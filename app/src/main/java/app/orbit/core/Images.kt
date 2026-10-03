package app.orbit.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Minimal image loading: favicons cached on disk per site, remote images in memory. */
object Images {
    private val memory = LruCache<String, ImageBitmap>(48)
    private lateinit var iconDir: File

    /** One network fetch per site; concurrent callers wait for the same result. */
    private val inflight = HashMap<String, CompletableDeferred<ImageBitmap?>>()

    fun init(context: Context) {
        iconDir = File(context.filesDir, "icons").apply { mkdirs() }
        // v0.3: earlier builds could save a favicon under the wrong site; start clean once.
        val marker = File(iconDir, ".v3")
        if (!marker.exists()) {
            iconDir.listFiles()?.forEach { it.delete() }
            marker.createNewFile()
        }
    }

    private fun iconFile(host: String) = File(iconDir, Url.site(host) + ".png")

    fun saveIcon(host: String, bitmap: Bitmap) {
        runCatching {
            val f = iconFile(host)
            f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            memory.remove("icon:" + Url.site(host))
        }
    }

    suspend fun icon(host: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "icon:" + Url.site(host)
        memory.get(key)
            ?: runCatching { BitmapFactory.decodeFile(iconFile(host).path)?.asImageBitmap()?.also { memory.put(key, it) } }.getOrNull()
            ?: fetchOnce(host)?.also { memory.put(key, it) }
    }

    private suspend fun fetchOnce(host: String): ImageBitmap? {
        val site = Url.site(host)
        var mine = false
        val job = synchronized(inflight) {
            inflight.getOrPut(site) { mine = true; CompletableDeferred() }
        }
        if (mine) job.complete(fetchIcon(host))
        return job.await()
    }

    /** Reads at most [max] bytes; returns null if the body is larger (protects against huge files). */
    private fun readCapped(input: java.io.InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** For sites never visited (e.g. suggested favorites), ask the site itself for its icon. */
    private fun fetchIcon(host: String): ImageBitmap? {
        for (path in listOf("/apple-touch-icon.png", "/favicon.ico")) {
            val bmp = runCatching {
                val conn = URL("https://$host$path").openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Orbit")
                if (conn.responseCode != 200) return@runCatching null
                val bytes = conn.inputStream.use { readCapped(it, 512 shl 10) } ?: return@runCatching null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            if (bmp != null && bmp.width >= 16) {
                saveIcon(host, bmp)
                return bmp.asImageBitmap()
            }
        }
        return null
    }

    suspend fun remote(url: String, maxWidth: Int = 1200): ImageBitmap? = withContext(Dispatchers.IO) {
        memory.get(url) ?: runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Orbit")
            val bytes = conn.inputStream.use { readCapped(it, 15 shl 20) } ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()?.also { memory.put(url, it) }
        }.getOrNull()
    }
}
