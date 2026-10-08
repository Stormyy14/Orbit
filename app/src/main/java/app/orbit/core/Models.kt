package app.orbit.core

import org.json.JSONArray
import org.json.JSONObject

data class Space(
    val id: String,
    val name: String,
    /** Key into the UI's icon set (see ui/SpaceIcons.kt). */
    val icon: String,
    /** "Recently visited" only lists visits after this. */
    val recentSince: Long = 0L,
) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("icon", icon)
        .put("recent", recentSince)

    companion object {
        const val DEFAULT_ID = "personal"

        fun fromJson(o: JSONObject): Space {
            val id = o.getString("id")
            val name = o.optString("name", "Space")
            return Space(
                id, name, o.optString("icon").ifBlank { guessIcon(id, name) },
                recentSince = o.optLong("recent"),
            )
        }

        /** Older versions stored an emoji/colour; pick a sensible icon from the name instead. */
        private fun guessIcon(id: String, name: String): String {
            val n = name.lowercase()
            return when {
                id == DEFAULT_ID || "personal" in n -> "person"
                "work" in n || "job" in n || "office" in n -> "work"
                "code" in n || "dev" in n -> "code"
                "study" in n || "school" in n || "uni" in n -> "school"
                "shop" in n -> "shopping"
                "home" in n || "family" in n -> "home"
                "travel" in n || "trip" in n -> "travel"
                "game" in n -> "games"
                "music" in n -> "music"
                "research" in n || "read" in n -> "book"
                else -> "folder"
            }
        }

        val Defaults = listOf(
            Space(DEFAULT_ID, "Personal", "person"),
            Space("work", "Work", "work"),
        )
    }
}

data class HistoryEntry(
    val url: String,
    val title: String,
    val time: Long,
    val spaceId: String,
) {
    fun toJson() = JSONObject().put("u", url).put("t", title).put("d", time).put("s", spaceId)

    companion object {
        fun fromJson(o: JSONObject) = HistoryEntry(
            o.getString("u"), o.optString("t"), o.optLong("d"), o.optString("s", Space.DEFAULT_ID),
        )
    }
}

/** A site on the orbit. [satellite]: also one of the sites Satellite fans out. */
data class Pin(val url: String, val title: String, val satellite: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("u", url).put("t", title).apply { if (satellite) put("s", true) }

    companion object {
        fun fromJson(o: JSONObject) = Pin(o.getString("u"), o.optString("t"), o.optBoolean("s"))
    }
}

enum class SearchEngine(val label: String, val template: String, val suggest: Boolean = true) {
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    BRAVE("Brave Search", "https://search.brave.com/search?q=%s"),
    STARTPAGE("Startpage", "https://www.startpage.com/do/search?q=%s"),
    ECOSIA("Ecosia", "https://www.ecosia.org/search?q=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s"),
}

// ---- Customization: how Orbit looks and behaves. Part of each profile's settings. ----

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark"), BLACK("Black") }

/** The colour of primary buttons, switches and selections. [MONO] uses the text colour. */
enum class Accent(val label: String, val light: Long, val dark: Long) {
    MONO("Mono", 0xFF171717, 0xFFEDEDED),
    BLUE("Blue", 0xFF0A6CFF, 0xFF5AA2FF),
    VIOLET("Violet", 0xFF7C3AED, 0xFFA78BFA),
    PINK("Pink", 0xFFDB2777, 0xFFF472B6),
    ORANGE("Orange", 0xFFEA580C, 0xFFFB923C),
    YELLOW("Yellow", 0xFFB45309, 0xFFFACC15),
    GREEN("Green", 0xFF15803D, 0xFF4ADE80),
    TEAL("Teal", 0xFF0F766E, 0xFF2DD4BF),
}

/** Corner radius of buttons, fields and cards (small, large), in dp. */
enum class Corners(val label: String, val small: Int, val large: Int) {
    SQUARE("Square", 2, 4), SOFT("Soft", 8, 12), ROUND("Round", 16, 22),
}

enum class FontChoice(val label: String) { GEIST("Geist"), SYSTEM("System"), SERIF("Serif"), MONO("Mono") }

data class Settings(
    val engine: SearchEngine = SearchEngine.GOOGLE,
    val suggestions: Boolean = true,
    /** Shields as a whole; the switches below choose what they do. */
    val shields: Boolean = true,
    val blockAds: Boolean = true,
    val blockTrackers: Boolean = true,
    val hideCookieBanners: Boolean = true,
    /** Removes tracking parameters (utm_*, fbclid, gclid, …) from links you open. */
    val cleanLinks: Boolean = true,
    val ghostMinutes: Int = 15,
    val haptics: Boolean = true,
    val darkPages: Boolean = false,
    val desktopDefault: Boolean = false,
    val collapseOnScroll: Boolean = true,
    val flowDomains: List<String> = listOf(
        "youtube.com", "tiktok.com", "instagram.com", "x.com", "twitter.com",
        "reddit.com", "facebook.com", "netflix.com", "twitch.tv",
    ),
    /** Keep video and music playing with Orbit in the background, with media controls. */
    val backgroundPlay: Boolean = true,
    /** Links to sites with an app on this device (LinkedIn, Spotify, …) open in that app. */
    val openInApps: Boolean = true,
    /** Satellite: a handle on the side of the screen that fans out your favorite sites. */
    val satellite: Boolean = false,
    /** Which edge the Satellite handle sits on, and how far down (0 = top, 1 = bottom). */
    val satelliteLeft: Boolean = false,
    val satelliteY: Float = 0.4f,
    // Look
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: Accent = Accent.MONO,
    val corners: Corners = Corners.SOFT,
    val font: FontChoice = FontChoice.GEIST,
    /** Scale for all app text (not web pages). */
    val textScale: Float = 1f,
    /** Web page text zoom, in percent. */
    val pageZoom: Int = 100,
    // Address bar
    val barTop: Boolean = false,
    val fullAddress: Boolean = false,
    // Start page
    val showClock: Boolean = false,
    val showSearch: Boolean = true,
    val showOrbit: Boolean = true,
    val showRecent: Boolean = true,
    /** When the start page wallpaper was set (0 = none). The image itself stays on this device. */
    val wallpaper: Long = 0L,
    /** How much the wallpaper is dimmed under the start page, in percent. */
    val wallpaperDim: Int = 50,
    /** Show the whole image (bars around it) instead of filling the screen. */
    val wallpaperFit: Boolean = false,
    /** Zoom on top of filling (or fitting) the screen; 1 = none. */
    val wallpaperZoom: Float = 1f,
    /** The point of the image kept at the centre of the screen, as fractions of its width and height. */
    val wallpaperX: Float = 0.5f,
    val wallpaperY: Float = 0.5f,
    /** Blur radius in dp (Android 12 and newer). */
    val wallpaperBlur: Int = 0,
) {
    /** How the wallpaper is framed: kept with the image on this device, never synced. */
    fun withWallpaperOf(o: Settings) = copy(
        wallpaper = o.wallpaper, wallpaperFit = o.wallpaperFit, wallpaperZoom = o.wallpaperZoom,
        wallpaperX = o.wallpaperX, wallpaperY = o.wallpaperY,
    )

    /** The same settings with every customization back to its default. */
    fun withDefaultLook(): Settings {
        val d = Settings()
        return copy(
            theme = d.theme, accent = d.accent, corners = d.corners, font = d.font, textScale = d.textScale,
            pageZoom = d.pageZoom, barTop = d.barTop, fullAddress = d.fullAddress, showClock = d.showClock,
            showSearch = d.showSearch, showOrbit = d.showOrbit, showRecent = d.showRecent, wallpaperDim = d.wallpaperDim, wallpaperBlur = d.wallpaperBlur,
            darkPages = d.darkPages, collapseOnScroll = d.collapseOnScroll,
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("engine", engine.name)
        .put("suggestions", suggestions)
        .put("shields", shields)
        .put("ads", blockAds)
        .put("trackers", blockTrackers)
        .put("cookies", hideCookieBanners)
        .put("cleanLinks", cleanLinks)
        .put("ghostMinutes", ghostMinutes)
        .put("haptics", haptics)
        .put("darkPages", darkPages)
        .put("desktop", desktopDefault)
        .put("collapse", collapseOnScroll)
        .put("flow", JSONArray(flowDomains))
        .put("bgPlay", backgroundPlay)
        .put("apps", openInApps)
        .put("satellite", satellite)
        .put("satLeft", satelliteLeft)
        .put("satY", satelliteY.toDouble())
        .put("theme", theme.name)
        .put("accent", accent.name)
        .put("corners", corners.name)
        .put("font", font.name)
        .put("textScale", textScale.toDouble())
        .put("pageZoom", pageZoom)
        .put("barTop", barTop)
        .put("fullAddress", fullAddress)
        .put("clock", showClock)
        .put("search", showSearch)
        .put("orbit", showOrbit)
        .put("recent", showRecent)
        .put("wallpaper", wallpaper)
        .put("wallpaperDim", wallpaperDim)
        .put("wallpaperFit", wallpaperFit)
        .put("wallpaperZoom", wallpaperZoom.toDouble())
        .put("wallpaperX", wallpaperX.toDouble())
        .put("wallpaperY", wallpaperY.toDouble())
        .put("wallpaperBlur", wallpaperBlur)

    companion object {
        /** 2: Google became the default engine; older files get it once. */
        private const val VERSION = 2

        fun fromJson(o: JSONObject): Settings {
            val d = Settings()
            val engine = if (o.optInt("v") < 2) d.engine
            else runCatching { SearchEngine.valueOf(o.getString("engine")) }.getOrDefault(d.engine)
            return Settings(
                engine = engine,
                suggestions = o.optBoolean("suggestions", d.suggestions),
                shields = o.optBoolean("shields", d.shields),
                blockAds = o.optBoolean("ads", d.blockAds),
                blockTrackers = o.optBoolean("trackers", d.blockTrackers),
                hideCookieBanners = o.optBoolean("cookies", d.hideCookieBanners),
                cleanLinks = o.optBoolean("cleanLinks", d.cleanLinks),
                ghostMinutes = o.optInt("ghostMinutes", d.ghostMinutes),
                haptics = o.optBoolean("haptics", d.haptics),
                darkPages = o.optBoolean("darkPages", d.darkPages),
                desktopDefault = o.optBoolean("desktop", d.desktopDefault),
                collapseOnScroll = o.optBoolean("collapse", d.collapseOnScroll),
                flowDomains = o.optJSONArray("flow")?.let { a -> List(a.length()) { a.getString(it) } } ?: d.flowDomains,
                backgroundPlay = o.optBoolean("bgPlay", d.backgroundPlay),
                openInApps = o.optBoolean("apps", d.openInApps),
                satellite = o.optBoolean("satellite", d.satellite),
                satelliteLeft = o.optBoolean("satLeft", d.satelliteLeft),
                satelliteY = o.optDouble("satY", d.satelliteY.toDouble()).toFloat().coerceIn(0f, 1f),
                theme = enumOr(o.optString("theme"), d.theme),
                accent = enumOr(o.optString("accent"), d.accent),
                corners = enumOr(o.optString("corners"), d.corners),
                font = enumOr(o.optString("font"), d.font),
                textScale = o.optDouble("textScale", d.textScale.toDouble()).toFloat().coerceIn(0.8f, 1.5f),
                pageZoom = o.optInt("pageZoom", d.pageZoom).coerceIn(50, 200),
                barTop = o.optBoolean("barTop", d.barTop),
                fullAddress = o.optBoolean("fullAddress", d.fullAddress),
                showClock = o.optBoolean("clock", d.showClock),
                showSearch = o.optBoolean("search", d.showSearch),
                showOrbit = o.optBoolean("orbit", d.showOrbit),
                showRecent = o.optBoolean("recent", d.showRecent),
                wallpaper = o.optLong("wallpaper", d.wallpaper),
                wallpaperDim = o.optInt("wallpaperDim", d.wallpaperDim).coerceIn(0, 90),
                wallpaperFit = o.optBoolean("wallpaperFit", d.wallpaperFit),
                wallpaperZoom = o.optDouble("wallpaperZoom", d.wallpaperZoom.toDouble()).toFloat().coerceIn(1f, 5f),
                wallpaperX = o.optDouble("wallpaperX", d.wallpaperX.toDouble()).toFloat().coerceIn(0f, 1f),
                wallpaperY = o.optDouble("wallpaperY", d.wallpaperY.toDouble()).toFloat().coerceIn(0f, 1f),
                wallpaperBlur = o.optInt("wallpaperBlur", d.wallpaperBlur).coerceIn(0, 40),
            )
        }

        private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: fallback
    }
}

/** A saved tab for session restore. Ghost tabs are never persisted. */
data class SavedTab(val id: String, val spaceId: String, val url: String, val title: String) {
    fun toJson() = JSONObject().put("id", id).put("s", spaceId).put("u", url).put("t", title)

    companion object {
        fun fromJson(o: JSONObject) = SavedTab(
            o.getString("id"), o.optString("s", Space.DEFAULT_ID), o.optString("u"), o.optString("t"),
        )
    }
}
