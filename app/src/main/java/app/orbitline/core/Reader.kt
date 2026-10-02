package app.orbitline.core

import org.json.JSONObject

data class ReaderBlock(val type: String, val text: String)

data class ReaderDoc(
    val title: String,
    val byline: String,
    val site: String,
    val hero: String,
    val words: Int,
    val blocks: List<ReaderBlock>,
    val url: String,
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
            )
        }.getOrNull()
    }
}
