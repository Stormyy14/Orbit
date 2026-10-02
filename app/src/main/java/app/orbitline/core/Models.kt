package app.orbitline.core

import org.json.JSONArray
import org.json.JSONObject

data class Space(
    val id: String,
    val name: String,
    /** Key into the UI's icon set (see ui/SpaceIcons.kt). */
    val icon: String,
) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("icon", icon)

    companion object {
        const val DEFAULT_ID = "personal"

        fun fromJson(o: JSONObject): Space {
            val id = o.getString("id")
            val name = o.optString("name", "Space")
            return Space(id, name, o.optString("icon").ifBlank { guessIcon(id, name) })
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

data class Pin(val url: String, val title: String) {
    fun toJson() = JSONObject().put("u", url).put("t", title)

    companion object {
        fun fromJson(o: JSONObject) = Pin(o.getString("u"), o.optString("t"))
    }
}

enum class SearchEngine(val label: String, val template: String, val suggest: Boolean = true) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    BRAVE("Brave Search", "https://search.brave.com/search?q=%s"),
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    STARTPAGE("Startpage", "https://www.startpage.com/do/search?q=%s"),
    ECOSIA("Ecosia", "https://www.ecosia.org/search?q=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s"),
}

data class Settings(
    val engine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val suggestions: Boolean = true,
    val shields: Boolean = true,
    val hideCookieBanners: Boolean = true,
    val ghostMinutes: Int = 15,
    val haptics: Boolean = true,
    val darkPages: Boolean = false,
    val desktopDefault: Boolean = false,
    val collapseOnScroll: Boolean = true,
    val flowDomains: List<String> = listOf(
        "youtube.com", "tiktok.com", "instagram.com", "x.com", "twitter.com",
        "reddit.com", "facebook.com", "netflix.com", "twitch.tv",
    ),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("engine", engine.name)
        .put("suggestions", suggestions)
        .put("shields", shields)
        .put("cookies", hideCookieBanners)
        .put("ghostMinutes", ghostMinutes)
        .put("haptics", haptics)
        .put("darkPages", darkPages)
        .put("desktop", desktopDefault)
        .put("collapse", collapseOnScroll)
        .put("flow", JSONArray(flowDomains))

    companion object {
        fun fromJson(o: JSONObject): Settings {
            val d = Settings()
            return Settings(
                engine = runCatching { SearchEngine.valueOf(o.getString("engine")) }.getOrDefault(d.engine),
                suggestions = o.optBoolean("suggestions", d.suggestions),
                shields = o.optBoolean("shields", d.shields),
                hideCookieBanners = o.optBoolean("cookies", d.hideCookieBanners),
                ghostMinutes = o.optInt("ghostMinutes", d.ghostMinutes),
                haptics = o.optBoolean("haptics", d.haptics),
                darkPages = o.optBoolean("darkPages", d.darkPages),
                desktopDefault = o.optBoolean("desktop", d.desktopDefault),
                collapseOnScroll = o.optBoolean("collapse", d.collapseOnScroll),
                flowDomains = o.optJSONArray("flow")?.let { a -> List(a.length()) { a.getString(it) } } ?: d.flowDomains,
            )
        }
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
