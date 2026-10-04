package app.orbit.core

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * An extension: a user script, as made for Greasemonkey, Tampermonkey and Violentmonkey and shared
 * on sites like Greasy Fork. Android's WebView can't load Chrome or Firefox extensions, but user
 * scripts cover much the same ground: they run on the pages they match and change them.
 */
data class Extension(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val namespace: String = "",
    val version: String = "",
    val description: String = "",
    val author: String = "",
    /** The @match and @include patterns as written, to show where it runs (empty = everywhere). */
    val sites: List<String> = emptyList(),
    /** Regular expressions (shared by Kotlin and JavaScript) for the pages it runs on and skips. */
    val include: List<String> = listOf(".*"),
    val exclude: List<String> = emptyList(),
    /** "start", "end" or "idle": when on the page it runs. */
    val runAt: String = "idle",
    val noFrames: Boolean = false,
    /** Libraries from @require, fetched when it was installed. */
    val requires: List<String> = emptyList(),
    val libs: String = "",
    val code: String,
    /** Where it was installed from, if from a link. */
    val source: String? = null,
    val enabled: Boolean = true,
    val installed: Long = System.currentTimeMillis(),
) {
    fun runsOn(url: String): Boolean =
        include.any { UserScripts.matches(it, url) } && exclude.none { UserScripts.matches(it, url) }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("ns", namespace).put("v", version).put("d", description)
        .put("a", author).put("sites", JSONArray(sites)).put("inc", JSONArray(include)).put("exc", JSONArray(exclude))
        .put("at", runAt).put("nf", noFrames).put("req", JSONArray(requires)).put("libs", libs).put("code", code)
        .put("src", source).put("on", enabled).put("t", installed)

    companion object {
        fun fromJson(o: JSONObject): Extension {
            fun list(k: String) = o.optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            return Extension(
                id = o.getString("id"), name = o.getString("name"), namespace = o.optString("ns"),
                version = o.optString("v"), description = o.optString("d"), author = o.optString("a"),
                sites = list("sites"), include = list("inc"), exclude = list("exc"), runAt = o.optString("at", "idle"),
                noFrames = o.optBoolean("nf"), requires = list("req"), libs = o.optString("libs"), code = o.getString("code"),
                source = o.optString("src").takeIf { it.isNotEmpty() && it != "null" }, enabled = o.optBoolean("on", true),
                installed = o.optLong("t"),
            )
        }
    }
}

object UserScripts {
    /** Larger than any sensible script plus its libraries. */
    const val MAX_BYTES = 3 shl 20
    const val MAX_REQUIRES = 10

    /** A link to a user script, which Orbit offers to install instead of opening. */
    fun isScriptUrl(uri: Uri): Boolean = uri.path?.lowercase()?.endsWith(".user.js") == true

    /** Reads the ==UserScript== header. Null if [code] isn't a user script. */
    fun parse(code: String, source: String?): Extension? {
        val start = code.indexOf("==UserScript==")
        val end = code.indexOf("==/UserScript==")
        if (start < 0 || end < start) return null
        val meta = mutableMapOf<String, MutableList<String>>()
        val line = Regex("""^\s*//\s*@(\S+)(?:\s+(.*?))?\s*$""")
        code.substring(start, end).lineSequence().forEach { l ->
            val m = line.find(l) ?: return@forEach
            meta.getOrPut(m.groupValues[1].lowercase()) { mutableListOf() } += m.groupValues[2]
        }
        fun one(k: String) = meta[k]?.firstOrNull { it.isNotBlank() }.orEmpty()
        val name = one("name").ifBlank { return null }
        val matches = meta["match"].orEmpty().filter { it.isNotBlank() }
        val includes = meta["include"].orEmpty().filter { it.isNotBlank() }
        val inc = matches.mapNotNull(::matchRegex) + includes.map(::globRegex)
        val exc = meta["exclude-match"].orEmpty().mapNotNull(::matchRegex) + meta["exclude"].orEmpty().filter { it.isNotBlank() }.map(::globRegex)
        return Extension(
            name = name.take(80),
            namespace = one("namespace"),
            version = one("version").take(30),
            description = one("description").take(300),
            author = one("author").take(80),
            sites = matches + includes,
            // Without any @match or @include, a script runs everywhere (as in Greasemonkey).
            include = inc.ifEmpty { if (matches.isEmpty() && includes.isEmpty()) listOf(".*") else emptyList() },
            exclude = exc,
            runAt = when (one("run-at")) {
                "document-start" -> "start"
                "document-end", "document-body" -> "end"
                else -> "idle"
            },
            noFrames = "noframes" in meta,
            requires = meta["require"].orEmpty().filter { it.startsWith("https://") }.take(MAX_REQUIRES),
            code = code,
            source = source,
        )
    }

    private val compiled = HashMap<String, Regex?>()

    fun matches(pattern: String, url: String): Boolean {
        val r = synchronized(compiled) {
            compiled.getOrPut(pattern) { runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() }
        }
        return r?.containsMatchIn(url) == true
    }

    /** A Chrome-style @match pattern (scheme, host and path, each may use a star) to a regular expression. */
    private fun matchRegex(p: String): String? {
        if (p == "<all_urls>" || p == "*") return "^https?://"
        val m = Regex("""^(\*|https?)://([^/]*)(/.*)?$""").matchEntire(p.trim()) ?: return null
        val scheme = if (m.groupValues[1] == "*") "https?" else m.groupValues[1]
        val host = m.groupValues[2].substringBefore(':')
        val hostRe = when {
            host == "*" -> "[^/]*"
            host.startsWith("*.") -> "(?:[^/]*\\.)?" + escape(host.drop(2))
            else -> escape(host)
        }
        val path = m.groupValues[3].ifEmpty { "/" }
        return "^$scheme://$hostRe(?::\\d+)?" + path.split('*').joinToString(".*") { escape(it) } + "$"
    }

    /** Greasemonkey @include: a glob with `*`, or a /regular expression/. */
    private fun globRegex(p: String): String {
        val t = p.trim()
        Regex("""^/(.+)/[a-z]*$""").matchEntire(t)?.let { return it.groupValues[1] }
        return "^" + t.split('*').joinToString(".*") { escape(it) } + "$"
    }

    /** Escapes a literal for a regular expression read the same way by Java and JavaScript. */
    private fun escape(s: String) = buildString { s.forEach { c -> if (c in "\\^$.|?*+()[]{}/") append('\\'); append(c) } }

    /**
     * The script injected into pages: checks the page against the extension's patterns, sets up
     * the common Greasemonkey functions, and runs the code at the time it asked for. Values a
     * script saves are kept in the site's own storage, so they're per site.
     */
    fun script(e: Extension): String {
        val info = JSONObject()
            .put("id", e.id).put("name", e.name).put("version", e.version).put("description", e.description)
            .put("namespace", e.namespace).put("inc", JSONArray(e.include)).put("exc", JSONArray(e.exclude))
            .put("at", e.runAt).put("nf", e.noFrames)
        return "(function(){var I=$info;" + PRELUDE + "function run(){try{(function(){\n" + e.libs + "\n;\n" + e.code +
            "\n}).call(window);}catch(x){try{console.error('[Orbit extension] '+I.name,x);}catch(y){}}}" + LAUNCH + "})();"
    }

    /** Script sites that look for a script manager before letting you install. */
    val STORE_ORIGINS = setOf("https://greasyfork.org", "https://sleazyfork.org")

    /**
     * Tells Greasy Fork a script manager is here (the way Violentmonkey does), so its install
     * button works straight away and can say which scripts are already installed.
     */
    fun storeBridge(installed: List<Extension>): String {
        val versions = JSONObject().apply { installed.forEach { put(it.namespace + "|" + it.name, it.version) } }
        return "(function(){var V=$versions;try{var x=window.external;" +
            "if(!x||typeof x!=='object'){x={};Object.defineProperty(window,'external',{value:x,configurable:true});}" +
            "x.Violentmonkey={version:'2.18.0',isInstalled:function(n,s){var v=V[(s||'')+'|'+n];return Promise.resolve(v===undefined?null:v);}};" +
            "}catch(e){}})();"
    }

    private const val PRELUDE = """
if(location.protocol!=='https:'&&location.protocol!=='http:')return;
if(I.nf&&window.top!==window)return;
var G='__orbit_x_'+I.id;if(window[G])return;window[G]=1;
var u=location.href;function m(a){for(var i=0;i<a.length;i++){try{if(new RegExp(a[i],'i').test(u))return true;}catch(e){}}return false;}
if(!m(I.inc)||m(I.exc))return;
var K='__orbit_ext_'+I.id+'_';
var GM_info={script:{name:I.name,version:I.version,description:I.description,namespace:I.namespace},scriptHandler:'Orbit',version:'1.0'};
function GM_getValue(k,d){try{var v=localStorage.getItem(K+k);return v===null?d:JSON.parse(v);}catch(e){return d;}}
function GM_setValue(k,v){try{localStorage.setItem(K+k,JSON.stringify(v));}catch(e){}}
function GM_deleteValue(k){try{localStorage.removeItem(K+k);}catch(e){}}
function GM_listValues(){var r=[];try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);if(k.indexOf(K)===0)r.push(k.slice(K.length));}}catch(e){}return r;}
function GM_addStyle(c){var s=document.createElement('style');s.textContent=c;(document.head||document.documentElement).appendChild(s);return s;}
function GM_addElement(p,t,a){if(typeof p==='string'){a=t;t=p;p=document.head||document.documentElement;}var el=document.createElement(t);for(var k in (a||{})){if(k==='textContent')el.textContent=a[k];else el.setAttribute(k,a[k]);}p.appendChild(el);return el;}
function GM_openInTab(l){return window.open(l,'_blank');}
function GM_setClipboard(t){try{navigator.clipboard.writeText(String(t));}catch(e){}}
function GM_registerMenuCommand(){return 0;}function GM_unregisterMenuCommand(){}
function GM_notification(){}function GM_log(){try{console.log.apply(console,arguments);}catch(e){}}
function GM_getResourceText(){return '';}function GM_getResourceURL(){return '';}
function GM_xmlhttpRequest(d){var c=window.AbortController?new AbortController():null;
function fail(x){var o={error:String(x),status:0,readyState:4};if(d.onerror)d.onerror(o);if(d.onloadend)d.onloadend(o);}
try{fetch(d.url,{method:d.method||'GET',headers:d.headers||{},body:d.data,credentials:d.anonymous?'omit':'include',signal:c?c.signal:undefined})
.then(function(r){return r.text().then(function(t){var h='';r.headers.forEach(function(v,k){h+=k+': '+v+'\r\n';});
var o={status:r.status,statusText:r.statusText,responseText:t,response:t,responseHeaders:h,finalUrl:r.url,readyState:4};
if(d.responseType==='json'){try{o.response=JSON.parse(t);}catch(e){o.response=null;}}
if(d.onload)d.onload(o);if(d.onloadend)d.onloadend(o);});}).catch(fail);}catch(x){fail(x);}
return{abort:function(){if(c)c.abort();}};}
var unsafeWindow=window;
function P(f){return function(){var a=arguments;return new Promise(function(ok,no){try{ok(f.apply(null,a));}catch(e){no(e);}});};}
var GM={info:GM_info,getValue:P(GM_getValue),setValue:P(GM_setValue),deleteValue:P(GM_deleteValue),listValues:P(GM_listValues),
addStyle:P(GM_addStyle),addElement:P(GM_addElement),openInTab:P(GM_openInTab),setClipboard:P(GM_setClipboard),
registerMenuCommand:P(GM_registerMenuCommand),notification:P(GM_notification),xmlHttpRequest:GM_xmlhttpRequest,
getResourceUrl:P(GM_getResourceURL)};
"""

    private const val LAUNCH = """
if(I.at==='start')run();
else if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',function(){if(I.at==='idle')setTimeout(run,0);else run();},{once:true});
else run();
"""
}
