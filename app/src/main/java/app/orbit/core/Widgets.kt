package app.orbit.core

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import app.orbit.MainActivity
import app.orbit.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What a widget asks Orbit to do when it opens (see [Browser.launch]). */
enum class Launch { SEARCH, VOICE, NEW_TAB, GHOST, VPN, FOCUS }

/**
 * Home-screen widgets: a search bar, favorite sites and quick actions. They only open Orbit:
 * every button is an intent to Orbit's own activity, and nothing a widget does can switch a
 * protection off (the VPN button opens the Orbit VPN screen, it doesn't toggle it).
 */
object Widgets {
    private const val PREFIX = "app.orbit.widget."
    private const val SLOTS = 8

    /** The [Launch] an intent from a widget asks for, or null. */
    fun launchFor(intent: Intent): Launch? {
        val action = intent.action ?: return null
        if (!action.startsWith(PREFIX)) return null
        return Launch.entries.firstOrNull { PREFIX + it.name == action }
    }

    private fun open(context: Context, launch: Launch): PendingIntent =
        PendingIntent.getActivity(
            context, launch.ordinal,
            Intent(context, MainActivity::class.java).setAction(PREFIX + launch.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openUrl(context: Context, slot: Int, url: String): PendingIntent =
        PendingIntent.getActivity(
            context, 100 + slot,
            Intent(Intent.ACTION_VIEW, Uri.parse(url), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun prefs(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    /**
     * Remembers what the widgets show (the active profile's favorites, and whether Orbit VPN is
     * on), then redraws them. Only changes are written, so it's cheap to call often.
     */
    fun publish(context: Context, pins: List<Pin>, vpnOn: Boolean) {
        val favs = JSONArray().apply { pins.take(SLOTS).forEach { put(JSONObject().put("u", it.url).put("t", it.title)) } }.toString()
        val p = prefs(context)
        if (p.getString("favorites", null) == favs && p.getBoolean("vpn", false) == vpnOn && p.contains("favorites")) return
        p.edit().putString("favorites", favs).putBoolean("vpn", vpnOn).apply()
        refresh(context)
    }

    /** Redraws every Orbit widget on the home screen. */
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        fun ids(c: Class<*>) = runCatching { manager.getAppWidgetIds(ComponentName(context, c)) }.getOrDefault(IntArray(0))
        ids(SearchWidget::class.java).forEach { manager.updateAppWidget(it, search(context)) }
        ids(ActionsWidget::class.java).forEach { manager.updateAppWidget(it, actions(context)) }
        ids(FavoritesWidget::class.java).takeIf { it.isNotEmpty() }?.let { all ->
            val views = favorites(context)
            all.forEach { manager.updateAppWidget(it, views) }
        }
    }

    internal fun search(context: Context) = RemoteViews(context.packageName, R.layout.widget_search).apply {
        setOnClickPendingIntent(R.id.widget_search, open(context, Launch.SEARCH))
        setOnClickPendingIntent(R.id.widget_voice, open(context, Launch.VOICE))
        setOnClickPendingIntent(R.id.widget_ghost, open(context, Launch.GHOST))
    }

    internal fun actions(context: Context) = RemoteViews(context.packageName, R.layout.widget_actions).apply {
        setOnClickPendingIntent(R.id.action_new, open(context, Launch.NEW_TAB))
        setOnClickPendingIntent(R.id.action_ghost, open(context, Launch.GHOST))
        setOnClickPendingIntent(R.id.action_vpn, open(context, Launch.VPN))
        setOnClickPendingIntent(R.id.action_focus, open(context, Launch.FOCUS))
        val vpnOn = prefs(context).getBoolean("vpn", false)
        setTextViewText(R.id.action_vpn_label, if (vpnOn) "VPN on" else "VPN off")
    }

    private val ICONS = intArrayOf(R.id.fav0_icon, R.id.fav1_icon, R.id.fav2_icon, R.id.fav3_icon, R.id.fav4_icon, R.id.fav5_icon, R.id.fav6_icon, R.id.fav7_icon)
    private val LABELS = intArrayOf(R.id.fav0_label, R.id.fav1_label, R.id.fav2_label, R.id.fav3_label, R.id.fav4_label, R.id.fav5_label, R.id.fav6_label, R.id.fav7_label)
    private val CELLS = intArrayOf(R.id.fav0, R.id.fav1, R.id.fav2, R.id.fav3, R.id.fav4, R.id.fav5, R.id.fav6, R.id.fav7)

    internal fun favorites(context: Context) = RemoteViews(context.packageName, R.layout.widget_favorites).apply {
        val list = runCatching {
            val a = JSONArray(prefs(context).getString("favorites", "[]"))
            List(a.length()) { a.getJSONObject(it) }.map { it.optString("u") to it.optString("t") }
        }.getOrDefault(emptyList()).filter { (u, _) -> u.startsWith("https://") || u.startsWith("http://") }
        setViewVisibility(R.id.fav_empty, if (list.isEmpty()) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.fav_grid, if (list.isEmpty()) View.GONE else View.VISIBLE)
        if (list.isEmpty()) setOnClickPendingIntent(R.id.fav_empty, open(context, Launch.NEW_TAB))
        val dark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        for (i in 0 until SLOTS) {
            val item = list.getOrNull(i)
            // Unused cells stay in place (invisible), so the icons line up the same way every time.
            setViewVisibility(CELLS[i], if (item == null) View.INVISIBLE else View.VISIBLE)
            if (item == null) continue
            val (url, title) = item
            val host = Url.host(url).orEmpty()
            val name = Url.siteName(title, url).take(14)
            setTextViewText(LABELS[i], name)
            setImageViewBitmap(ICONS[i], icon(context, host, name, dark))
            setOnClickPendingIntent(CELLS[i], openUrl(context, i, url))
        }
    }

    /** The site's saved icon, rounded; or a tile with its first letter. */
    private fun icon(context: Context, host: String, name: String, dark: Boolean): Bitmap {
        val size = 96
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val clip = Path().apply { addRoundRect(rect, size * 0.24f, size * 0.24f, Path.Direction.CW) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val saved = runCatching {
            File(File(context.filesDir, "icons"), Url.site(host) + ".png").takeIf { host.isNotEmpty() && it.exists() }
                ?.let { BitmapFactory.decodeFile(it.path) }
        }.getOrNull()
        c.save()
        c.clipPath(clip)
        val bg = if (dark) 0xFF1A1A1A.toInt() else 0xFFF2F2F2.toInt()
        paint.color = bg
        c.drawRect(rect, paint)
        if (saved != null && saved.width >= 16 && visibleOn(saved, bg)) {
            val inset = if (saved.width >= 96) 0f else size * 0.18f
            c.drawBitmap(saved, null, RectF(inset, inset, size - inset, size - inset), paint)
        } else {
            paint.color = if (dark) Color.WHITE else 0xFF171717.toInt()
            paint.textSize = size * 0.46f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
            c.drawText(letter, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        }
        c.restore()
        return out
    }

    /**
     * False for icons that would all but vanish on the tile: mostly transparent, or drawn in
     * nearly the tile's colour (a white logo on a light tile). Those get a letter instead.
     */
    private fun visibleOn(icon: Bitmap, bg: Int): Boolean {
        val step = maxOf(1, icon.width / 24)
        var seen = 0
        var visible = 0
        for (y in 0 until icon.height step step) for (x in 0 until icon.width step step) {
            seen++
            val p = icon.getPixel(x, y)
            if (Color.alpha(p) < 60) continue
            val d = Math.abs(Color.red(p) - Color.red(bg)) + Math.abs(Color.green(p) - Color.green(bg)) + Math.abs(Color.blue(p) - Color.blue(bg))
            if (d > 90) visible++
        }
        return seen > 0 && visible * 100 / seen >= 6
    }
}

/** The search bar widget. */
class SearchWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, Widgets.search(context)) }
    }
}

/** Favorite sites. */
class FavoritesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = Widgets.favorites(context)
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}

/** New tab, ghost tab, Orbit VPN, Focus. */
class ActionsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, Widgets.actions(context)) }
    }
}
