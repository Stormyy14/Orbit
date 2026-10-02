package app.orbitline

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import app.orbitline.core.Browser
import app.orbitline.ui.BrowserScreen
import app.orbitline.ui.Orb
import app.orbitline.ui.OrbitTheme
import app.orbitline.ui.LocalBrowser

class MainActivity : ComponentActivity() {

    private lateinit var browser: Browser
    private var pendingFiles: ((Array<Uri>?) -> Unit)? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingFiles?.invoke(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        pendingFiles = null
    }

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
        browser.load()
        if (savedInstanceState == null) browser.handleIntent(intent)

        setContent {
            OrbitTheme {
                CompositionLocalProvider(LocalBrowser provides browser) {
                    BrowserScreen(browser, onExit = { moveTaskToBack(true) })
                }
            }
        }
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
}
