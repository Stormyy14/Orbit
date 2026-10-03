package app.orbit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FiltersTest {
    private val ALL = 7

    private fun engine(vararg lines: String) = FilterEngine().apply {
        lines.forEach { addLine(it, 1) }
        finish()
    }

    private fun blocks(e: FilterEngine, url: String, page: String? = "news.example", types: Int = RType.SCRIPT, method: String = "GET") =
        e.match(Request(url, page, types, method), ALL) != null

    @Test fun hostAnchor() {
        val e = engine("||ads.example.com^")
        assertTrue(blocks(e, "https://ads.example.com/x.js"))
        assertTrue(blocks(e, "https://cdn.ads.example.com/x.js"))
        assertTrue(blocks(e, "https://ads.example.com"))
        assertTrue(blocks(e, "https://ads.example.com:8443/x"))
        assertFalse(blocks(e, "https://badads.example.com/x.js"))
        assertFalse(blocks(e, "https://ads.example.com.evil.net/x.js"))
        assertFalse(blocks(e, "https://example.com/?u=https://ads.example.com/"))
    }

    @Test fun hostAnchorWithPath() {
        val e = engine("||example.com/ads/*.gif|")
        assertTrue(blocks(e, "https://www.example.com/ads/banner.gif", types = RType.IMAGE))
        assertFalse(blocks(e, "https://www.example.com/ads/banner.gif?x=1", types = RType.IMAGE))
        assertFalse(blocks(e, "https://www.notexample.com/ads/banner.gif", types = RType.IMAGE))
    }

    @Test fun separatorsAndWildcards() {
        val e = engine("/banner/*/img^", "&ad_type=")
        assertTrue(blocks(e, "http://example.com/banner/foo/img"))
        assertTrue(blocks(e, "http://example.com/banner/foo/bar/img?param"))
        assertTrue(blocks(e, "http://example.com/banner//img/foo"))
        assertFalse(blocks(e, "http://example.com/banner/img"))
        assertFalse(blocks(e, "http://example.com/banner/foo/imgraph"))
        assertFalse(blocks(e, "http://example.com/banner/foo/img.gif"))
        assertTrue(blocks(e, "https://x.com/p?a=1&ad_type=2"))
    }

    @Test fun startAndEndAnchors() {
        val e = engine("|https://track.", "swf|")
        assertTrue(blocks(e, "https://track.example.com/a"))
        assertFalse(blocks(e, "https://example.com/?r=https://track.x"))
        assertTrue(blocks(e, "http://example.com/annoyingflash.swf"))
        assertFalse(blocks(e, "http://example.com/swf/index.html"))
        assertTrue(blocks(e, "http://example.com/swf/a.swf"))
    }

    @Test fun thirdPartyAndTypes() {
        val e = engine("||tracker.net^\$third-party", "/pixel.\$image", "||cdn.net^\$~script")
        assertTrue(blocks(e, "https://tracker.net/t.js", page = "news.example"))
        assertFalse(blocks(e, "https://tracker.net/t.js", page = "www.tracker.net"))
        assertTrue(blocks(e, "https://x.com/pixel.gif", types = RType.IMAGE))
        assertFalse(blocks(e, "https://x.com/pixel.gif", types = RType.SCRIPT))
        assertTrue(blocks(e, "https://cdn.net/a.png", types = RType.IMAGE))
        assertFalse(blocks(e, "https://cdn.net/a.js", types = RType.SCRIPT))
        // When the type is uncertain, any of the possible types is enough.
        assertTrue(blocks(e, "https://x.com/pixel.gif", types = RType.IMAGE or RType.SCRIPT))
    }

    @Test fun broadTypedRulesNeedACertainType() {
        val e = engine("*\$ping,third-party")
        assertTrue(blocks(e, "https://tracker.net/beacon", types = RType.PING))
        assertFalse(blocks(e, "https://video.cdn.net/stream?id=1", types = RType.XHR or RType.PING or RType.OTHER))
        assertFalse(blocks(e, "https://tracker.net/beacon", page = "tracker.net", types = RType.PING))
    }

    @Test fun domainOption() {
        val e = engine("/adframe.\$domain=example.com|~shop.example.com", "||widget.io^\$domain=~friend.org")
        assertTrue(blocks(e, "https://cdn.x/adframe.js", page = "www.example.com"))
        assertFalse(blocks(e, "https://cdn.x/adframe.js", page = "shop.example.com"))
        assertFalse(blocks(e, "https://cdn.x/adframe.js", page = "other.org"))
        assertTrue(blocks(e, "https://widget.io/w.js", page = "news.example"))
        assertFalse(blocks(e, "https://widget.io/w.js", page = "a.friend.org"))
        val g = engine("/promo.\$domain=google.*")
        assertTrue(blocks(g, "https://x/promo.js", page = "www.google.co.uk"))
        assertTrue(blocks(g, "https://x/promo.js", page = "google.de"))
        assertFalse(blocks(g, "https://x/promo.js", page = "notgoogle.de"))
    }

    @Test fun exceptionsAndImportant() {
        val e = engine("||ads.net^", "@@||ads.net/allowed^", "||bad.net^\$important", "@@||bad.net^")
        assertTrue(blocks(e, "https://ads.net/a.js"))
        assertFalse(blocks(e, "https://ads.net/allowed/a.js"))
        assertTrue(blocks(e, "https://bad.net/a.js"))
    }

    @Test fun pageExceptions() {
        val e = engine("||ads.net^", "##.ad", "@@||trusted.org^\$document", "@@||quiet.org^\$generichide", "@@||nobanner.org^\$elemhide")
        assertEquals(RType.DOCUMENT, e.pageFlags("https://www.trusted.org/page", ALL) and RType.DOCUMENT)
        val flags = e.pageFlags("https://www.trusted.org/", ALL)
        assertFalse(e.match(Request("https://ads.net/a.js", "www.trusted.org", RType.SCRIPT), ALL, flags) != null)
        assertEquals(RType.GENERICHIDE, e.pageFlags("https://quiet.org/", ALL))
        assertEquals(RType.ELEMHIDE, e.pageFlags("https://nobanner.org/", ALL))
        assertEquals(0, e.pageFlags("https://other.org/", ALL))
    }

    @Test fun genericBlock() {
        val e = engine("||ads.net^", "/generic-ad.", "/site-ad.\$domain=site.org", "@@||site.org^\$genericblock")
        val flags = e.pageFlags("https://site.org/", ALL)
        assertEquals(RType.GENERICBLOCK, flags)
        assertNull(e.match(Request("https://ads.net/a.js", "site.org", RType.SCRIPT), ALL, flags))
        assertNull(e.match(Request("https://x.org/generic-ad.js", "site.org", RType.SCRIPT), ALL, flags))
        assertNotNull(e.match(Request("https://x.org/site-ad.js", "site.org", RType.SCRIPT), ALL, flags))
    }

    @Test fun methodsAndMatchCase() {
        val e = engine("/collect\$method=post", "/AdBanner\$match-case")
        assertTrue(blocks(e, "https://x.com/collect", method = "POST"))
        assertFalse(blocks(e, "https://x.com/collect", method = "GET"))
        assertTrue(blocks(e, "https://x.com/AdBanner.js"))
        assertFalse(blocks(e, "https://x.com/adbanner.js"))
    }

    @Test fun regexRules() {
        val e = engine("/^https:\\/\\/[a-z]{8}\\.com\\/[a-z]{6}\\.js\$/\$script,third-party")
        assertTrue(blocks(e, "https://abcdefgh.com/abcdef.js"))
        assertFalse(blocks(e, "https://abcdefg.com/abcdef.js"))
        // A catastrophic pattern gives up instead of hanging.
        val slow = engine("/(a+)+\$/")
        val t = System.nanoTime()
        blocks(slow, "https://x.com/" + "a".repeat(40) + "!")
        assertTrue((System.nanoTime() - t) < 2_000_000_000L)
    }

    @Test fun unsupportedRulesAreSkipped() {
        val e = engine(
            "||a.com^\$csp=script-src 'none'", "||b.com^\$redirect=noopjs", "||c.com^\$removeparam=x", "||d.com^\$popup",
            "||e.com^\$document", "||f.com^\$unknownoption",
        )
        listOf("a", "b", "c", "d", "e", "f").forEach { assertFalse(it, blocks(e, "https://$it.com/x.js")) }
        assertEquals(0, e.networkRules)
    }

    @Test fun listsCanBeSwitchedOff() {
        val e = FilterEngine().apply {
            addLine("||ads.net^", 1)
            addLine("||track.net^", 2)
            addLine("/ad-path/", 1)
            finish()
        }
        assertNotNull(e.match(Request("https://ads.net/", "x.org", RType.SCRIPT), 1))
        assertNull(e.match(Request("https://ads.net/", "x.org", RType.SCRIPT), 2))
        assertNotNull(e.match(Request("https://track.net/", "x.org", RType.SCRIPT), 2))
        assertNull(e.match(Request("https://x.org/ad-path/", "x.org", RType.SCRIPT), 2))
    }

    @Test fun cosmetics() {
        val e = engine(
            "##.ad-banner", "###sponsor", "##div.cookie-bar", "##.promo > .item", "##div[id^=\"ad-\"]", "example.com##.only-here",
            "~skip.com##.not-there", "example.com#@#.ad-banner", "example.com#?#.card:-abp-has(.sponsored)",
            "example.com#?#.card:-abp-contains(Ad)", "shop.*##.shop-ad", "##.bad{color:red}", "##a[href=\"x\"] }",
            "example.com#\$#abort-on-property-read x", "##+js(nowebrtc)",
        )
        val page = e.pageCss("www.example.com", ALL, generic = true)
        assertTrue(page.contains(".only-here{"))
        assertTrue(page.contains("div[id^=\"ad-\"]{"))
        assertTrue(page.contains(".card:has(.sponsored){"))
        assertFalse(page.contains("contains"))
        assertFalse(page.contains("color:red"))
        assertFalse(page.contains("abort"))
        assertFalse(e.pageCss("other.org", ALL, generic = true).contains(".only-here"))
        assertFalse(e.pageCss("www.example.com", ALL, generic = false).contains("div[id^"))
        assertTrue(e.pageCss("shop.de", ALL, generic = false).contains(".shop-ad{"))

        val names = e.namesCss("other.org", listOf("ad-banner", "promo", "x"), listOf("sponsor"), ALL)
        assertTrue(names.contains(".ad-banner{"))
        assertTrue(names.contains("#sponsor{"))
        assertTrue(names.contains(".promo > .item{"))
        assertTrue(e.namesCss("other.org", listOf("cookie-bar"), emptyList(), ALL).contains("div.cookie-bar{"))
        assertFalse(e.namesCss("example.com", listOf("ad-banner"), emptyList(), ALL).contains(".ad-banner"))
        assertTrue(e.namesCss("other.org", listOf("not-there"), emptyList(), ALL).contains(".not-there"))
        assertFalse(e.namesCss("a.skip.com", listOf("not-there"), emptyList(), ALL).contains(".not-there"))
        // Names that would need escaping are never turned into selectors.
        assertEquals("", e.namesCss("x.org", listOf("a{b", "1ad", "x y"), listOf("}"), ALL))
    }

    @Test fun selectorSafety() {
        listOf("a{", "a}", "a;b", "/* x", "@import", "a[x=\"y]", "a(", "a)", "a\u0001b", "\\").forEach {
            assertFalse(it, FilterEngine().apply { addLine("##$it", 1) }.cosmeticRules > 0)
        }
        listOf(".a", "a[title=\"x;y{}\"]", ".a\\:b", "div:has(> .x)", "#ad").forEach {
            assertTrue(it, FilterEngine().apply { addLine("##$it", 1) }.cosmeticRules > 0)
        }
    }

    /**
     * Checks the real lists, if FILTERS_DIR points at a folder with easylist.txt, easyprivacy.txt
     * and cookie.txt (downloaded from easylist.to); skipped otherwise.
     */
    @Test fun realLists() {
        val dir = File(System.getenv("FILTERS_DIR") ?: return)
        val files = listOf("easylist.txt" to 1, "easyprivacy.txt" to 2, "cookie.txt" to 4).map { File(dir, it.first) to it.second }
        if (files.any { !it.first.exists() }) return
        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val e = FilterEngine()
        files.forEach { (f, bit) -> e.addList(f.readText(), bit) }
        e.finish()
        val parseMs = (System.nanoTime() - t0) / 1_000_000
        System.gc()
        val used = (rt.totalMemory() - rt.freeMemory() - before) / (1 shl 20)
        println("real lists: ${e.networkRules} network, ${e.cosmeticRules} cosmetic rules, parsed in $parseMs ms, ~$used MB")

        fun b(url: String, page: String, t: Int = RType.SCRIPT) = e.match(Request(url, page, t), ALL).also { println("$url -> $it") }
        assertNotNull("block", b("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "www.cnn.com"))
        assertNotNull("block", b("https://www.googletagmanager.com/gtag/js?id=G-XXXX", "www.cnn.com"))
        assertNotNull("block", b("https://www.google-analytics.com/analytics.js", "news.example"))
        assertNotNull("block", b("https://connect.facebook.net/en_US/fbevents.js", "shop.example"))
        assertNotNull("block", b("https://static.criteo.net/js/ld/publishertag.js", "news.example"))
        assertNull("allow", b("https://www.wikipedia.org/static/images/logo.png", "www.wikipedia.org", RType.IMAGE))
        assertNull("allow", b("https://code.jquery.com/jquery-3.7.1.min.js", "example.org"))
        assertNull("allow", b("https://fonts.googleapis.com/css2?family=Inter", "example.org", RType.STYLESHEET))
        assertNull("allow", b("https://github.githubassets.com/assets/app.js", "github.com"))

        val urls = listOf(
            "https://www.example.com/assets/app.3f2a1b.js", "https://cdn.example.net/images/photo-123.jpg",
            "https://securepubads.g.doubleclick.net/gampad/ads?iu=/123/x&sz=300x250", "https://api.example.com/v1/feed?page=2",
            "https://www.youtube.com/s/player/abc/player_ias.vflset/en_US/base.js", "https://i.ytimg.com/vi/xyz/hqdefault.jpg",
        ).map { Request(it, "www.example.com", RType.SCRIPT or RType.XHR or RType.OTHER) }
        val t1 = System.nanoTime()
        var n = 0
        repeat(2000) { for (r in urls) { e.match(r, ALL); n++ } }
        val us = (System.nanoTime() - t1) / 1000.0 / n
        println("match: %.2f µs per request".format(us))
        assertTrue(us < 200)

        // Optional: report which rule (if any) blocks each URL in urls.txt, as any request type.
        File(dir, "urls.txt").takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.forEach { u ->
            println("debug: ${e.match(Request(u, "m.youtube.com", RType.SCRIPT or RType.XHR or RType.OTHER), ALL)} <- ${u.take(90)}")
        }

        val css = e.pageCss("www.example.com", ALL, generic = true)
        println("generic page css: ${css.length / 1024} KB, ${css.count { it == '\n' }} rules")
        assertTrue(e.namesCss("x.org", listOf("adsbygoogle-wrapper"), emptyList(), ALL).contains(".adsbygoogle-wrapper"))
        assertTrue(e.namesCss("x.org", listOf("adsbygoogle"), emptyList(), ALL).contains("ins.adsbygoogle[data-ad-slot]"))
    }
}
