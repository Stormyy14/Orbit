package app.orbit.core

/**
 * A content-blocking engine for Adblock Plus filter syntax, the format of EasyList and
 * EasyPrivacy: network rules (with their options and `@@` exceptions) and element-hiding
 * rules (`##`, `#@#`, `#?#`). Pure Kotlin, so it's unit-tested on the JVM.
 *
 * Anything it doesn't fully understand is skipped rather than guessed, so an unsupported
 * rule can never block or hide more than it should. Snippets/scriptlets (rules that run
 * code) are never supported.
 */
class FilterEngine {
    private val blocks = NetIndex()
    private val important = NetIndex()
    private val exceptions = NetIndex()
    /** `@@…$document`, `$elemhide`, `$generichide`, `$genericblock`: page-wide exceptions. */
    private val pageExceptions = NetIndex()
    /** The common `||host^` rules with no other options, kept as hashes (most of the lists). */
    private val hosts = HostSet()
    private val cosmetics = Cosmetics()

    var networkRules = 0
        private set
    var cosmeticRules = 0
        private set

    /** Adds every rule in a filter list's [text]. Rules remember [list] (a bit) so lists can be switched off. */
    fun addList(text: String, list: Int) {
        var start = 0
        val n = text.length
        while (start < n) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = n
            addLine(text.substring(start, end), list)
            start = end + 1
        }
    }

    fun addLine(raw: String, list: Int) {
        val line = raw.trim()
        if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
        if (line.length > 4096) return
        val cos = cosmeticSeparator(line)
        if (cos != null) {
            if (cosmetics.add(line, cos.first, cos.second, list)) cosmeticRules++
            return
        }
        if (addNetwork(line, list)) networkRules++
    }

    /** Call once every list is in: compacts the host hashes. */
    fun finish() {
        hosts.finish()
    }

    // ---- network ----

    /**
     * The rule that blocks [r], or null if it's allowed. [lists] is the set of enabled lists,
     * [pageFlags] what [pageFlags] returned for the page making the request.
     */
    fun match(r: Request, lists: Int, pageFlags: Int = 0): String? {
        if (pageFlags and RType.DOCUMENT != 0) return null
        val generic = pageFlags and RType.GENERICBLOCK == 0
        important.find(r, lists, generic)?.let { return it.text }
        val hit = (if (generic) hosts.find(r, lists)?.let { "||$it^" } else null)
            ?: blocks.find(r, lists, generic)?.text ?: return null
        if (exceptions.find(r, lists, true) != null) return null
        return hit
    }

    /** Page-wide exceptions for a page at [url]: a mask of DOCUMENT/ELEMHIDE/GENERICHIDE/GENERICBLOCK. */
    fun pageFlags(url: String, lists: Int): Int {
        val r = Request(url, Request.hostOf(url), PAGE_TYPES)
        var flags = 0
        pageExceptions.forEachMatch(r, lists) { flags = flags or (it.types and PAGE_TYPES) }
        return flags
    }

    // ---- element hiding ----

    /**
     * CSS for a page on [host]: its site-specific rules, plus generic rules that can't be
     * looked up by class or id (unless [generic] is false).
     */
    fun pageCss(host: String, lists: Int, generic: Boolean): String = cosmetics.pageCss(host, lists, generic)

    /** CSS for generic rules about the [classes] and [ids] found on a page on [host]. */
    fun namesCss(host: String, classes: Collection<String>, ids: Collection<String>, lists: Int): String =
        cosmetics.namesCss(host, classes, ids, lists)

    private fun addNetwork(line: String, list: Int): Boolean {
        var s = line
        val exception = s.startsWith("@@")
        if (exception) s = s.substring(2)
        if (s.isEmpty()) return false

        // Options follow the last '$', unless that '$' belongs to a /regex/.
        var options: String? = null
        val dollar = s.lastIndexOf('$')
        if (dollar >= 0 && dollar + 1 < s.length) {
            val tail = s.substring(dollar + 1)
            if (OPTIONS.matches(tail)) {
                options = tail
                s = s.substring(0, dollar)
            } else if (tail.contains('=') || tail.contains(',')) return false // options we can't read
        }

        var types = 0
        var notTypes = 0
        var party = 0
        var domains: DomainList? = null
        var matchCase = false
        var isImportant = false
        var methods = 0
        var notMethods = 0
        var popupOnly = false
        if (options != null) {
            for (opt in options.split(',')) {
                if (opt.isEmpty()) return false
                val neg = opt[0] == '~'
                val name = (if (neg) opt.substring(1) else opt).lowercase()
                val eq = name.indexOf('=')
                val key = if (eq >= 0) name.substring(0, eq) else name
                val value = if (eq >= 0) opt.substring(opt.indexOf('=') + 1) else null
                val t = TYPE_NAMES[key]
                when {
                    t != null -> if (neg) notTypes = notTypes or t else types = types or t
                    key == "popup" || key == "popunder" -> if (!neg) popupOnly = true
                    key == "third-party" || key == "3p" -> party = if (neg) FIRST else THIRD
                    key == "first-party" || key == "1p" -> party = if (neg) THIRD else FIRST
                    (key == "domain" || key == "from") && value != null && !neg -> domains = DomainList.parse(value, '|') ?: return false
                    key == "match-case" && !neg -> matchCase = true
                    key == "important" && !neg -> isImportant = true
                    key == "method" && value != null && !neg -> {
                        for (m in value.lowercase().split('|')) {
                            val mn = m.removePrefix("~")
                            val bit = METHODS[mn] ?: return false
                            if (m.startsWith("~")) notMethods = notMethods or bit else methods = methods or bit
                        }
                    }
                    // Anything else (csp, redirect, removeparam, rewrite, header, ...) changes
                    // requests in ways we don't do; skip the whole rule.
                    else -> return false
                }
            }
        }
        val pageLevel = types and PAGE_TYPES
        types = types and PAGE_TYPES.inv()
        if (popupOnly && types == 0 && pageLevel == 0) return false
        var mask = if (types == 0 && pageLevel == 0) RType.RESOURCES else types
        mask = mask and notTypes.inv()
        if (methods == 0 && notMethods != 0) methods = ALL_METHODS
        methods = methods and notMethods.inv()

        if (pageLevel != 0) {
            // Only exceptions can be page-wide. A $document block would stop pages loading; we
            // never block whole pages, so those rules keep only their resource types (if any).
            if (exception) {
                val f = build(s, pageLevel, party, domains, matchCase, list, methods, line) ?: return false
                pageExceptions.add(f)
                if (types == 0) return true
            } else if (types == 0) return false
        }
        if (mask == 0) return false

        val f = build(s, mask, party, domains, matchCase, list, methods, line) ?: return false
        if (!exception && !isImportant && domains == null && methods == 0 && mask == RType.RESOURCES) {
            f.pureHost()?.let { h ->
                hosts.add(h, list, party)
                return true
            }
        }
        when {
            exception -> exceptions.add(f)
            isImportant -> important.add(f)
            else -> blocks.add(f)
        }
        return true
    }

    private fun build(
        pattern: String, types: Int, party: Int, domains: DomainList?, matchCase: Boolean, list: Int, methods: Int, text: String,
    ): NetFilter? {
        if (pattern.length >= 2 && pattern[0] == '/' && pattern.endsWith("/")) {
            val body = pattern.substring(1, pattern.length - 1)
            if (body.length > 1000) return null
            val re = runCatching {
                if (matchCase) Regex(body) else Regex(body, RegexOption.IGNORE_CASE)
            }.getOrNull() ?: return null
            return NetFilter(text, emptyArray(), false, false, false, re, types, party, domains, matchCase, list, methods)
        }
        var p = pattern
        val hostAnchor = p.startsWith("||")
        if (hostAnchor) p = p.substring(2)
        var startAnchor = !hostAnchor && p.startsWith("|")
        if (startAnchor) p = p.substring(1)
        var endAnchor = p.endsWith("|")
        if (endAnchor) p = p.substring(0, p.length - 1)
        while (p.contains("**")) p = p.replace("**", "*")
        if (p.startsWith("*")) { p = p.substring(1); startAnchor = false }
        if (p.endsWith("*")) { p = p.substring(0, p.length - 1); endAnchor = false }
        if (p.contains('|')) return null
        if (!matchCase) p = p.lowercase()
        val segments = if (p.isEmpty()) emptyArray() else p.split('*').toTypedArray()
        val anchoredHost = hostAnchor && segments.isNotEmpty()
        return NetFilter(text, segments, anchoredHost, startAnchor, endAnchor, null, types, party, domains, matchCase, list, methods)
    }

    companion object {
        const val FIRST = 1
        const val THIRD = 2
        const val PAGE_TYPES = RType.DOCUMENT or RType.ELEMHIDE or RType.GENERICHIDE or RType.GENERICBLOCK
        private val OPTIONS = Regex("[A-Za-z0-9~_\\-=|.,*:%]+")

        private val TYPE_NAMES = mapOf(
            "script" to RType.SCRIPT, "image" to RType.IMAGE, "stylesheet" to RType.STYLESHEET, "css" to RType.STYLESHEET,
            "subdocument" to RType.SUBDOCUMENT, "frame" to RType.SUBDOCUMENT, "xmlhttprequest" to RType.XHR, "xhr" to RType.XHR,
            "media" to RType.MEDIA, "font" to RType.FONT, "object" to RType.OBJECT, "object-subrequest" to RType.OBJECT,
            "ping" to RType.PING, "beacon" to RType.PING, "websocket" to RType.WEBSOCKET,
            "other" to RType.OTHER, "document" to RType.DOCUMENT, "doc" to RType.DOCUMENT,
            "elemhide" to RType.ELEMHIDE, "ehide" to RType.ELEMHIDE, "generichide" to RType.GENERICHIDE,
            "ghide" to RType.GENERICHIDE, "genericblock" to RType.GENERICBLOCK,
        )

        private val METHODS = mapOf(
            "get" to 1, "post" to 2, "put" to 4, "delete" to 8, "head" to 16, "options" to 32, "patch" to 64, "connect" to 128, "trace" to 256,
        )
        private const val ALL_METHODS = 511

        fun methodBit(method: String): Int = METHODS[method.lowercase()] ?: 0

        /** Where a cosmetic rule's separator is, and which kind it is; null for network rules. */
        internal fun cosmeticSeparator(line: String): Pair<Int, String>? {
            var from = 0
            while (true) {
                val i = line.indexOf('#', from)
                if (i < 0) return null
                for (sep in COSMETIC_SEPARATORS) if (line.startsWith(sep, i)) return i to sep
                from = i + 1
            }
        }

        private val COSMETIC_SEPARATORS = listOf("##", "#@#", "#?#", "#@?#", "#$#", "#@$#", "#%#", "#@%#", "#$?#", "#@$?#")
    }
}

/** Request types (ABP's `$script`, `$image`, …) as a bit mask. */
object RType {
    const val SCRIPT = 1
    const val IMAGE = 1 shl 1
    const val STYLESHEET = 1 shl 2
    const val SUBDOCUMENT = 1 shl 3
    const val XHR = 1 shl 4
    const val MEDIA = 1 shl 5
    const val FONT = 1 shl 6
    const val OBJECT = 1 shl 7
    const val PING = 1 shl 8
    const val WEBSOCKET = 1 shl 9
    const val OTHER = 1 shl 10
    const val RESOURCES = (1 shl 11) - 1
    const val DOCUMENT = 1 shl 11
    const val ELEMHIDE = 1 shl 12
    const val GENERICHIDE = 1 shl 13
    const val GENERICBLOCK = 1 shl 14
}

/** A request to check. [types] may hold several bits when the exact type can't be known. */
class Request(val url: String, val pageHost: String?, val types: Int, method: String = "GET") {
    val lower: String = url.lowercase()
    val hostStart: Int
    val hostEnd: Int
    val host: String
    val method: Int = FilterEngine.methodBit(method)

    init {
        val scheme = lower.indexOf("://")
        var hs = if (scheme >= 0) scheme + 3 else 0
        var he = hs
        while (he < lower.length && lower[he] != '/' && lower[he] != '?' && lower[he] != '#') he++
        val at = lower.lastIndexOf('@', he - 1)
        if (at >= hs) hs = at + 1
        val colon = lower.indexOf(':', hs)
        if (colon in hs until he && lower[hs] != '[') he = colon
        hostStart = hs
        hostEnd = he
        host = lower.substring(hs, he)
    }

    /** Unknown pages count as third-party, so the stricter rules apply. */
    val thirdParty: Boolean = pageHost == null || Domains.site(host) != Domains.site(pageHost)

    companion object {
        fun hostOf(url: String): String = Request(url, "", 0).host
    }
}

class NetFilter internal constructor(
    val text: String,
    private val segments: Array<String>,
    private val hostAnchor: Boolean,
    private val startAnchor: Boolean,
    private val endAnchor: Boolean,
    private val regex: Regex?,
    val types: Int,
    private val party: Int,
    val domains: DomainList?,
    private val matchCase: Boolean,
    private val list: Int,
    private val methods: Int,
) {
    /** "example.com" for a plain `||example.com^` rule. */
    internal fun pureHost(): String? {
        if (!hostAnchor || regex != null || endAnchor || segments.size != 1) return null
        val s = segments[0]
        if (s.length < 3 || !s.endsWith("^")) return null
        val h = s.substring(0, s.length - 1)
        return if (h.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' } && h.contains('.') && !h.startsWith('.')) h else null
    }

    /** The index token: a run of URL characters this rule's URLs must contain whole. */
    internal fun token(): String? {
        if (regex != null) return null
        var best: String? = null
        for ((si, seg) in segments.withIndex()) {
            var i = 0
            while (i < seg.length) {
                if (!isTokenChar(seg[i])) { i++; continue }
                var j = i
                while (j < seg.length && isTokenChar(seg[j])) j++
                val leftOk = if (i > 0) true else (si == 0 && (hostAnchor || startAnchor))
                val rightOk = if (j < seg.length) true else (si == segments.lastIndex && endAnchor)
                val t = seg.substring(i, j).lowercase()
                if (leftOk && rightOk && t.length >= 2 && t !in COMMON && (best == null || t.length > best.length)) best = t
                i = j
            }
        }
        return best
    }

    fun optionsMatch(r: Request, lists: Int): Boolean {
        if (list and lists == 0) return false
        if (types and r.types == 0) return false
        // A rule for every URL of some type ("*$ping,third-party") only applies when the request's
        // type is known; a request that merely might be of that type would be blocked for nothing.
        if (regex == null && segments.isEmpty() && types != RType.RESOURCES && r.types and (r.types - 1) != 0) return false
        if (party == FilterEngine.THIRD && !r.thirdParty) return false
        if (party == FilterEngine.FIRST && r.thirdParty) return false
        if (methods != 0 && methods and r.method == 0) return false
        if (domains != null && !domains.matches(r.pageHost)) return false
        return true
    }

    fun urlMatches(r: Request): Boolean {
        val u = if (matchCase) r.url else r.lower
        if (regex != null) return safeFind(regex, u)
        if (segments.isEmpty()) return true
        if (hostAnchor) {
            var p = r.hostStart
            while (p < r.hostEnd) {
                if (matchFrom(u, p)) return true
                val dot = u.indexOf('.', p)
                if (dot < 0 || dot >= r.hostEnd) return false
                p = dot + 1
            }
            return false
        }
        if (startAnchor) return matchFrom(u, 0)
        // Unanchored: the leftmost place the first part fits is always the best one to try.
        val first = segments[0]
        var from = 0
        while (from <= u.length) {
            val at = find(u, from, first)
            if (at < 0) return false
            if (matchRest(u, at, segAt(u, at, first))) return true
            // Only an end anchor can make a later start succeed (when there's a single part).
            if (!(endAnchor && segments.size == 1)) return false
            from = at + 1
        }
        return false
    }

    private fun matchFrom(u: String, start: Int): Boolean {
        val end = segAt(u, start, segments[0])
        if (end < 0) return false
        return matchRest(u, start, end)
    }

    private fun matchRest(u: String, firstStart: Int, firstEnd: Int): Boolean {
        if (firstEnd < 0) return false
        var pos = firstEnd
        val last = segments.lastIndex
        if (last == 0) return !endAnchor || pos == u.length
        for (k in 1 until last) {
            val at = find(u, pos, segments[k])
            if (at < 0) return false
            pos = segAt(u, at, segments[k])
        }
        val seg = segments[last]
        if (!endAnchor) return find(u, pos, seg) >= 0
        // The last part has to end exactly at the end of the URL.
        val len = seg.length
        val candidates = if (seg.endsWith("^")) intArrayOf(u.length - len, u.length - len + 1) else intArrayOf(u.length - len)
        for (c in candidates) if (c >= pos && segAt(u, c, seg) == u.length) return true
        return false
    }

    companion object {
        private val COMMON = setOf("http", "https", "www", "com", "net", "org", "js", "html", "php", "jpg", "png", "gif")

        fun isTokenChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '%' || c in 'A'..'Z'

        /** ABP's '^': anything but a letter, digit, or one of _ - . % */
        private fun isSeparator(c: Char) = !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

        /** End index if [seg] matches [u] at [pos], else -1. '^' may also match the end of the URL. */
        private fun segAt(u: String, pos: Int, seg: String): Int {
            var i = pos
            if (i < 0) return -1
            for (k in seg.indices) {
                val c = seg[k]
                if (c == '^') {
                    if (i == u.length) return if (k == seg.lastIndex) i else -1
                    if (!isSeparator(u[i])) return -1
                } else if (i >= u.length || u[i] != c) return -1
                i++
            }
            return i
        }

        /** Leftmost position at or after [from] where [seg] matches, or -1. */
        private fun find(u: String, from: Int, seg: String): Int {
            if (seg.isEmpty()) return from
            val lead = seg[0]
            var p = from
            while (p <= u.length) {
                if (lead != '^') {
                    p = u.indexOf(lead, p)
                    if (p < 0) return -1
                }
                if (segAt(u, p, seg) >= 0) return p
                p++
            }
            return -1
        }

        /** Regex rules come from downloaded lists; a pathological one gives up instead of hanging. */
        private fun safeFind(re: Regex, u: String): Boolean = try {
            re.containsMatchIn(Budgeted(u, intArrayOf(200_000)))
        } catch (e: BudgetExceeded) {
            false
        }
    }
}

private class BudgetExceeded : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

private class Budgeted(private val s: CharSequence, private val left: IntArray) : CharSequence {
    override val length: Int get() = s.length
    override fun get(index: Int): Char {
        if (--left[0] < 0) throw BudgetExceeded()
        return s[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = Budgeted(s.subSequence(startIndex, endIndex), left)
    override fun toString(): String = s.toString()
}

/** Network rules indexed by host or by a token their URLs must contain. */
private class NetIndex {
    private val byHost = HashMap<String, ArrayList<NetFilter>>()
    private val byToken = HashMap<String, ArrayList<NetFilter>>()
    private val rest = ArrayList<NetFilter>()

    fun add(f: NetFilter) {
        val h = f.pureHost()
        when {
            h != null -> byHost.getOrPut(h) { ArrayList(1) }.add(f)
            else -> f.token()?.let { byToken.getOrPut(it) { ArrayList(2) }.add(f) } ?: rest.add(f)
        }
    }

    fun find(r: Request, lists: Int, generic: Boolean): NetFilter? {
        var found: NetFilter? = null
        forEachCandidate(r) { f, hostOnly ->
            if ((generic || f.domains?.hasIncludes == true) && f.optionsMatch(r, lists) && (hostOnly || f.urlMatches(r))) {
                found = f
                true
            } else false
        }
        return found
    }

    fun forEachMatch(r: Request, lists: Int, fn: (NetFilter) -> Unit) {
        forEachCandidate(r) { f, hostOnly ->
            if (f.optionsMatch(r, lists) && (hostOnly || f.urlMatches(r))) fn(f)
            false
        }
    }

    /** Calls [fn] with each rule that could match; stops when it returns true. */
    private inline fun forEachCandidate(r: Request, fn: (NetFilter, Boolean) -> Boolean) {
        if (byHost.isNotEmpty()) {
            var h = r.host
            while (true) {
                byHost[h]?.let { list -> for (f in list) if (fn(f, true)) return }
                val dot = h.indexOf('.')
                if (dot < 0) break
                h = h.substring(dot + 1)
            }
        }
        if (byToken.isNotEmpty()) {
            val u = r.lower
            var i = 0
            while (i < u.length) {
                if (!NetFilter.isTokenChar(u[i])) { i++; continue }
                var j = i
                while (j < u.length && NetFilter.isTokenChar(u[j])) j++
                if (j - i >= 2) byToken[u.substring(i, j)]?.let { list -> for (f in list) if (fn(f, false)) return }
                i = j
            }
        }
        for (f in rest) if (fn(f, false)) return
    }
}

/**
 * Plain `||host^` rules (well over half of every list) as sorted 64-bit hashes: a few hundred
 * kilobytes instead of tens of megabytes of objects.
 */
private class HostSet {
    private val building = HashMap<Long, Int>()
    private var keys = LongArray(0)
    private var bits = IntArray(0)

    fun add(host: String, list: Int, party: Int) {
        val h = hash(host, 0)
        // Low byte: lists that block from any page; next byte: only as third-party; then first-party.
        val bit = when (party) {
            FilterEngine.THIRD -> list shl 8
            FilterEngine.FIRST -> list shl 16
            else -> list
        }
        building[h] = (building[h] ?: 0) or bit
    }

    fun finish() {
        if (building.isEmpty()) return
        val all = HashMap<Long, Int>(keys.size + building.size)
        for (i in keys.indices) all[keys[i]] = bits[i]
        for ((k, v) in building) all[k] = (all[k] ?: 0) or v
        building.clear()
        val sorted = all.keys.toLongArray().also { it.sort() }
        keys = sorted
        bits = IntArray(sorted.size) { all[sorted[it]]!! }
    }

    /** The matching host suffix, or null. */
    fun find(r: Request, lists: Int): String? {
        if (keys.isEmpty()) return null
        val host = r.host
        var i = 0
        while (true) {
            val idx = keys.binarySearch(hash(host, i))
            if (idx >= 0) {
                val b = bits[idx]
                val any = b and 0xFF
                val third = (b shr 8) and 0xFF
                val first = (b shr 16) and 0xFF
                if (any and lists != 0 || (r.thirdParty && third and lists != 0) || (!r.thirdParty && first and lists != 0)) {
                    return host.substring(i)
                }
            }
            val dot = host.indexOf('.', i)
            if (dot < 0) return null
            i = dot + 1
        }
    }

    companion object {
        /** FNV-1a, 64-bit, over host[from..]. */
        fun hash(s: String, from: Int): Long {
            var h = -0x340d631b7bdddcdbL
            for (k in from until s.length) {
                h = h xor s[k].code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
    }
}

/** A `domain=` list (or the domains before `##`): includes, and `~` excludes. */
class DomainList private constructor(private val include: Array<String>?, private val exclude: Array<String>?) {
    val hasIncludes: Boolean get() = include != null

    fun matches(host: String?): Boolean {
        if (host == null) return include == null
        var bestIn = -1
        var bestEx = -1
        include?.forEach { if (it.length > bestIn && Domains.matches(host, it)) bestIn = it.length }
        if (include != null && bestIn < 0) return false
        exclude?.forEach { if (it.length > bestEx && Domains.matches(host, it)) bestEx = it.length }
        return bestEx < 0 || bestIn > bestEx
    }

    companion object {
        fun parse(value: String, sep: Char): DomainList? {
            val inc = ArrayList<String>()
            val exc = ArrayList<String>()
            for (part in value.split(sep)) {
                val d = part.trim().lowercase()
                if (d.isEmpty()) continue
                if (d.startsWith("~")) exc += d.substring(1) else inc += d
            }
            if (inc.isEmpty() && exc.isEmpty()) return null
            if ((inc + exc).any { !validDomain(it) }) return null
            return DomainList(inc.takeIf { it.isNotEmpty() }?.toTypedArray(), exc.takeIf { it.isNotEmpty() }?.toTypedArray())
        }

        internal fun validDomain(d: String) = d.isNotEmpty() && d.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '*' || it == '_' }
    }
}

object Domains {
    private val SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "ac", "edu", "ne", "or", "gob")

    /** "www.m.example.co.uk" -> "example.co.uk" (a good-enough eTLD+1 heuristic). */
    fun site(host: String): String {
        if (host.contains(':') || host.all { it.isDigit() || it == '.' }) return host
        val parts = host.lowercase().removePrefix("www.").split('.')
        if (parts.size <= 2) return parts.joinToString(".")
        val sld = parts[parts.size - 2]
        val twoLevel = sld.length <= 3 && sld in SECOND_LEVEL
        return parts.takeLast(if (twoLevel) 3 else 2).joinToString(".")
    }

    /** True if [host] is [domain] or one of its subdomains. `name.*` matches name under any suffix. */
    fun matches(host: String, domain: String): Boolean {
        if (domain.endsWith(".*")) {
            val suffix = site(host).substringAfter('.', "")
            if (suffix.isEmpty()) return false
            return matches(host, domain.substring(0, domain.length - 1) + suffix)
        }
        return host == domain || (host.length > domain.length && host.endsWith(domain) && host[host.length - domain.length - 1] == '.')
    }

    /** [host] and each parent domain, then each `name.*` form of them. */
    fun lookupKeys(host: String): List<String> {
        val keys = ArrayList<String>(8)
        var h = host
        while (true) {
            keys += h
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        val suffix = site(host).substringAfter('.', "")
        if (suffix.isNotEmpty() && host.endsWith(".$suffix")) {
            var stem = host.substring(0, host.length - suffix.length - 1)
            while (true) {
                keys += "$stem.*"
                val dot = stem.indexOf('.')
                if (dot < 0) break
                stem = stem.substring(dot + 1)
            }
        }
        return keys
    }
}

/** Element-hiding rules. */
private class Cosmetics {
    private class Rule(val selector: String, val list: Int, val domains: DomainList?)

    /** Generic `.name` / `#name` rules, as hashes: the bulk of every list. */
    private val simple = HashMap<String, Int>()
    /** Other generic rules starting with a class or id, keyed by it (".name" / "#name"). */
    private val keyed = HashMap<String, ArrayList<Rule>>()
    /** Generic rules that can't be looked up by name; always applied. */
    private val other = ArrayList<Rule>()
    private val specific = HashMap<String, ArrayList<Rule>>()
    private val specificExceptions = HashMap<String, HashSet<String>>()
    private val genericExceptions = HashSet<String>()

    fun add(line: String, at: Int, sep: String, list: Int): Boolean {
        val exception = sep == "#@#" || sep == "#@?#"
        // Snippets, scriptlets and CSS-injection rules run code or restyle pages: never supported.
        if (sep != "##" && sep != "#@#" && sep != "#?#" && sep != "#@?#") return false
        var selector = line.substring(at + sep.length).trim()
        if (selector.startsWith("+js(") || selector.startsWith("^")) return false
        selector = selector.replace(":-abp-has(", ":has(")
        if (PROCEDURAL.any { selector.contains(it) }) return false
        if (!safeSelector(selector)) return false
        val domainPart = line.substring(0, at)
        val domains = if (domainPart.isEmpty()) null else DomainList.parse(domainPart, ',') ?: return false

        if (exception) {
            if (domains == null) genericExceptions += selector
            else domainPart.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("~") }
                .forEach { specificExceptions.getOrPut(it) { HashSet() } += selector }
            return true
        }
        if (domains != null && domains.hasIncludes) {
            val rule = Rule(selector, list, domains)
            domainPart.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("~") }
                .forEach { specific.getOrPut(it) { ArrayList(2) } += rule }
            return true
        }
        val key = nameKey(selector)
        when {
            key != null && key == selector && domains == null -> simple[key] = (simple[key] ?: 0) or list
            key != null -> keyed.getOrPut(key) { ArrayList(1) } += Rule(selector, list, domains)
            else -> other += Rule(selector, list, domains)
        }
        return true
    }

    private fun exceptionsFor(keys: List<String>): Set<String> {
        val out = HashSet<String>(genericExceptions)
        for (k in keys) specificExceptions[k]?.let { out += it }
        return out
    }

    fun pageCss(host: String, lists: Int, generic: Boolean): String {
        val keys = Domains.lookupKeys(host)
        val except = exceptionsFor(keys)
        val out = StringBuilder()
        val seen = HashSet<String>()
        fun emit(r: Rule) {
            if (r.list and lists == 0 || r.selector in except || !seen.add(r.selector)) return
            if (r.domains != null && !r.domains.matches(host)) return
            out.append(r.selector).append(RULE_BODY)
        }
        for (k in keys) specific[k]?.forEach(::emit)
        if (generic) other.forEach(::emit)
        return out.toString()
    }

    fun namesCss(host: String, classes: Collection<String>, ids: Collection<String>, lists: Int): String {
        val except = exceptionsFor(Domains.lookupKeys(host))
        val out = StringBuilder()
        fun look(key: String) {
            val bits = simple[key]
            if (bits != null && bits and lists != 0 && key !in except) out.append(key).append(RULE_BODY)
            keyed[key]?.forEach { r ->
                if (r.list and lists != 0 && r.selector !in except && (r.domains == null || r.domains.matches(host))) {
                    out.append(r.selector).append(RULE_BODY)
                }
            }
        }
        for (c in classes) if (validName(c)) look(".$c")
        for (i in ids) if (validName(i)) look("#$i")
        return out.toString()
    }

    companion object {
        const val RULE_BODY = "{display:none!important}\n"

        private val PROCEDURAL = listOf(
            ":-abp-", ":has-text(", ":contains(", ":xpath(", ":matches-css", ":upward(", ":remove(", ":style(",
            ":min-text-length(", ":watch-attr(", ":others(", ":matches-path(", ":matches-attr(", ":matches-prop(",
            ":if(", ":if-not(", ":nth-ancestor(", ":remove-attr(", ":remove-class(", ":matches-media(", ":shadow-dom",
        )

        private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-'

        /** A class or id that can be written in a selector without escaping. */
        fun validName(n: String) = n.isNotEmpty() && n.length <= 200 && !n[0].isDigit() &&
            !(n[0] == '-' && n.length > 1 && (n[1].isDigit() || n[1] == '-')) && n.all(::isNameChar)

        /**
         * ".name" or "#name" if [selector] starts with a plain class or id, maybe after a tag
         * name ("div.name"), and isn't a list: then it can only match where that name is used.
         */
        fun nameKey(selector: String): String? {
            if (selector.contains(',')) return null
            var start = 0
            if (selector.isNotEmpty() && selector[0].isLetter()) {
                while (start < selector.length && (selector[start].isLetterOrDigit() || selector[start] == '-')) start++
            }
            if (start + 1 >= selector.length || (selector[start] != '.' && selector[start] != '#')) return null
            var i = start + 1
            while (i < selector.length && isNameChar(selector[i])) i++
            if (i < selector.length && selector[i] == '\\') return null
            val key = selector.substring(start, i)
            return if (validName(key.substring(1))) key else null
        }

        /**
         * Rules come from downloaded lists and end up in a page's stylesheet, so a selector may
         * never close the rule it sits in (no braces or ';' outside strings, no comments,
         * balanced quotes and brackets). Anything else is at worst a selector that matches nothing.
         */
        fun safeSelector(s: String): Boolean {
            if (s.isEmpty() || s.length > 1500 || s[0] == '@') return false
            var paren = 0
            var bracket = 0
            var quote = 0.toChar()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c < ' ' || c == '\u007f') return false
                if (c == '\\') {
                    if (i + 1 >= s.length || s[i + 1] < ' ') return false
                    i += 2
                    continue
                }
                if (quote != 0.toChar()) {
                    if (c == quote) quote = 0.toChar()
                    i++
                    continue
                }
                when (c) {
                    '"', '\'' -> quote = c
                    '{', '}', ';', '<' -> return false
                    '(' -> paren++
                    ')' -> if (--paren < 0) return false
                    '[' -> bracket++
                    ']' -> if (--bracket < 0) return false
                    '/' -> if (i + 1 < s.length && s[i + 1] == '*') return false
                }
                i++
            }
            return quote == 0.toChar() && paren == 0 && bracket == 0
        }
    }
}
