package app.orbit.core

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

/** File names, saving and opening for downloads (the downloading itself is in [Browser]). */
object Downloads {
    /** Files made by pages (blob: and data: links) are read from the page; this caps how big. */
    const val MAX_PAGE_FILE = 512L shl 20

    /** Extensions that name the server's script rather than the file it sends. */
    private val SCRIPT_EXTENSIONS = setOf("php", "asp", "aspx", "jsp", "cgi", "pl", "do", "action", "htm", "html")

    /**
     * The name to save a download under: the one the server or page gives, else the end of the
     * link, with an extension that matches the type when the name has none. Unlike Android's
     * URLUtil.guessFileName, a "file.pdf" sent as application/octet-stream stays "file.pdf".
     */
    fun fileName(url: String, disposition: String?, mime: String?, suggested: String? = null): String {
        val type = baseMime(mime)
        var name = suggested?.takeIf { it.isNotBlank() }
            ?: fromDisposition(disposition)
            ?: if (url.startsWith("http")) runCatching { Uri.parse(url).lastPathSegment }.getOrNull() else null
        name = clean(name.orEmpty()).ifEmpty { "download" }
        val ext = name.substringAfterLast('.', "").lowercase()
        val typeExt = type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        if (typeExt != null && type != "application/octet-stream") {
            if (ext.isEmpty() || ext.length > 8) name += ".$typeExt"
            else if (ext in SCRIPT_EXTENSIONS && type != "text/html") name = name.substringBeforeLast('.') + ".$typeExt"
        }
        return name
    }

    /** The type to save [name] as: the server's, unless it only says "some bytes". */
    fun mimeFor(name: String, mime: String?): String {
        val type = baseMime(mime)
        if (type != null && type != "application/octet-stream" && type != "binary/octet-stream") return type
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: type ?: "application/octet-stream"
    }

    private fun baseMime(mime: String?) = mime?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.contains('/') }

    private fun fromDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        Regex("filename\\*\\s*=\\s*([\\w-]+)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(disposition)?.let { m ->
            runCatching { return URLDecoder.decode(m.groupValues[2].trim().trim('"'), m.groupValues[1]) }
        }
        Regex("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)", RegexOption.IGNORE_CASE).find(disposition)?.let { m ->
            val raw = m.groupValues[2].ifEmpty { m.groupValues[1] }.trim()
            return runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
        }
        return null
    }

    private fun clean(name: String): String {
        var n = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f\"*:<>?|]"), "_")
            .trim().trim('.').trim()
        if (n.length > 120) {
            val ext = n.substringAfterLast('.', "").take(10)
            n = n.take(120 - ext.length - 1).trimEnd() + if (ext.isNotEmpty()) ".$ext" else ""
        }
        return n
    }

    /**
     * Puts [file] in the phone's Downloads folder as [name], where the Files app and other apps
     * find it. Returns a link other apps may open, or null if it couldn't be saved.
     */
    fun save(context: Context, file: File, name: String, mime: String): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            // Before Android 10 the shared Downloads folder needs a storage permission, so the file
            // stays in Orbit's own folder and is listed in the Downloads app.
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
            var target = File(dir, name)
            var i = 1
            while (target.exists()) target = File(dir, name.substringBeforeLast('.') + " (${i++})" + name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" })
            file.copyTo(target)
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            @Suppress("DEPRECATION")
            val id = dm.addCompletedDownload(target.name, target.name, true, mime, target.absolutePath, target.length(), false)
            dm.getUriForDownloadedFile(id)
        }
    }.getOrNull()

    /** Opens a downloaded file in an app that can show it. False if there's none. */
    fun open(context: Context, uri: Uri, mime: String?): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /**
     * At document start in every frame: remembers the name a page gives a file it offers
     * (`<a download="report.csv">`), and keeps blob: files readable for a minute after the page
     * lets go of them, since many pages do that right after starting the download.
     */
    const val PAGE_HOOK = """
(function(){if(window.__orbitDl)return;var S=window.__orbitDl={blobs:{},names:{}};
try{var cu=URL.createObjectURL,ru=URL.revokeObjectURL;
URL.createObjectURL=function(o){var u=cu.apply(URL,arguments);try{if(o instanceof Blob)S.blobs[u]=o;}catch(e){}return u;};
URL.revokeObjectURL=function(u){var a=arguments;setTimeout(function(){delete S.blobs[u];try{ru.apply(URL,a);}catch(e){}},60000);};}catch(e){}
function note(a){try{if(a&&a.href&&a.hasAttribute&&a.hasAttribute('download'))S.names[a.href]=a.getAttribute('download')||'';}catch(e){}}
document.addEventListener('click',function(e){var t=e.target;while(t&&t.tagName!=='A')t=t.parentElement;note(t);},true);
try{var ck=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){note(this);return ck.apply(this,arguments);};}catch(e){}
})();"""

    /**
     * Reads a blob: or data: file in the page and sends it to OrbitDownload in pieces, tagged
     * with [id] so the app only takes files it asked for.
     */
    fun readScript(url: String, id: String) = """
(function(u,id){var B=window.OrbitDownload;if(!B)return 'nobridge';var S=window.__orbitDl||{blobs:{},names:{}};
function post(o){o.id=id;B.postMessage(JSON.stringify(o));}
var name=S.names[u]||'';
(S.blobs[u]?Promise.resolve(S.blobs[u]):fetch(u).then(function(r){return r.blob();})).then(function(b){
if(b.size>${MAX_PAGE_FILE}){post({error:'big'});return;}
post({start:true,type:b.type||'',size:b.size,name:name});var CH=393216,off=0;
function next(){if(off>=b.size){post({done:true});return;}
var fr=new FileReader();fr.onload=function(){var s=String(fr.result);post({data:s.slice(s.indexOf(',')+1)});off+=CH;next();};
fr.onerror=function(){post({error:'read'});};fr.readAsDataURL(b.slice(off,off+CH));}
next();}).catch(function(e){post({error:String(e)});});return 'ok';})(${JSONObject.quote(url)},${JSONObject.quote(id)})"""
}
