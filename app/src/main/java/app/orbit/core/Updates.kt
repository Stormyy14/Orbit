package app.orbit.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.orbit.MainActivity
import app.orbit.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A newer Orbit published on GitHub. */
data class Release(
    val version: String,
    /** The "What's new" part of the release notes, as plain lines. */
    val notes: List<String>,
    val apkUrl: String,
    val shaUrl: String?,
    val size: Long,
    val page: String,
)

/**
 * Updates from GitHub Releases: checks for a newer version (at most every few hours, or when
 * asked), downloads its APK, checks it against the published SHA-256 and hands it to Android's
 * installer. Android only accepts it if it's signed with the same key as the installed app.
 */
object Updates {
    enum class Phase { IDLE, CHECKING, DOWNLOADING, NEEDS_PERMISSION, INSTALLING, FAILED }

    private const val LATEST = "https://api.github.com/repos/Stormyy14/Orbit/releases/latest"
    private const val CHECK_EVERY_MS = 6 * 3_600_000L
    private const val MAX_APK = 150L shl 20
    internal const val ACTION_STATUS = "app.orbit.update.STATUS"
    private const val CHANNEL = "updates"

    /** The newer release, if there is one. */
    var available by mutableStateOf<Release?>(null)
        private set
    var phase by mutableStateOf(Phase.IDLE)
        private set
    /** Download progress, 0..1. */
    var progress by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Set after a check that found nothing newer, so Settings can say "Up to date". */
    var upToDate by mutableStateOf(false)
        private set
    var autoCheck by mutableStateOf(true)
        private set

    lateinit var current: String
        private set
    /** A downloaded and verified APK, kept so a retry doesn't download it again. */
    private var ready: Pair<String, File>? = null
    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("device", Context.MODE_PRIVATE)

    fun init(context: Context) {
        app = context.applicationContext
        current = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0"
        autoCheck = prefs.getBoolean("autoUpdateCheck", true)
        // An update found earlier is remembered until it's installed.
        available = prefs.getString("pendingUpdate", null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
            ?.takeIf { newer(it.version, current) }
        // A downloaded update that's already installed (or older) is no longer needed.
        File(app.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    fun changeAutoCheck(on: Boolean) {
        autoCheck = on
        prefs.edit().putBoolean("autoUpdateCheck", on).apply()
    }

    /** Checks if it's been a while (and automatic checks are on). */
    fun checkIfDue(scope: CoroutineScope) {
        if (!autoCheck) return
        val last = prefs.getLong("lastUpdateCheck", 0L)
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS && available == null) return
        check(scope)
    }

    fun check(scope: CoroutineScope, onDone: ((Release?) -> Unit)? = null) {
        if (phase == Phase.CHECKING || phase == Phase.DOWNLOADING || phase == Phase.INSTALLING) return
        phase = Phase.CHECKING
        error = null
        scope.launch {
            val release = runCatching { fetchLatest() }.getOrElse {
                error = "Couldn't check for updates"
                phase = Phase.FAILED
                onDone?.invoke(null)
                return@launch
            }
            available = release?.takeIf { newer(it.version, current) }
            prefs.edit()
                .putLong("lastUpdateCheck", System.currentTimeMillis())
                .apply { available?.let { putString("pendingUpdate", toJson(it).toString()) } ?: remove("pendingUpdate") }
                .apply()
            upToDate = available == null
            phase = Phase.IDLE
            onDone?.invoke(available)
        }
    }

    /** True once per version: the first time a new release is seen, the update screen opens. */
    fun shouldAnnounce(r: Release): Boolean = prefs.getString("announcedUpdate", null) != r.version

    fun markAnnounced(r: Release) {
        prefs.edit().putString("announcedUpdate", r.version).apply()
    }

    /** Downloads, verifies and installs [r]. */
    fun install(scope: CoroutineScope, r: Release) {
        if (phase == Phase.DOWNLOADING || phase == Phase.INSTALLING) return
        error = null
        scope.launch {
            val apk = ready?.takeIf { it.first == r.version && it.second.exists() }?.second ?: run {
                phase = Phase.DOWNLOADING
                progress = 0f
                runCatching { download(r) }.getOrElse { e ->
                    error = e.message?.takeIf { e is UpdateException } ?: "Couldn't download the update"
                    phase = Phase.FAILED
                    return@launch
                }
            }
            ready = r.version to apk
            proceed(scope, apk)
        }
    }

    /**
     * Android only lets Orbit install updates once you've allowed it (once, in system settings).
     * If that's still needed, the settings page opens and the install carries on when you return.
     */
    private fun proceed(scope: CoroutineScope, apk: File) {
        if (!app.packageManager.canRequestPackageInstalls()) {
            phase = Phase.NEEDS_PERMISSION
            openInstallPermission()
            return
        }
        phase = Phase.INSTALLING
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { commit(apk) } }.onFailure {
                error = "Couldn't start the installer"
                phase = Phase.FAILED
            }
        }
    }

    fun openInstallPermission() {
        runCatching {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Back from system settings: continue a waiting install if it's allowed now. */
    fun onResume(scope: CoroutineScope) {
        if (phase != Phase.NEEDS_PERMISSION) return
        val apk = ready?.second?.takeIf { it.exists() } ?: run { phase = Phase.IDLE; return }
        if (app.packageManager.canRequestPackageInstalls()) proceed(scope, apk)
    }

    // ---------------------------------------------------------------------------------------

    private class UpdateException(message: String) : Exception(message)

    private suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val o = JSONObject(get(LATEST, accept = "application/vnd.github+json"))
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return@withContext null
        val version = o.optString("tag_name").removePrefix("v").trim()
        if (version.isEmpty()) return@withContext null
        val assets = o.optJSONArray("assets") ?: return@withContext null
        val list = List(assets.length()) { assets.getJSONObject(it) }
        val apk = list.firstOrNull { it.optString("name") == "Orbit-$version.apk" }
            ?: list.firstOrNull { it.optString("name").endsWith(".apk") }
            ?: return@withContext null
        val sha = list.firstOrNull { it.optString("name") == apk.optString("name") + ".sha256" }
        Release(
            version = version,
            notes = whatsNew(o.optString("body")),
            apkUrl = apk.optString("browser_download_url"),
            shaUrl = sha?.optString("browser_download_url"),
            size = apk.optLong("size"),
            page = o.optString("html_url"),
        )
    }

    private suspend fun download(r: Release): File = withContext(Dispatchers.IO) {
        if (!r.apkUrl.startsWith("https://")) throw UpdateException("The update link isn't secure")
        val expected = r.shaUrl?.let { url -> get(url).trim().split(Regex("\\s+")).firstOrNull()?.lowercase() }
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "Orbit-${r.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = open(r.apkUrl)
        try {
            if (conn.responseCode !in 200..299) throw UpdateException("Couldn't download the update")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.size
            if (total > MAX_APK) throw UpdateException("The update is too large")
            var done = 0L
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done > MAX_APK) throw UpdateException("The update is too large")
                        if (total > 0) progress = (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (expected != null && expected != actual) {
            file.delete()
            throw UpdateException("The download was damaged. Try again.")
        }
        progress = 1f
        file
    }

    /** Hands the APK to Android's installer; it asks the user to confirm. */
    private fun commit(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("orbit.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                app, id,
                Intent(app, UpdateReceiver::class.java).setAction(ACTION_STATUS),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(status.intentSender)
        }
    }

    internal fun onStatus(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { error = "Couldn't open the installer"; phase = Phase.FAILED }
            }
            PackageInstaller.STATUS_SUCCESS -> phase = Phase.IDLE
            PackageInstaller.STATUS_FAILURE_ABORTED -> phase = Phase.IDLE
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                error = "This update is signed differently from your Orbit, so Android won't install it"
                phase = Phase.FAILED
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> { error = "Not enough storage for the update"; phase = Phase.FAILED }
            else -> { error = "The update wasn't installed"; phase = Phase.FAILED }
        }
    }

    /** After an update, the new version tells you it's ready (the old one was closed to install it). */
    internal fun announceUpdated(context: Context) {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            nm.notify(
                8,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.orbit_mark)
                    .setContentTitle("Orbit updated to $version")
                    .setContentText("Tap to open")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Orbit/$current (Android)")
    }

    private fun get(url: String, accept: String? = null): String {
        val conn = open(url)
        try {
            accept?.let { conn.setRequestProperty("Accept", it) }
            if (conn.responseCode !in 200..299) throw UpdateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText().take(200_000) }
        } finally {
            conn.disconnect()
        }
    }

    private fun toJson(r: Release) = JSONObject()
        .put("version", r.version).put("notes", org.json.JSONArray(r.notes)).put("apk", r.apkUrl)
        .put("sha", r.shaUrl).put("size", r.size).put("page", r.page)

    private fun fromJson(o: JSONObject): Release {
        val notes = o.optJSONArray("notes")
        return Release(
            version = o.getString("version"),
            notes = List(notes?.length() ?: 0) { notes!!.getString(it) },
            apkUrl = o.getString("apk"),
            shaUrl = o.optString("sha").takeIf { it.startsWith("https://") },
            size = o.optLong("size"),
            page = o.optString("page"),
        )
    }

    /** True if [a] is a higher version than [b] ("0.10.0" > "0.9.2"). */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** The bullet points under "What's new", without Markdown. */
    private fun whatsNew(body: String): List<String> {
        val lines = body.replace("\r", "").lines()
        val start = lines.indexOfFirst { it.trim().lowercase().startsWith("## what") }
        val section = if (start >= 0) lines.drop(start + 1).takeWhile { !it.trim().startsWith("## ") } else lines
        return section.map { it.trim() }.filter { it.isNotEmpty() }
            .map { line ->
                line.removePrefix("- ").removePrefix("* ")
                    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
                    .replace(Regex("\\*(.+?)\\*"), "$1")
                    .replace(Regex("`([^`]*)`"), "$1")
                    .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            }
            .take(30)
    }
}

/** Results from Android's installer, and the "you've been updated" moment. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Not exported: only Android (and Orbit's own installer callback) can reach this.
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> Updates.announceUpdated(context)
            Updates.ACTION_STATUS -> Updates.onStatus(context, intent)
        }
    }
}
