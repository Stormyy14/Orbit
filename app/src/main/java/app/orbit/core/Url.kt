package app.orbit.core

import android.net.Uri
import android.util.Patterns
import java.net.URLEncoder

object Url {
    fun host(url: String?): String? = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()

    /** "www.m.example.co.uk" -> "example.co.uk" (good-enough eTLD+1 heuristic). */
    fun site(host: String): String {
        val parts = host.lowercase().removePrefix("www.").split('.')
        if (parts.size <= 2) return parts.joinToString(".")
        val sld = parts[parts.size - 2]
        val twoLevel = sld.length <= 3 && sld in setOf("co", "com", "org", "net", "gov", "ac", "edu", "ne", "or", "gob")
        return parts.takeLast(if (twoLevel) 3 else 2).joinToString(".")
    }

    fun sameSite(a: String, b: String) = site(a) == site(b)

    fun pretty(url: String): String {
        val h = host(url) ?: return url
        return h.removePrefix("www.").removePrefix("m.")
    }

    fun isSecure(url: String) = url.startsWith("https://")

    /**
     * A human name for a site, taken from its page title when possible:
     * "Breaking News | CNN" -> "CNN", "GitHub · Change is constant" -> "GitHub".
     */
    fun siteName(title: String, url: String): String {
        val host = host(url) ?: return title.ifBlank { url }
        val core = site(host).substringBefore('.')
        val parts = title.split(Regex("\\s+[-|·–—:]\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val want = core.lowercase()
        parts.filter { it.length <= 24 && it.replace(" ", "").lowercase().contains(want) }
            .minByOrNull { it.length }
            ?.let { return it }
        // "Wiki Loves Monuments Portugal 2025" for wikilovesmonuments -> "Wiki Loves Monuments"
        for (part in parts) {
            val words = part.split(' ').filter { it.isNotBlank() }
            for (start in words.indices) {
                var joined = ""
                for (end in start until words.size) {
                    joined += words[end].lowercase().filter { it.isLetterOrDigit() }
                    if (joined == want) return words.subList(start, end + 1).joinToString(" ")
                    if (!want.startsWith(joined)) break
                }
            }
        }
        return core.replaceFirstChar { it.uppercase() }
    }

    fun matchesDomain(url: String, domains: List<String>): Boolean {
        val h = host(url) ?: return false
        return domains.any { d -> h == d || h.endsWith(".$d") }
    }

    private val bangs = linkedMapOf(
        "g" to "https://www.google.com/search?q=%s",
        "d" to "https://duckduckgo.com/?q=%s",
        "b" to "https://search.brave.com/search?q=%s",
        "yt" to "https://www.youtube.com/results?search_query=%s",
        "w" to "https://en.wikipedia.org/wiki/Special:Search?search=%s",
        "gh" to "https://github.com/search?q=%s",
        "r" to "https://www.reddit.com/search/?q=%s",
        "m" to "https://www.google.com/maps/search/%s",
        "a" to "https://www.amazon.com/s?k=%s",
        "so" to "https://stackoverflow.com/search?q=%s",
        "tr" to "https://translate.google.com/?sl=auto&tl=en&text=%s",
        "img" to "https://duckduckgo.com/?ia=images&iax=images&q=%s",
        "npm" to "https://www.npmjs.com/search?q=%s",
        "mdn" to "https://developer.mozilla.org/en-US/search?q=%s",
    )

    /** (bang, host) pairs, for hints in the command palette. */
    val bangList: List<Pair<String, String>> = bangs.map { (k, v) -> k to pretty(v.replace("%s", "x")) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Turns raw omnibox input into a URL: a URL, a host, a !bang, or a search. */
    fun resolve(input: String, engine: SearchEngine): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        bang(text)?.let { (template, q) -> return template.replace("%s", enc(q)) }
        // Never execute or open local/app content from the address bar (self-XSS, paste-jacking).
        val scheme = text.substringBefore(':', "").lowercase()
        if (scheme in setOf("javascript", "vbscript", "file", "content", "intent")) return search(text, engine)
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text)) return text
        if (text.startsWith("about:") || text.startsWith("data:")) return text
        if (!text.contains(' ')) {
            if (text.startsWith("localhost") || Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$").matches(text)) {
                return "http://$text"
            }
            if (text.contains('.') && Patterns.WEB_URL.matcher(text).matches()) return "https://$text"
        }
        return search(text, engine)
    }

    fun search(text: String, engine: SearchEngine) = engine.template.replace("%s", enc(text))

    /** "!yt lofi" or "lofi !yt" -> (template, "lofi") */
    fun bang(text: String): Pair<String, String>? {
        val tokens = text.split(' ').filter { it.isNotBlank() }
        val b = tokens.firstOrNull { it.startsWith("!") && it.length > 1 } ?: return null
        val template = bangs[b.drop(1).lowercase()] ?: return null
        return template to tokens.filter { it != b }.joinToString(" ")
    }

    /** Pulls the first http(s) link out of shared text, if any. */
    fun extract(text: String): String? {
        val m = Patterns.WEB_URL.matcher(text)
        while (m.find()) {
            val found = m.group()
            if (found.startsWith("http")) return found
        }
        return null
    }
}
