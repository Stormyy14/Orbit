package app.orbit.core

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.torproject.jni.TorService
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Orbit VPN: routes everything Orbit loads through the Tor network, using a Tor client that runs
 * inside the app. Free, no account and no Orbit servers: traffic is wrapped in three layers of
 * encryption and relayed by three volunteer-run servers, so sites see a Tor address instead of
 * yours, and your network only sees that you use Tor.
 *
 * It fails closed: while it's on but not connected, WebViews point at a port nothing listens on,
 * so a page can't quietly load without it. WebRTC is switched off in pages (it could reveal your
 * address), and the app's own requests go through [Net].
 */
object Vpn {
    enum class State { OFF, CONNECTING, ON, FAILED }

    /** The user wants Orbit VPN on (remembered across restarts). */
    var enabled by mutableStateOf(false)
        private set
    var state by mutableStateOf(State.OFF)
        private set
    /** Connection progress while connecting, 0..100. */
    var progress by mutableIntStateOf(0)
        private set
    var step by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** A Tor exit address seen by Tor's check service: proof the route works (sites may see others). */
    var exitIp by mutableStateOf<String?>(null)
        private set
    var connectedAt by mutableLongStateOf(0L)
        private set
    /** False while a change of route hasn't reached WebView yet; pages wait for it. */
    var routeReady by mutableStateOf(true)
        private set

    /** The SOCKS port in use, or 0 while not connected. */
    @Volatile var socksPort = 0
        private set

    /** Called after traffic switches route (on, connected, off, new location), so open pages can reload. */
    var onRouteChanged: (() -> Unit)? = null
    /** Called whenever WebView has the route asked for; loads that waited can go. */
    var onRouteReady: (() -> Unit)? = null

    private lateinit var app: Context
    private lateinit var scope: CoroutineScope
    private val prefs get() = app.getSharedPreferences("device", Context.MODE_PRIVATE)
    private var service: TorService? = null
    private var bound = false
    private var ports = emptyList<Int>()
    private var portIndex = 0
    private var pollJob: Job? = null
    private var lastTorError: String? = null

    /** Why Orbit VPN can't run on this device, or null if it can. */
    var unsupported: String? = null
        private set

    fun init(context: Context, scope: CoroutineScope) {
        if (::app.isInitialized) return
        app = context.applicationContext
        this.scope = scope
        unsupported = checkSupport()
        val filter = IntentFilter().apply {
            addAction(TorService.ACTION_STATUS)
            addAction(TorService.ACTION_ERROR)
        }
        LocalBroadcastManager.getInstance(app).registerReceiver(receiver, filter)
        if (prefs.getBoolean("vpn", false) && unsupported == null) {
            enabled = true
            // Before any page loads: nothing may go out until the VPN is connected.
            applyRoute()
            start()
        }
    }

    private fun checkSupport(): String? {
        val abis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        if (Build.SUPPORTED_ABIS.none { it in abis }) return "Orbit VPN isn't available for this processor"
        val features = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)
        if (!features) return "Orbit VPN needs a newer Android System WebView. Update it from Google Play."
        return try {
            System.loadLibrary("tor")
            null
        } catch (e: Throwable) {
            "Orbit VPN couldn't start on this device"
        }
    }

    fun turnOn() {
        unsupported?.let { error = it; return }
        if (enabled && state != State.FAILED) return
        enabled = true
        prefs.edit().putBoolean("vpn", true).apply()
        error = null
        // Cut open pages off at once; they come back through the VPN when it's connected.
        applyRoute { onRouteChanged?.invoke() }
        start()
    }

    fun turnOff() {
        if (!enabled) return
        enabled = false
        prefs.edit().putBoolean("vpn", false).apply()
        stop()
        state = State.OFF
        error = null
        exitIp = null
        applyRoute { onRouteChanged?.invoke() }
    }

    /** Tries again after a failure. */
    fun retry() {
        if (!enabled) return turnOn()
        stop()
        error = null
        start()
    }

    /** New circuits, so sites see a different address. */
    fun newLocation() {
        if (state != State.ON) return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val control = service?.torControlConnection ?: return@runCatching false
                    control.signal("NEWNYM")
                    true
                }.getOrDefault(false)
            }
            if (!ok) return@launch
            // A different port means WebView opens new connections, which go over the new circuits.
            if (ports.size > 1) {
                portIndex = (portIndex + 1) % ports.size
                socksPort = ports[portIndex]
            }
            exitIp = null
            applyRoute {
                onRouteChanged?.invoke()
                checkAddress()
            }
        }
    }

    // ---- Tor ----

    private fun start() {
        state = State.CONNECTING
        progress = 0
        step = "Starting"
        socksPort = 0
        exitIp = null
        lastTorError = null
        writeTorrc()
        if (!bound) {
            bound = runCatching {
                app.bindService(Intent(app, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) return fail("Couldn't start Orbit VPN")
        }
        pollJob?.cancel()
        pollJob = scope.launch { watchBootstrap() }
    }

    private fun stop() {
        pollJob?.cancel()
        pollJob = null
        socksPort = 0
        ports = emptyList()
        if (bound) {
            runCatching { app.unbindService(connection) }
            bound = false
        }
        service = null
    }

    private fun writeTorrc() {
        runCatching {
            TorService.getTorrc(app).writeText(
                // Two SOCKS ports so "New location" can move WebView to fresh connections.
                "SOCKSPort auto\nSOCKSPort auto\nHTTPTunnelPort 0\nClientOnly 1\nAvoidDiskWrites 1\nSafeLogging 1\n",
            )
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? TorService.LocalBinder)?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            if (enabled) fail("Orbit VPN stopped")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TorService.ACTION_ERROR -> lastTorError = intent.getStringExtra(Intent.EXTRA_TEXT)
                TorService.ACTION_STATUS -> {
                    val status = intent.getStringExtra(TorService.EXTRA_STATUS)
                    if (!enabled || !bound) return
                    when (status) {
                        TorService.STATUS_ON -> if (state == State.CONNECTING) scope.launch { connected() }
                        // May also come late from an earlier run, so only believe it if Tor stopped answering.
                        TorService.STATUS_STOPPING -> scope.launch { delay(2000); verifyAlive() }
                    }
                }
            }
        }
    }

    /** Follows Tor's start-up, and gives up if it gets nowhere. */
    private suspend fun watchBootstrap() {
        var last = -1
        var lastMove = System.currentTimeMillis()
        while (scope.isActive && state == State.CONNECTING) {
            val phase = withContext(Dispatchers.IO) { runCatching { service?.getInfo("status/bootstrap-phase") }.getOrNull() }
            if (phase != null) {
                val p = Regex("PROGRESS=(\\d+)").find(phase)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val summary = Regex("SUMMARY=\"([^\"]*)\"").find(phase)?.groupValues?.get(1)
                if (p != last) { last = p; lastMove = System.currentTimeMillis() }
                progress = p.coerceAtMost(99)
                summary?.let { step = it.removeSuffix(".") }
                if (p >= 100) { connected(); return }
            }
            if (System.currentTimeMillis() - lastMove > 90_000L) {
                return fail("Couldn't connect to the Tor network. It may be blocked on this Wi-Fi or mobile network.")
            }
            delay(400)
        }
    }

    private suspend fun verifyAlive() {
        if (!enabled || state == State.FAILED || state == State.OFF) return
        val alive = withContext(Dispatchers.IO) { runCatching { service?.getInfo("version") }.getOrNull() } != null
        if (!alive && state == State.ON) fail("Orbit VPN stopped")
    }

    private suspend fun connected() {
        if (state != State.CONNECTING) return
        val listeners = withContext(Dispatchers.IO) { runCatching { service?.getInfo("net/listeners/socks") }.getOrNull() }
        val found = Regex(":(\\d+)").findAll(listeners.orEmpty()).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it > 0 }.toList()
        if (found.isEmpty()) return fail("Orbit VPN couldn't open a connection")
        ports = found
        portIndex = 0
        socksPort = found[0]
        pollJob?.cancel()
        applyRoute {
            state = State.ON
            progress = 100
            step = "Connected"
            connectedAt = System.currentTimeMillis()
            onRouteChanged?.invoke()
            checkAddress()
        }
    }

    private fun fail(message: String) {
        if (!enabled) return
        stop()
        state = State.FAILED
        error = lastTorError?.let { "$message ($it)" } ?: message
        // Still enabled: the route stays closed, so nothing loads without the VPN.
        applyRoute()
    }

    /** Asks Tor's own check service which address sites see, and that it's really Tor. */
    private fun checkAddress() {
        scope.launch {
            repeat(2) {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val conn = Net.open("https://check.torproject.org/api/ip", connectMs = 20_000, readMs = 20_000)
                        val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText().take(2000) })
                        o.optBoolean("IsTor") to o.optString("IP")
                    }.getOrNull()
                }
                if (state != State.ON) return@launch
                if (result != null) {
                    exitIp = result.second.takeIf { it.isNotBlank() && it.length < 64 }
                    if (!result.first) error = "Tor's check service doesn't see this as Tor traffic"
                    return@launch
                }
                delay(3000)
            }
        }
    }

    // ---- WebView route ----

    /**
     * Points every WebView at Tor (or, while not connected, at a closed port, so nothing goes
     * out directly), or back to a direct connection when the VPN is off.
     */
    private fun applyRoute(done: (() -> Unit)? = null) {
        if (!::app.isInitialized) return
        val controller = runCatching { ProxyController.getInstance() }.getOrNull() ?: run {
            routeReady = true
            done?.invoke()
            return
        }
        routeReady = false
        val main = ContextCompat.getMainExecutor(app)
        val applied = Runnable {
            routeReady = true
            onRouteReady?.invoke()
            done?.invoke()
        }
        runCatching {
            if (enabled) {
                val port = socksPort
                // Port 1 can't be opened by apps, so while connecting every request fails instead of leaking.
                val rule = "socks5://127.0.0.1:" + (if (port > 0) port else 1)
                controller.setProxyOverride(ProxyConfig.Builder().addProxyRule(rule).build(), main, applied)
            } else {
                controller.clearProxyOverride(main, applied)
            }
        }.onFailure {
            routeReady = true
            if (enabled) {
                state = State.FAILED
                error = "This WebView can't route traffic through Orbit VPN"
            }
        }
    }
}

/**
 * Network requests Orbit makes itself (icons, suggestions, filter lists, updates, sync). With
 * Orbit VPN on they go through it and never directly: they fail while it isn't connected.
 */
object Net {
    fun open(url: String, connectMs: Int, readMs: Int): HttpURLConnection {
        val u = URL(url)
        val conn = if (Vpn.enabled) {
            val port = Vpn.socksPort
            if (port <= 0) throw IOException("Orbit VPN isn't connected")
            // The host name goes to Tor unresolved, so DNS lookups don't leave the device either.
            u.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        } else {
            u.openConnection()
        } as HttpURLConnection
        val slow = if (Vpn.enabled) 3 else 1
        conn.connectTimeout = connectMs * slow
        conn.readTimeout = readMs * slow
        return conn
    }
}
