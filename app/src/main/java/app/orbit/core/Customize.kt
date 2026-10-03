package app.orbit.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Start page wallpapers: one image per profile, kept on this device only. */
object Wallpapers {
    private const val MAX_SIDE = 1600
    private const val MAX_BYTES = 40 shl 20

    private fun file(context: Context, profileId: String?) =
        File(File(context.filesDir, "wallpapers").apply { mkdirs() }, (profileId ?: "guest") + ".jpg")

    /** Copies a picked image in, scaled down to a sensible size. Returns false if it isn't an image. */
    suspend fun save(context: Context, uri: Uri, profileId: String?): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return@runCatching false
                }
                out.toByteArray()
            } ?: return@runCatching false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@runCatching false
            file(context, profileId).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            true
        }.getOrDefault(false)
    }

    suspend fun load(context: Context, profileId: String?): ImageBitmap? = withContext(Dispatchers.IO) {
        val f = file(context, profileId)
        if (!f.exists()) null else runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()
    }

    fun delete(context: Context, profileId: String?) {
        runCatching { file(context, profileId).delete() }
    }
}

/**
 * Launcher icon choices. Each one is an <activity-alias> in the manifest; exactly one is enabled.
 * The classic alias keeps the launcher component name older versions used, so updating doesn't
 * remove Orbit from the home screen.
 */
enum class AppIcon(val label: String, val alias: String, val background: Long, val mark: Long) {
    CLASSIC("Classic", "app.orbitline.MainActivity", 0xFF0A0A0A, 0xFFFFFFFF),
    LIGHT("Light", "app.orbit.IconLight", 0xFFFFFFFF, 0xFF0A0A0A),
    BLUE("Blue", "app.orbit.IconBlue", 0xFF0A6CFF, 0xFFFFFFFF),
    VIOLET("Violet", "app.orbit.IconViolet", 0xFF7C3AED, 0xFFFFFFFF),
    GREEN("Green", "app.orbit.IconGreen", 0xFF15803D, 0xFFFFFFFF);

    companion object {
        fun current(context: Context): AppIcon {
            val pm = context.packageManager
            return entries.firstOrNull { icon ->
                when (pm.getComponentEnabledSetting(ComponentName(context.packageName, icon.alias))) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == CLASSIC
                    else -> false
                }
            } ?: CLASSIC
        }

        fun set(context: Context, choice: AppIcon) {
            val pm = context.packageManager
            // Turn the new one on before the old one off, so there's always a launcher entry.
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, choice.alias),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP,
            )
            entries.filter { it != choice }.forEach {
                pm.setComponentEnabledSetting(
                    ComponentName(context.packageName, it.alias),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
