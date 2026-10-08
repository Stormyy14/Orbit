package app.orbit.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale

data class ReaderBlock(val type: String, val text: String)

data class ReaderDoc(
    val title: String,
    val byline: String,
    val site: String,
    val hero: String,
    val words: Int,
    val blocks: List<ReaderBlock>,
    val url: String,
    /** The page showed signs of a paywall, so it may hold only the start of the article. */
    val paywall: Boolean = false,
    /** Words in the article's paragraphs, which is what tells two versions of a page apart. */
    val textWords: Int = words,
) {
    val minutes get() = (words / 230).coerceAtLeast(1)

    companion object {
        fun parse(json: String, url: String): ReaderDoc? = runCatching {
            val o = JSONObject(json)
            if (o.has("error")) return null
            val arr = o.getJSONArray("blocks")
            val blocks = ArrayList<ReaderBlock>(arr.length())
            var lastText = ""
            val title = o.optString("title")
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                val text = b.optString("v")
                val type = b.optString("t")
                if (text == lastText) continue
                if (type == "h1" && text == title) continue
                lastText = text
                blocks += ReaderBlock(type, text)
            }
            if (blocks.count { it.type == "p" } < 2) return null
            ReaderDoc(
                title = title,
                byline = o.optString("byline"),
                site = o.optString("site"),
                hero = o.optString("hero"),
                words = o.optInt("words"),
                blocks = blocks,
                url = url,
                paywall = o.optBoolean("paywall"),
                textWords = o.optInt("text", o.optInt("words")),
            )
        }.getOrNull()
    }
}

/** Other copies of a page for reader view, for when the page in the tab only shows part of it. */
object Reader {
    private const val MAX_BYTES = 4 shl 20
    private const val GOOGLEBOT = "Mozilla/5.0 (Linux; Android 6.0.1; Nexus 5X Build/MMB29P) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

    /**
     * The page as it comes from the server without cookies or scripts (many paywalls are only
     * put up by scripts afterwards), as search engines get it, and its latest copy on the
     * Internet Archive. Whichever could be fetched, at the same time; never more than ~15s.
     */
    suspend fun fetchVersions(url: String, userAgent: String): List<String> = withContext(Dispatchers.IO) {
        coroutineScope {
            listOf(
                async { fetch(url, userAgent, "https://www.google.com/") },
                async { fetch(url, GOOGLEBOT, null) },
                async { archived(url)?.let { fetch(it, userAgent, null) } },
            ).awaitAll().filterNotNull()
        }
    }

    private fun archived(url: String): String? = runCatching {
        val conn = Net.open("https://archive.org/wayback/available?url=${URLEncoder.encode(url, "UTF-8")}", 5000, 6000)
        val o = try {
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
        val snap = o.optJSONObject("archived_snapshots")?.optJSONObject("closest") ?: return null
        val ts = snap.optString("timestamp")
        if (!snap.optBoolean("available") || ts.isEmpty()) return null
        // "id_" is the page exactly as archived, without the archive's toolbar or rewritten links.
        "https://web.archive.org/web/${ts}id_/$url"
    }.getOrNull()

    private fun fetch(url: String, userAgent: String, referer: String?): String? = runCatching {
        val conn = Net.open(url, connectMs = 6000, readMs = 9000)
        try {
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
            conn.setRequestProperty("Accept-Language", "${Locale.getDefault().toLanguageTag()},en;q=0.8")
            if (referer != null) conn.setRequestProperty("Referer", referer)
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val type = conn.contentType.orEmpty()
            if (type.isNotEmpty() && !type.contains("html", ignoreCase = true)) return null
            val bytes = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < MAX_BYTES) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            String(bytes, charsetOf(type, bytes))
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun charsetOf(contentType: String, bytes: ByteArray): Charset {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val name = Regex("charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).let { it.find(contentType) ?: it.find(head) }?.groupValues?.get(1)
        return name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }
}
