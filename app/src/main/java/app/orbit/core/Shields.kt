package app.orbit.core

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Lightweight on-device tracker & ad shield. Matches request hosts (and their parent
 * domains) against a curated list. Only third-party requests are blocked, so a site
 * can never break itself by being on the list.
 */
object Shields {
    private val blocked: Set<String> = """
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
    """.split(Regex("\\s+")).filter { it.isNotBlank() }.toHashSet()

    private fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    fun isTracker(host: String): Boolean {
        var h = host.lowercase()
        while (true) {
            if (h in blocked) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** Returns an empty response if [requestUrl] should be blocked for a page on [pageHost]. */
    fun intercept(requestUrl: Uri, pageHost: String?): WebResourceResponse? {
        val host = requestUrl.host ?: return null
        if (pageHost != null && Url.sameSite(host, pageHost)) return null
        return if (isTracker(host)) empty() else null
    }

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
    ).joinToString(",") + "{display:none!important;visibility:hidden!important}" +
        "html.sp-message-open,body.didomi-popup-open{overflow:auto!important;position:static!important}"
}
