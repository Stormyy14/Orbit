package app.orbit

import android.Manifest
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import app.orbit.core.Browser
import app.orbit.core.MediaCenter
import app.orbit.core.Settings
import app.orbit.core.ThemeMode
import app.orbit.ui.BrowserScreen
import app.orbit.ui.LocalBrowser
import app.orbit.ui.Orb
import app.orbit.ui.OrbitTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var browser: Browser
    private var pendingFiles: ((Array<Uri>?) -> Unit)? = null
    private var appliedTheme: ThemeMode? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingFiles?.invoke(Browser.pickedFiles(result.resultCode, result.data))
        pendingFiles = null
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Orb.applySystem(resources.configuration)

        browser = Browser(this, lifecycleScope)
        browser.fileChooser = { intent, callback ->
            pendingFiles?.invoke(null)
            pendingFiles = callback
            runCatching { filePicker.launch(intent) }.onFailure { callback(null); pendingFiles = null }
        }
        browser.askNotifications = ::askNotificationsOnce
        browser.load()
        applyLook(browser.settings)
        // Every change in Customize (or a profile switch) restyles the app right away.
        lifecycleScope.launch { snapshotFlow { browser.settings }.collect(::applyLook) }
        if (savedInstanceState == null) browser.handleIntent(intent)

        setContent {
            CompositionLocalProvider(LocalBrowser provides browser) {
                OrbitTheme {
                    BrowserScreen(browser, onExit = { moveTaskToBack(true) })
                }
            }
        }
    }

    private fun applyLook(s: Settings) {
        Orb.applyLook(s)
        if (appliedTheme == s.theme) return
        appliedTheme = s.theme
        // Android 12+: a light/dark choice for this app only, so web pages (prefers-color-scheme,
        // "Dark websites") and system dialogs follow it too. Older versions just restyle Orbit.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching {
            getSystemService(UiModeManager::class.java).setApplicationNightMode(
                when (s.theme) {
                    ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                    ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                    ThemeMode.DARK, ThemeMode.BLACK -> UiModeManager.MODE_NIGHT_YES
                },
            )
        }
    }

    /** The media notification needs permission on Android 13+; ask the first time something plays. */
    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("device", MODE_PRIVATE)
        if (prefs.getBoolean("askedNotifications", false)) return
        prefs.edit().putBoolean("askedNotifications", true).apply()
        runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Orb.applySystem(newConfig)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        browser.handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        browser.onPause()
    }

    override fun onResume() {
        super.onResume()
        browser.onResume()
    }

    override fun onDestroy() {
        // The tabs (and anything playing in them) go with the activity.
        if (isFinishing) {
            MediaCenter.onCommand = null
            MediaCenter.clear(this)
        }
        super.onDestroy()
    }
}
