package app.orbit.core

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * On-device ad and tracker blocking. Network requests and page elements are matched against
 * EasyList (ads), EasyPrivacy (trackers) and the EasyList Cookie List (consent banners), which
 * are downloaded straight from their publishers and kept up to date, plus a small built-in list
 * that works before the first download. Matching happens on the device: no browsing data is sent
 * anywhere. See [FilterEngine] for the rule syntax.
 */
object Shields {
    /** Filter list bits; a site's rules apply when its list is switched on. */
    const val ADS = 1
    const val TRACKERS = 2
    const val COOKIES = 4

    class Source(val id: String, val name: String, val what: String, val bit: Int, val urls: List<String>)

    val sources = listOf(
        Source(
            "easylist", "EasyList", "Ads", ADS,
            listOf("https://easylist.to/easylist/easylist.txt", "https://easylist-downloads.adblockplus.org/easylist.txt"),
        ),
        Source(
            "easyprivacy", "EasyPrivacy", "Trackers", TRACKERS,
            listOf("https://easylist.to/easylist/easyprivacy.txt", "https://easylist-downloads.adblockplus.org/easyprivacy.txt"),
        ),
        Source(
            "cookies", "EasyList Cookie List", "Cookie banners", COOKIES,
            listOf("https://secure.fanboy.co.nz/fanboy-cookiemonster.txt", "https://easylist-downloads.adblockplus.org/fanboy-cookiemonster.txt"),
        ),
    )

    private const val UPDATE_EVERY_MS = 4 * 24 * 3_600_000L
    private const val RETRY_AFTER_MS = 3_600_000L
    private const val MAX_LIST = 16L shl 20

    @Volatile private var engine: FilterEngine = FilterEngine().apply { addBuiltin(this); finish() }

    /** Rules in use (network + element hiding). */
    var rules by mutableIntStateOf(engine.networkRules)
        private set
    /** When the lists were last checked, 0 if they never were. */
    var checkedAt by mutableLongStateOf(0L)
        private set
    var updating by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private lateinit var app: Context
    private lateinit var dir: File
    private val prefs get() = app.getSharedPreferences("device", Context.MODE_PRIVATE)

    fun init(context: Context, scope: CoroutineScope) {
        if (::app.isInitialized) return
        app = context.applicationContext
        dir = File(app.filesDir, "shields").apply { mkdirs() }
        checkedAt = prefs.getLong("lists.checked", 0L)
        scope.launch {
            if (sources.any { file(it).exists() }) rebuild()
            updateIfDue(scope)
        }
    }

    private fun file(s: Source) = File(dir, "${s.id}.txt")

    /** Lists the user has switched on, as bits for [FilterEngine]. */
    fun lists(s: Settings): Int =
        (if (s.blockAds) ADS else 0) or (if (s.blockTrackers) TRACKERS else 0) or (if (s.hideCookieBanners) COOKIES else 0)

    // =====================================================================================
    // Network
    // =====================================================================================

    /** An empty response if [req] (made by a page on [pageHost]) should be blocked, else null. */
    fun intercept(req: WebResourceRequest, pageHost: String?, lists: Int, pageFlags: Int): WebResourceResponse? {
        val url = req.url
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (lists == 0) return null
        val type = typeOf(req)
        val r = Request(url.toString(), pageHost, type, req.method ?: "GET")
        engine.match(r, lists, pageFlags) ?: return null
        return blocked(type)
    }

    /** Page-wide exceptions for a page at [url] (see [FilterEngine.pageFlags]). */
    fun pageFlags(url: String, lists: Int): Int =
        if (lists == 0 || !(url.startsWith("https://") || url.startsWith("http://"))) 0 else engine.pageFlags(url, lists)

    /**
     * What a request is for. WebView doesn't say, so it's read from the Accept header and the
     * file extension; when it's still unclear, every likely type is set and any of them matches.
     */
    private fun typeOf(req: WebResourceRequest): Int {
        val h = req.requestHeaders
        val accept = (h["Accept"] ?: h["accept"] ?: "").lowercase()
        when {
            accept.startsWith("text/html") || accept.contains("application/xhtml") -> return RType.SUBDOCUMENT
            accept.startsWith("text/css") -> return RType.STYLESHEET
            accept.startsWith("image/") -> return RType.IMAGE
            accept.startsWith("video/") || accept.startsWith("audio/") -> return RType.MEDIA
            accept.startsWith("application/json") -> return RType.XHR
        }
        if (h.containsKey("Range") || h.containsKey("range")) return RType.MEDIA
        // Hyperlink-auditing pings say so; nothing else is treated as a ping.
        val contentType = (h["Content-Type"] ?: h["content-type"] ?: "").lowercase()
        if (contentType.startsWith("text/ping") || h.keys.any { it.equals("Ping-From", true) || it.equals("Ping-To", true) }) return RType.PING
        val path = req.url.path?.lowercase().orEmpty()
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        return when (ext) {
            "js", "mjs" -> RType.SCRIPT
            "css" -> RType.STYLESHEET
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp" -> RType.IMAGE
            "woff", "woff2", "ttf", "otf", "eot" -> RType.FONT
            "mp4", "webm", "m3u8", "mpd", "mp3", "m4a", "ogg", "aac", "m4s" -> RType.MEDIA
            "html", "htm" -> RType.SUBDOCUMENT or RType.XHR
            else -> RType.SCRIPT or RType.XHR or RType.OTHER
        }
    }

    private val GIF = byteArrayOf(
        71, 73, 70, 56, 57, 97, 1, 0, 1, 0, -128, 0, 0, 0, 0, 0, 0, 0, 0, 33, -7, 4, 1, 0, 0, 0, 0, 44, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 68, 1, 0, 59,
    )

    /** A harmless stand-in: a transparent pixel for images, empty content otherwise. */
    private fun blocked(type: Int): WebResourceResponse {
        val (mime, body) = when (type) {
            RType.IMAGE -> "image/gif" to GIF
            RType.SCRIPT -> "text/javascript" to ByteArray(0)
            RType.STYLESHEET -> "text/css" to ByteArray(0)
            RType.SUBDOCUMENT -> "text/html" to ByteArray(0)
            else -> "text/plain" to ByteArray(0)
        }
        return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(body))
    }

    // =====================================================================================
    // Element hiding
    // =====================================================================================

    /** CSS for a frame on [host]: site-specific rules and generic rules not tied to a class or id. */
    fun pageCss(host: String, lists: Int, flags: Int): String {
        if (flags and (RType.DOCUMENT or RType.ELEMHIDE) != 0) return ""
        val generic = flags and RType.GENERICHIDE == 0
        val css = engine.pageCss(host, lists, generic)
        return if (lists and COOKIES != 0 && generic) css + cookieCss else css
    }

    /** CSS for generic rules about the classes and ids a page reported. */
    fun namesCss(host: String, classes: Collection<String>, ids: Collection<String>, lists: Int): String =
        engine.namesCss(host, classes, ids, lists)

    // =====================================================================================
    // Tracking parameters
    // =====================================================================================

    private val TRACKING_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id", "utm_name", "utm_cid",
        "utm_reader", "utm_referrer", "utm_social", "utm_social-type", "utm_brand", "utm_place",
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid", "ttclid", "igshid",
        "li_fat_id", "mc_cid", "mc_eid", "_hsenc", "_hsmi", "__hssc", "__hstc", "__hsfp", "hsCtaTracking", "mkt_tok",
        "oly_anon_id", "oly_enc_id", "vero_id", "vero_conv", "wickedid", "_openstat", "rb_clickid", "s_cid",
        "ml_subscriber", "ml_subscriber_hash", "srsltid", "_branch_match_id", "_kx", "ss_email_id", "bsft_clkid",
        "bsft_uid", "epik", "sc_cid", "si_cid", "irclickid",
    )

    /** [url] without known tracking parameters, or null if it has none. */
    fun cleanUrl(url: String): String? {
        if (!(url.startsWith("https://") || url.startsWith("http://"))) return null
        val q = url.indexOf('?')
        if (q < 0) return null
        val hash = url.indexOf('#', q).let { if (it < 0) url.length else it }
        val params = url.substring(q + 1, hash).split('&')
        val kept = params.filter { p -> p.isNotEmpty() && p.substringBefore('=') !in TRACKING_PARAMS }
        if (kept.size == params.count { it.isNotEmpty() }) return null
        val base = url.substring(0, q)
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + url.substring(hash)
    }

    // =====================================================================================
    // Filter lists
    // =====================================================================================

    fun updateIfDue(scope: CoroutineScope) {
        if (!::app.isInitialized || updating) return
        val now = System.currentTimeMillis()
        val missing = sources.any { !file(it).exists() }
        val failedAt = prefs.getLong("lists.failed", 0L)
        if (failedAt > checkedAt && now - failedAt < RETRY_AFTER_MS) return
        if (missing || now - checkedAt > UPDATE_EVERY_MS) update(scope)
    }

    /** Downloads newer versions of the lists (only what changed), then reloads the rules. */
    fun update(scope: CoroutineScope, onDone: ((Boolean) -> Unit)? = null) {
        if (!::app.isInitialized || updating) return
        updating = true
        error = null
        scope.launch {
            var changed = false
            var failed = 0
            try {
                withContext(Dispatchers.IO) {
                    for (s in sources) {
                        when (download(s)) {
                            true -> changed = true
                            false -> Unit
                            null -> failed++
                        }
                    }
                }
                val now = System.currentTimeMillis()
                if (failed == 0) {
                    checkedAt = now
                    prefs.edit().putLong("lists.checked", now).apply()
                } else {
                    prefs.edit().putLong("lists.failed", now).apply()
                    error = if (Vpn.enabled && Vpn.socksPort == 0) "Waiting for Orbit VPN" else "Couldn't reach the list servers"
                }
                if (changed) rebuild()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't update the filter lists"
            } finally {
                updating = false
                onDone?.invoke(failed == 0)
            }
        }
    }

    /** true = new version saved, false = unchanged, null = failed. */
    private fun download(s: Source): Boolean? {
        val target = file(s)
        for (url in s.urls) {
            try {
                val conn = Net.open(url, connectMs = 15_000, readMs = 30_000)
                conn.setRequestProperty("User-Agent", "Orbit (Android)")
                val tag = "lists.${s.id}"
                if (target.exists() && prefs.getString("$tag.url", null) == url) {
                    prefs.getString("$tag.etag", null)?.let { conn.setRequestProperty("If-None-Match", it) }
                    prefs.getString("$tag.modified", null)?.let { conn.setRequestProperty("If-Modified-Since", it) }
                }
                val code = conn.responseCode
                if (code == 304) return false
                if (code != 200) { conn.disconnect(); continue }
                val bytes = conn.inputStream.use { readCapped(it, MAX_LIST) } ?: continue
                val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
                // It has to look like a filter list: anything else (a captive portal page, an error) is ignored.
                if (!text.startsWith("[Adblock Plus") || text.length < 10_000) continue
                val tmp = File(dir, "${s.id}.txt.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(target)) { tmp.delete(); continue }
                prefs.edit()
                    .putString("$tag.url", url)
                    .putString("$tag.etag", conn.getHeaderField("ETag"))
                    .putString("$tag.modified", conn.getHeaderField("Last-Modified"))
                    .apply()
                return true
            } catch (e: IOException) {
                continue
            }
        }
        return null
    }

    private fun readCapped(input: java.io.InputStream, max: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream(1 shl 20)
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Builds a new engine from the saved lists off the main thread, then swaps it in. */
    private suspend fun rebuild() {
        val e = withContext(Dispatchers.Default) {
            FilterEngine().apply {
                addBuiltin(this)
                for (s in sources) {
                    val f = file(s)
                    if (f.exists()) runCatching { addList(f.readText(), s.bit) }
                }
                finish()
            }
        }
        engine = e
        rules = e.networkRules + e.cosmeticRules
    }

    /** The hand-curated list Orbit has always had: works from the first launch, before any download. */
    private fun addBuiltin(e: FilterEngine) {
        BUILTIN.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { e.addLine("||$it^\$third-party", ADS or TRACKERS) }
    }

    private const val BUILTIN = """
        doubleclick.net googlesyndication.com googleadservices.com google-analytics.com
        googletagmanager.com googletagservices.com adservice.google.com
        analytics.google.com app-measurement.com firebaselogging.googleapis.com
        connect.facebook.net pixel.facebook.com an.facebook.com
        ads.twitter.com analytics.twitter.com static.ads-twitter.com ads-api.twitter.com
        ads.linkedin.com px.ads.linkedin.com snap.licdn.com analytics.tiktok.com ads.tiktok.com
        business-api.tiktok.com sc-static.net tr.snapchat.com ct.pinterest.com ads.pinterest.com
        bat.bing.com clarity.ms ads.yahoo.com analytics.yahoo.com sp.analytics.yahoo.com
        adnxs.com adsrvr.org criteo.com criteo.net taboola.com outbrain.com outbrainimg.com
        rubiconproject.com pubmatic.com openx.net casalemedia.com indexww.com smartadserver.com
        adform.net adition.com bidswitch.net 3lift.com triplelift.com sharethrough.com
        teads.tv yieldmo.com media.net contextweb.com spotxchange.com spotx.tv springserve.com
        lijit.com sovrn.com gumgum.com moatads.com moatpixel.com adsafeprotected.com
        doubleverify.com serving-sys.com flashtalking.com innovid.com amazon-adsystem.com
        scorecardresearch.com quantserve.com quantcount.com comscore.com
        chartbeat.com chartbeat.net parsely.com nr-data.net hotjar.com hotjar.io
        mouseflow.com fullstory.com crazyegg.com luckyorange.com inspectlet.com smartlook.com
        mixpanel.com segment.io api.segment.io cdn.segment.com api.amplitude.com
        heapanalytics.com kissmetrics.com
        bluekai.com demdex.net omtrdc.net everesttech.net krxd.net exelator.com
        eyeota.net tapad.com rlcdn.com agkn.com mathtag.com
        mookie1.com crwdcntrl.net bounceexchange.com bouncex.net
        zemanta.com revcontent.com mgid.com adblade.com zergnet.com
        popads.net popcash.net propellerads.com propellerclick.com adsterra.com exoclick.com
        juicyads.com trafficjunky.net trafficjunky.com hilltopads.net clickadu.com
        onclickads.net adcash.com admaven.com ad-maven.com
        mc.yandex.ru an.yandex.ru adfox.ru
        events.reddit.com pixel.reddit.com
        pixel.wp.com
        tiqcdn.com tealiumiq.com ensighten.com
        imasdk.googleapis.com
        adcolony.com applovin.com unityads.unity3d.com vungle.com chartboost.com
        inmobi.com mopub.com supersonicads.com
        zedo.com adtech.de adtechus.com
        yieldlab.net improvedigital.com 360yield.com richaudience.com
        seedtag.com ogury.io pubnative.net smaato.net adhese.com
    """

    /** CSS that hides the most common consent/cookie walls without clicking anything. */
    val cookieCss: String = listOf(
        "#onetrust-banner-sdk", "#onetrust-consent-sdk", ".onetrust-pc-dark-filter",
        "#CybotCookiebotDialog", "#CybotCookiebotDialogBodyUnderlay", "#cookiebanner",
        "#cookie-banner", "#cookieBanner", ".cookie-banner", ".cookie-consent", ".cookieconsent",
        ".cc-window", ".cc-banner", ".cc-grower", "#cookie-notice", ".cookie-notice",
        "#cookie-law-info-bar", ".cli-modal-backdrop", "#gdpr-cookie-message", ".gdpr-banner",
        "#usercentrics-root", "#truste-consent-track", ".truste_overlay", ".truste_box_overlay",
        "#qc-cmp2-container", ".qc-cmp2-container", ".fc-consent-root", "#sp_message_container",
        "#didomi-host", "#cmpbox", "#cmpbox2", ".cmpboxBG", "#iubenda-cs-banner", "#klaro",
        ".osano-cm-window", ".evidon-banner", "#cookiescript_injected", "#moove_gdpr_cookie_info_bar",
        ".cmplz-cookiebanner", "#BorlabsCookieBox", "#cookie-consent-banner", ".consent-banner",
        "#consent-banner", "#hs-eu-cookie-confirmation",
    ).joinToString("") { "$it{display:none!important;visibility:hidden!important}\n" } +
        "html.sp-message-open,body.didomi-popup-open{overflow:auto!important;position:static!important}\n"
}
