package app.orbit.core

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

data class Notice(
    val text: String,
    val action: String? = null,
    val onAction: (() -> Unit)? = null,
    val id: Long = System.nanoTime(),
)

data class LinkTarget(val url: String?, val image: String?, val tab: Tab)

private data class Closed(val url: String, val title: String, val spaceId: String)

/**
 * The browser engine: owns tabs, spaces, WebViews, history and all page-level features.
 * Lives as long as the activity (config changes are handled in-place, see manifest).
 */
@SuppressLint("SetJavaScriptEnabled")
class Browser(private val activity: ComponentActivity, private val scope: CoroutineScope) {

    /** Holds the profile list; also the data folder of the guest (no profile). */
    private val root = Store(activity, scope)
    private val stores = HashMap<String, Store>()
    /** The active profile's data. */
    private var store = root
    private val main = Handler(Looper.getMainLooper())
    val sync = GoogleSync(activity)

    val multiProfile: Boolean = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)
    private val docStartScripts = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false)

    // ---- persistent state ----
    val profiles = mutableStateListOf<UserProfile>()
    /** The active profile, or null for the guest. */
    var profileId by mutableStateOf<String?>(null)
        private set
    val profile: UserProfile? get() = profiles.firstOrNull { it.id == profileId }
    var syncing by mutableStateOf(false)
        private set
    /** Why the active profile's last sync failed, or null if it worked. */
    var syncError by mutableStateOf<String?>(null)
        private set
    private var pushJob: Job? = null
    /** True while remote data is being applied, so it isn't reported as a local change. */
    private var applyingRemote = false
    val spaces = mutableStateListOf<Space>()
    val tabs = mutableStateListOf<Tab>()
    val history = mutableStateListOf<HistoryEntry>()
    val pins = mutableStateListOf<Pin>()
    /** site -> CSS selectors the user zapped away. */
    val zaps = mutableStateMapOf<String, List<String>>()
    /** sites where the user switched shields off. */
    val shieldsOff = mutableStateListOf<String>()
    var settings by mutableStateOf(Settings())
        private set
    var totalBlocked by mutableLongStateOf(0L)
        private set
    var flowMinutesTotal by mutableIntStateOf(0)
        private set

    // ---- live state ----
    var currentId by mutableStateOf<String?>(null)
        private set
    var currentSpaceId by mutableStateOf(Space.DEFAULT_ID)
        private set
    val current: Tab? by derivedStateOf { tabs.firstOrNull { it.id == currentId } }
    val currentSpace: Space by derivedStateOf { spaces.firstOrNull { it.id == currentSpaceId } ?: spaces.firstOrNull() ?: Space.Defaults[0] }

    var now by mutableLongStateOf(System.currentTimeMillis())
        private set
    var flowUntil by mutableLongStateOf(0L)
        private set
    var flowStartedAt by mutableLongStateOf(0L)
        private set
    private val flowBypass = HashMap<String, Long>()
    val flowActive: Boolean get() = now < flowUntil

    var barCollapsed by mutableStateOf(false)
    var notice by mutableStateOf<Notice?>(null)
        private set
    var peek by mutableStateOf<Tab?>(null)
        private set
    var linkMenu by mutableStateOf<LinkTarget?>(null)
    var reader by mutableStateOf<ReaderDoc?>(null)
    var findOpen by mutableStateOf(false)
    var findCurrent by mutableIntStateOf(0)
        private set
    var findTotal by mutableIntStateOf(0)
        private set
    var customView by mutableStateOf<View?>(null)
        private set
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    var foreground = true

    // ---- media: what's playing, for the notification and background playback ----
    /** The tab whose media the notification controls, and whether it's playing right now. */
    var mediaTabId by mutableStateOf<String?>(null)
        private set
    var mediaPlaying by mutableStateOf(false)
        private set
    /** The frame that reported the media; commands go back to it (it may be an embedded player). */
    private var mediaReply: JavaScriptReplyProxy? = null
    private var mediaArtUrl: String? = null
    private var mediaArt: Bitmap? = null
    private var lastMedia: JSONObject? = null

    /** Set by the activity: asks once for permission to show the media notification. */
    var askNotifications: (() -> Unit)? = null
    /** Set by the activity: launches a file picker and reports the chosen URIs. */
    var fileChooser: ((Intent, (Array<Uri>?) -> Unit) -> Unit)? = null

    private val closed = ArrayDeque<Closed>()
    private val defaultUa: String = runCatching { WebSettings.getDefaultUserAgent(activity) }.getOrDefault("")
    private val mobileUa = defaultUa.replace("; wv)", ")").replace(Regex("Version/\\d+\\.\\d+ "), "")
    private val desktopUa = run {
        val v = Regex("Chrome/([\\d.]+)").find(defaultUa)?.groupValues?.get(1) ?: "130.0.0.0"
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$v Safari/537.36"
    }

    // =====================================================================================
    // Lifecycle & persistence
    // =====================================================================================

    fun load() {
        Images.init(activity)
        // Before any WebView loads: with Orbit VPN on, nothing may go out until it's connected.
        Vpn.onRouteReady = ::flushPendingLoads
        Vpn.onRouteChanged = ::afterRouteChange
        Vpn.init(activity, scope)
        Shields.init(activity, scope)
        MediaCenter.onCommand = ::mediaCommand
        root.readObject("profiles")?.let { o ->
            o.optJSONArray("list")?.let { a -> for (i in 0 until a.length()) profiles += UserProfile.fromJson(a.getJSONObject(i)) }
            profileId = o.optString("active").takeIf { id -> profiles.any { it.id == id } }
        }
        store = storeFor(profileId)
        loadData()
        // Ghost tabs never survive a restart, so any ghost data left by a killed process goes now.
        wipeGhosts()

        scope.launch {
            while (true) {
                delay(1000)
                tick()
            }
        }
        syncNow()
        // Widgets show the active profile's favorites and whether Orbit VPN is on.
        scope.launch {
            snapshotFlow { pins.toList() to Vpn.enabled }.collect { (p, vpn) ->
                withContext(Dispatchers.IO) { runCatching { Widgets.publish(activity, p, vpn) } }
            }
        }
        Updates.init(activity)
        Updates.checkIfDue(scope)
    }

    private fun storeFor(id: String?): Store = if (id == null) root else stores.getOrPut(id) { Store(activity, scope, "p/$id") }

    /** Reads the active profile's data from [store]. */
    private fun loadData() {
        store.readObject("settings")?.let { settings = Settings.fromJson(it) }
        val savedSpaces = store.readArray("spaces")?.let { a -> List(a.length()) { Space.fromJson(a.getJSONObject(it)) } }
        spaces.addAll(savedSpaces?.takeIf { it.isNotEmpty() } ?: Space.Defaults)
        store.readArray("history")?.let { a -> for (i in 0 until a.length()) history += HistoryEntry.fromJson(a.getJSONObject(i)) }
        store.readArray("pins")?.let { a -> for (i in 0 until a.length()) pins += Pin.fromJson(a.getJSONObject(i)) }
        store.readObject("zaps")?.let { o ->
            o.keys().forEach { k -> val a = o.getJSONArray(k); zaps[k] = List(a.length()) { a.getString(it) } }
        }
        store.readObject("stats")?.let {
            totalBlocked = it.optLong("blocked")
            flowMinutesTotal = it.optInt("flow")
            it.optJSONArray("shieldsOff")?.let { a -> for (i in 0 until a.length()) shieldsOff += a.getString(i) }
        }
        store.readObject("session")?.let { o ->
            currentSpaceId = o.optString("space", Space.DEFAULT_ID).takeIf { id -> spaces.any { it.id == id } } ?: spaces.first().id
            val a = o.optJSONArray("tabs") ?: JSONArray()
            for (i in 0 until a.length()) {
                val s = SavedTab.fromJson(a.getJSONObject(i))
                if (spaces.none { it.id == s.spaceId }) continue
                tabs += Tab(id = s.id, spaceId = s.spaceId, url = s.url, title = s.title)
            }
            currentId = o.optString("current").takeIf { id -> tabs.any { it.id == id } }
        }
        if (currentId == null) {
            val t = tabs.lastOrNull { it.spaceId == currentSpaceId } ?: newTab(select = false)
            currentId = t.id
        }
        // The current tab's WebView is created by the UI once it is attached (see BrowserScreen).
    }

    private fun tick() {
        now = System.currentTimeMillis()
        val cur = current
        if (foreground && cur != null) cur.lastActive = now
        // Ghost tabs self-destruct after a period without being looked at.
        val ttl = settings.ghostMinutes * 60_000L
        tabs.filter { it.ghost && (it !== cur || !foreground) && now - it.lastActive > ttl }
            .forEach { closeTab(it, undoable = false) }
        if (flowUntil != 0L && now >= flowUntil) {
            val mins = ((flowUntil - flowStartedAt) / 60_000L).toInt()
            flowMinutesTotal += mins
            flowUntil = 0L
            saveStats()
            notify("Focus session finished")
        }
    }

    fun saveSession() {
        val saved = tabs.filter { !it.ghost }.map { SavedTab(it.id, it.spaceId, if (it.showHome) "" else it.url, it.title) }
        val cur = currentId
        val space = currentSpaceId
        store.write("session") {
            val arr = JSONArray().apply { saved.forEach { put(it.toJson()) } }
            JSONObject().put("tabs", arr).put("current", cur).put("space", space).toString()
        }
    }

    private fun saveSpaces() {
        val snap = spaces.toList()
        store.write("spaces") { JSONArray().apply { snap.forEach { put(it.toJson()) } }.toString() }
        changed()
    }

    private fun saveHistory() {
        val snap = history.toList()
        store.write("history", 1500) { JSONArray().apply { snap.forEach { put(it.toJson()) } }.toString() }
        changed()
    }

    private fun savePins() {
        val snap = pins.toList()
        store.write("pins") { JSONArray().apply { snap.forEach { put(it.toJson()) } }.toString() }
        changed()
    }

    private fun saveZaps() {
        val snap = zaps.toMap()
        store.write("zaps") { JSONObject().apply { snap.forEach { (k, v) -> put(k, JSONArray(v)) } }.toString() }
        changed()
    }

    private fun saveStats() {
        val blocked = totalBlocked
        val flow = flowMinutesTotal
        val off = shieldsOff.toList()
        store.write("stats", 2000) { JSONObject().put("blocked", blocked).put("flow", flow).put("shieldsOff", JSONArray(off)).toString() }
    }

    /** Shows or hides Satellite, the favorites handle on the edge of the screen. */
    fun toggleSatellite() {
        updateSettings(settings.copy(satellite = !settings.satellite))
        notify(if (settings.satellite) "Satellite on · drag it to move it" else "Satellite off")
    }

    fun updateSettings(s: Settings) {
        settings = s
        store.write("settings") { s.toJson().toString() }
        tabs.forEach { t -> t.webView?.let { applyWebSettings(it, t) } }
        syncAllScripts()
        changed()
    }

    /** Uses a picked image as this profile's start page wallpaper. */
    fun setWallpaper(uri: Uri) {
        val id = profileId
        scope.launch {
            if (Wallpapers.save(activity, uri, id) && id == profileId) {
                updateSettings(settings.copy(wallpaper = System.currentTimeMillis()))
            } else if (id == profileId) notify("Couldn't use that image")
        }
    }

    fun removeWallpaper() {
        Wallpapers.delete(activity, profileId)
        updateSettings(settings.copy(wallpaper = 0L))
    }

    fun onPause() {
        foreground = false
        current?.let { if (!keepsPlaying(it)) it.webView?.onPause() }
        saveSession()
        // Don't leave changes waiting for the debounce: the app may not come back.
        if (pushJob?.isActive == true) syncNow()
    }

    fun onResume() {
        foreground = true
        Updates.onResume(scope)
        Shields.updateIfDue(scope)
        current?.let { it.lastActive = System.currentTimeMillis(); it.webView?.onResume() }
        tick()
    }

    fun notify(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        notice = Notice(text, action, onAction)
    }

    fun dismissNotice(n: Notice) {
        if (notice?.id == n.id) notice = null
    }

    // =====================================================================================
    // Tabs
    // =====================================================================================

    fun tabsIn(spaceId: String): List<Tab> = tabs.filter { !it.ghost && it.spaceId == spaceId }
    val ghostTabs: List<Tab> get() = tabs.filter { it.ghost }

    private fun groupOf(tab: Tab) = if (tab.ghost) ghostTabs else tabsIn(tab.spaceId)

    fun newTab(
        url: String = "",
        spaceId: String = currentSpaceId,
        ghost: Boolean = false,
        select: Boolean = true,
        parent: Tab? = null,
    ): Tab {
        val tab = Tab(spaceId = spaceId, ghost = ghost, url = url, parentId = parent?.id)
        val parentIndex = parent?.let { tabs.indexOf(it) } ?: -1
        if (parentIndex >= 0) tabs.add(parentIndex + 1, tab) else tabs.add(tab)
        if (ghost && !multiProfile) notify("Ghost tabs can't get their own cookies on this WebView version")
        if (select) select(tab) else if (url.isNotEmpty()) tab.title = Url.pretty(url)
        saveSession()
        return tab
    }

    fun select(tab: Tab) {
        val prev = current
        if (prev === tab) {
            if (!tab.showHome) ensureWebView(tab)
            return
        }
        prev?.let { captureThumbnail(it); if (!keepsPlaying(it)) it.webView?.onPause() }
        currentId = tab.id
        if (!tab.ghost) currentSpaceId = tab.spaceId
        tab.lastActive = System.currentTimeMillis()
        if (!tab.showHome) ensureWebView(tab)
        tab.webView?.onResume()
        barCollapsed = false
        findOpen = false
        hibernateIdle()
        saveSession()
    }

    /** Switch to the neighbouring tab within the current group; returns false at the edges. */
    fun switchRelative(delta: Int): Boolean {
        val cur = current ?: return false
        val group = groupOf(cur)
        val target = group.getOrNull(group.indexOf(cur) + delta) ?: return false
        select(target)
        return true
    }

    fun closeTab(tab: Tab, undoable: Boolean = true) {
        val group = groupOf(tab)
        val idx = group.indexOf(tab)
        val wasCurrent = tab.id == currentId
        if (!tab.ghost && !tab.showHome && tab.url.isNotBlank()) {
            closed.addFirst(Closed(tab.url, tab.title, tab.spaceId))
            while (closed.size > 15) closed.removeLast()
        }
        tabs.remove(tab)
        destroyWebView(tab)
        if (wasCurrent) {
            val rest = group - tab
            val next = rest.firstOrNull { it.id == tab.parentId }
                ?: rest.getOrNull(idx) ?: rest.getOrNull(idx - 1)
                ?: tabsIn(currentSpaceId).maxByOrNull { it.lastActive }
            if (next != null) select(next) else newTab()
        }
        if (tab.ghost && ghostTabs.isEmpty()) wipeGhosts()
        if (undoable && !tab.ghost && !tab.showHome) {
            notify("Tab closed", "Undo") { reopenClosed() }
        }
        saveSession()
    }

    fun closeAll(spaceId: String?, ghosts: Boolean) {
        val victims = if (ghosts) ghostTabs else tabsIn(spaceId ?: currentSpaceId)
        victims.forEach { closeTab(it, undoable = false) }
        if (ghosts) notify("Ghost tabs closed")
    }

    fun reopenClosed() {
        val c = closed.removeFirstOrNull() ?: return notify("Nothing to reopen")
        val spaceId = if (spaces.any { it.id == c.spaceId }) c.spaceId else currentSpaceId
        newTab(c.url, spaceId = spaceId).also { it.title = c.title }
    }

    fun burnGhosts() = closeAll(null, ghosts = true)

    // =====================================================================================
    // Spaces
    // =====================================================================================

    fun switchSpace(id: String) {
        if (spaces.none { it.id == id }) return
        currentSpaceId = id
        val target = tabsIn(id).maxByOrNull { it.lastActive }
        if (target != null) select(target) else newTab(spaceId = id)
        saveSession()
    }

    fun addSpace(name: String, icon: String): Space {
        val s = Space(UUID.randomUUID().toString().take(8), name.ifBlank { "Space" }, icon)
        spaces += s
        saveSpaces()
        return s
    }

    fun updateSpace(space: Space) {
        val i = spaces.indexOfFirst { it.id == space.id }
        if (i >= 0) spaces[i] = space
        saveSpaces()
    }

    fun deleteSpace(id: String) {
        if (spaces.size <= 1) return notify("You need at least one space")
        tabsIn(id).forEach { closeTab(it, undoable = false) }
        spaces.removeAll { it.id == id }
        history.removeAll { it.spaceId == id }
        if (currentSpaceId == id) switchSpace(spaces.first().id)
        if (multiProfile) main.postDelayed({ runCatching { ProfileStore.getInstance().deleteProfile(profileName(id, false)) } }, 800)
        saveSpaces(); saveHistory(); saveSession()
    }

    fun moveTab(tab: Tab, spaceId: String) {
        if (tab.ghost || tab.spaceId == spaceId) return
        // A WebView's profile is fixed, so moving a tab re-creates it in the new space's jar.
        val url = tab.url
        val wasHome = tab.showHome
        destroyWebView(tab)
        tab.savedState = null
        tab.spaceId = spaceId
        if (!wasHome && url.isNotBlank() && tab.id == currentId) ensureWebView(tab)
        if (tab.id == currentId) currentSpaceId = spaceId
        saveSession()
        notify("Moved to ${spaces.firstOrNull { it.id == spaceId }?.name}")
    }

    private fun profileName(spaceId: String, ghost: Boolean) = if (ghost) "kv_ghost" else webProfile(profileId, spaceId)

    /** The WebView cookie jar for a space; every browser profile has its own set. */
    private fun webProfile(profile: String?, spaceId: String) = when {
        profile != null -> "kv_p${profile}_$spaceId"
        spaceId == Space.DEFAULT_ID -> Profile.DEFAULT_PROFILE_NAME
        else -> "kv_space_$spaceId"
    }

    private fun cookiesFor(tab: Tab?): CookieManager =
        if (multiProfile && tab != null) {
            runCatching { ProfileStore.getInstance().getOrCreateProfile(profileName(tab.spaceId, tab.ghost)).cookieManager }
                .getOrDefault(CookieManager.getInstance())
        } else CookieManager.getInstance()

    private fun wipeGhosts() {
        if (!multiProfile) return
        main.postDelayed({
            val store = ProfileStore.getInstance()
            val deleted = runCatching { store.deleteProfile("kv_ghost") }.getOrDefault(false)
            if (!deleted) runCatching {
                store.getProfile("kv_ghost")?.let { p ->
                    p.cookieManager.removeAllCookies(null)
                    p.webStorage.deleteAllData()
                }
            }
        }, 600)
    }

    fun clearSpaceData(spaceId: String) {
        history.removeAll { it.spaceId == spaceId }
        saveHistory()
        if (multiProfile) {
            runCatching {
                val p = ProfileStore.getInstance().getOrCreateProfile(profileName(spaceId, false))
                p.cookieManager.removeAllCookies(null)
                p.webStorage.deleteAllData()
            }
        } else {
            CookieManager.getInstance().removeAllCookies(null)
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
        tabsIn(spaceId).forEach { it.webView?.clearCache(true) }
        notify("Data cleared")
    }

    // =====================================================================================
    // Profiles & Google sync
    // =====================================================================================

    private fun saveProfiles() {
        val snap = profiles.toList()
        val active = profileId
        root.write("profiles") {
            JSONObject().put("active", active).put("list", JSONArray().apply { snap.forEach { put(it.toJson()) } }).toString()
        }
    }

    private fun updateProfile(id: String, change: (UserProfile) -> UserProfile) {
        val i = profiles.indexOfFirst { it.id == id }
        if (i < 0) return
        profiles[i] = change(profiles[i])
        saveProfiles()
    }

    private fun newProfileId() = UUID.randomUUID().toString().take(8)

    fun addLocalProfile(name: String) {
        val p = UserProfile(newProfileId(), name.ifBlank { "Profile" })
        profiles += p
        saveProfiles()
        switchProfile(p.id)
    }

    /** Picks a Google account, asks for Drive access and opens (or restores) its profile. */
    fun addGoogleProfile() {
        scope.launch {
            val email = sync.pickAccount() ?: return@launch
            profiles.firstOrNull { it.email.equals(email, ignoreCase = true) }?.let {
                if (it.id == profileId) syncNow(interactive = true) else switchProfile(it.id, interactiveSync = true)
                return@launch
            }
            try {
                val token = sync.token(email, interactive = true) ?: return@launch notify("Google access wasn't allowed")
                val (name, photo) = sync.accountInfo(token)
                val p = UserProfile(newProfileId(), name ?: email.substringBefore('@'), email, photo)
                profiles += p
                saveProfiles()
                // The first sync restores a profile saved from another device, and says if anything is wrong.
                switchProfile(p.id, interactiveSync = true)
            } catch (e: SyncException) {
                notify(e.message ?: "Couldn't sign in")
            } catch (e: Exception) {
                notify("Couldn't sign in")
            }
        }
    }

    fun renameProfile(id: String, name: String) = updateProfile(id) { it.copy(name = name.ifBlank { it.name }) }

    /** Removes a profile and its data from this device. Its Google copy stays, so it can be added again. */
    fun removeProfile(id: String) {
        val p = profiles.firstOrNull { it.id == id } ?: return
        if (profileId == id) switchProfile(null)
        val s = storeFor(id)
        val spaceIds = s.readArray("spaces")?.let { a -> List(a.length()) { a.getJSONObject(it).optString("id") } }
            ?: Space.Defaults.map { it.id }
        s.wipe()
        stores.remove(id)
        Wallpapers.delete(activity, id)
        profiles.remove(p)
        saveProfiles()
        if (multiProfile) main.postDelayed({
            spaceIds.forEach { sid -> runCatching { ProfileStore.getInstance().deleteProfile(webProfile(id, sid)) } }
        }, 800)
        notify("${p.name} removed from this device")
    }

    /**
     * Switches to another profile (null = guest): every tab, space and setting is swapped out.
     * With [interactiveSync], Google may ask to sign in again and the sync's outcome is shown.
     */
    fun switchProfile(id: String?, interactiveSync: Boolean = false) {
        if (id == profileId || (id != null && profiles.none { it.id == id })) return
        if (pushJob?.isActive == true) syncNow()
        saveSession()
        closePeek()
        exitFullscreen()
        closeFind()
        reader = null
        linkMenu = null
        tabs.forEach { destroyWebView(it) }
        tabs.clear(); spaces.clear(); history.clear(); pins.clear(); zaps.clear(); shieldsOff.clear(); closed.clear()
        wipeGhosts()
        flowUntil = 0L; flowBypass.clear()
        settings = Settings(); totalBlocked = 0L; flowMinutesTotal = 0
        currentId = null; currentSpaceId = Space.DEFAULT_ID
        profileId = id
        syncError = null
        saveProfiles()
        store = storeFor(id)
        loadData()
        notify(profile?.let { "Switched to ${it.name}" } ?: "Browsing as guest")
        syncNow(interactiveSync)
    }

    /** Local data that's worth backing up changed; send it to Google a little later. */
    private fun changed() {
        if (applyingRemote) return
        val p = profile ?: return
        updateProfile(p.id) { it.copy(changedAt = System.currentTimeMillis()) }
        if (!p.google) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(20_000)
            syncNow()
        }
    }

    /**
     * Syncs the active Google profile: the newer side wins. With [interactive], Google may ask
     * the user to sign in again, and the outcome is shown.
     */
    fun syncNow(interactive: Boolean = false) {
        val p = profile?.takeIf { it.google } ?: return
        if (syncing) {
            if (interactive) notify("Already syncing")
            return
        }
        pushJob?.cancel()
        // Snapshot now: the user may switch profiles before the network answers.
        val local = syncBundle(p.changedAt)
        syncing = true
        scope.launch {
            var pulled = false
            fun failed(reason: String) {
                if (profileId == p.id) syncError = reason
                if (interactive) notify(reason)
            }
            try {
                var token = sync.token(p.email!!, interactive)
                if (token == null) {
                    if (profileId == p.id) syncError = "Sign in again to sync"
                    notify("Sign in to sync ${p.name}", "Sign in") { syncNow(interactive = true) }
                    return@launch
                }
                val remote = try {
                    sync.download(token)
                } catch (e: DriveException) {
                    // A token Google stopped accepting: drop it and ask for a new one, once.
                    if (e.code != 401) throw e
                    sync.clearToken(token)
                    token = sync.token(p.email, interactive) ?: throw e
                    sync.download(token)
                }
                val remoteAt = remote?.optLong("updated") ?: 0L
                when {
                    remote != null && remoteAt > p.changedAt -> if (profileId == p.id) {
                        pulled = true
                        applySyncBundle(remote)
                        updateProfile(p.id) { it.copy(changedAt = remoteAt, syncedAt = System.currentTimeMillis()) }
                    }
                    remote == null || p.changedAt > remoteAt -> {
                        sync.upload(token, local)
                        updateProfile(p.id) { it.copy(syncedAt = System.currentTimeMillis()) }
                    }
                    else -> updateProfile(p.id) { it.copy(syncedAt = System.currentTimeMillis()) }
                }
                if (profileId == p.id) syncError = null
                if (p.photo == null) sync.accountInfo(token).second?.let { photo -> updateProfile(p.id) { it.copy(photo = photo) } }
                if (interactive) notify(if (pulled) "${p.name} restored from Google" else "${p.name} is up to date")
            } catch (e: SyncException) {
                failed(e.message ?: "Couldn't sync")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(if (e is java.io.IOException) "No connection to Google" else "Couldn't sync")
            } finally {
                syncing = false
                // Changed again while uploading: go round once more, later.
                val latest = profiles.firstOrNull { it.id == p.id }
                if (!pulled && profileId == p.id && latest != null && latest.changedAt > p.changedAt && pushJob?.isActive != true) changed()
            }
        }
    }

    /** What's saved to Google: everything except open tabs, logins and counters. */
    private fun syncBundle(updated: Long): JSONObject = JSONObject()
        .put("v", 1)
        .put("updated", updated)
        .put("settings", settings.toJson())
        .put("spaces", JSONArray().apply { spaces.forEach { put(it.toJson()) } })
        .put("pins", JSONArray().apply { pins.forEach { put(it.toJson()) } })
        .put("zaps", JSONObject().apply { zaps.forEach { (k, v) -> put(k, JSONArray(v)) } })
        .put("shieldsOff", JSONArray(shieldsOff.toList()))
        .put("history", JSONArray().apply { history.take(SYNCED_HISTORY).forEach { put(it.toJson()) } })

    private fun applySyncBundle(o: JSONObject) {
        applyingRemote = true
        try {
            // The wallpaper image stays on each device, so keep this device's choice.
            o.optJSONObject("settings")?.let { updateSettings(Settings.fromJson(it).copy(wallpaper = settings.wallpaper)) }
            o.optJSONArray("spaces")?.let { a ->
                val remote = List(a.length()) { Space.fromJson(a.getJSONObject(it)) }
                if (remote.isEmpty()) return@let
                // Keep local spaces that still have open tabs, so no tab is orphaned.
                val keep = spaces.filter { s -> remote.none { it.id == s.id } && tabsIn(s.id).isNotEmpty() }
                spaces.clear()
                spaces.addAll(remote + keep)
                if (spaces.none { it.id == currentSpaceId }) currentSpaceId = spaces.first().id
                saveSpaces()
            }
            o.optJSONArray("pins")?.let { a ->
                pins.clear()
                for (i in 0 until a.length()) pins += Pin.fromJson(a.getJSONObject(i))
                savePins()
            }
            o.optJSONObject("zaps")?.let { z ->
                zaps.clear()
                z.keys().forEach { k -> val a = z.getJSONArray(k); zaps[k] = List(a.length()) { a.getString(it) } }
                saveZaps()
            }
            o.optJSONArray("shieldsOff")?.let { a ->
                shieldsOff.clear()
                for (i in 0 until a.length()) shieldsOff += a.getString(i)
                saveStats()
            }
            o.optJSONArray("history")?.let { a ->
                val merged = (List(a.length()) { HistoryEntry.fromJson(a.getJSONObject(it)) } + history)
                    .distinctBy { it.url to it.time }
                    .sortedByDescending { it.time }
                    .take(3000)
                history.clear()
                history.addAll(merged)
                saveHistory()
            }
        } finally {
            applyingRemote = false
        }
    }

    // =====================================================================================
    // Navigation
    // =====================================================================================

    fun navigate(input: String, tab: Tab? = current) {
        val t = tab ?: newTab()
        val url = Url.resolve(input, settings.engine)
        if (url.isBlank()) return
        if (!flowAllows(url)) {
            t.flowBlocked = url
            return
        }
        val target = linkFor(url)?.also { if (it.startsWith("https://") && url.startsWith("http://")) t.upgradedHost = Url.host(it) } ?: url
        t.showHome = false
        t.url = target
        t.pageHost = Url.host(target)
        val wv = ensureWebView(t, loadInitial = false)
        loadIn(t, wv, target)
        barCollapsed = false
    }

    /** Plain-http sites the user chose to open without https while Orbit VPN is on (this session). */
    private val httpAllowed = HashSet<String>()

    /**
     * What a main-frame [url] should become, or null if it's fine: without tracking parameters
     * (Shields), and over https while Orbit VPN is on, since a relay could read or change plain
     * http. Onion sites, local addresses and sites allowed by the user stay as they are.
     */
    private fun linkFor(url: String): String? {
        var out = url
        if (Vpn.enabled && out.startsWith("http://")) {
            val host = Url.host(out)
            val local = host == null || host == "localhost" || host.endsWith(".onion") || host.contains(':') ||
                host.all { it.isDigit() || it == '.' } || host in httpAllowed
            if (!local) out = "https://" + out.removePrefix("http://")
        }
        val site = Url.host(out)?.let(Url::site)
        if (settings.shields && settings.cleanLinks && (site == null || site !in shieldsOff)) Shields.cleanUrl(out)?.let { out = it }
        return out.takeIf { it != url }
    }

    /** The https version of [host] failed: offer the page over plain http, as the user's choice. */
    private fun offerHttp(tab: Tab, host: String, url: String) {
        tab.upgradedHost = null
        val plain = "http://" + url.substringAfter("://")
        notify("$host has no secure version", "Open anyway") {
            httpAllowed += host
            navigate(plain, tab)
        }
    }

    /** Loads [url], unless Orbit VPN's route isn't in place yet; then it waits for it. */
    private fun loadIn(tab: Tab, wv: WebView, url: String) {
        if (Vpn.routeReady) wv.loadUrl(url) else tab.pendingUrl = url
    }

    private fun flushPendingLoads() {
        (tabs + listOfNotNull(peek)).forEach { t ->
            val wv = t.webView ?: return@forEach
            val url = t.pendingUrl ?: return@forEach
            t.pendingUrl = null
            if (url == RESTORE) {
                val state = t.savedState
                t.savedState = null
                val restored = state?.let { runCatching { wv.restoreState(it) }.getOrNull() }
                if (restored == null && t.url.isNotBlank()) wv.loadUrl(t.url)
            } else wv.loadUrl(url)
        }
    }

    /** Orbit VPN switched route: pages reload so they use the new one (and nothing keeps the old). */
    private fun afterRouteChange() {
        syncAllScripts()
        (tabs + listOfNotNull(peek)).forEach { t -> if (!t.showHome && t.pendingUrl == null) t.webView?.reload() }
    }

    fun goBack(tab: Tab? = current): Boolean {
        val t = tab ?: return false
        if (t.showHome) return false
        val wv = t.webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack(); return true
        }
        if (t.startedFromHome) {
            t.showHome = true
            return true
        }
        return false
    }

    fun goForward(tab: Tab? = current) {
        val t = tab ?: return
        if (t.showHome && t.webView != null && t.url.isNotBlank()) {
            t.showHome = false; return
        }
        t.webView?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reload(tab: Tab? = current) {
        tab?.takeIf { !it.showHome }?.webView?.reload()
    }

    fun goHome(tab: Tab? = current) {
        tab?.showHome = true
    }

    // =====================================================================================
    // Flow mode
    // =====================================================================================

    fun startFlow(minutes: Int) {
        flowStartedAt = System.currentTimeMillis()
        flowUntil = flowStartedAt + minutes * 60_000L
        flowBypass.clear()
        now = flowStartedAt
        notify("Focus on for $minutes minutes")
        current?.let { t ->
            if (!t.showHome && !flowAllows(t.url)) t.flowBlocked = t.url
        }
    }

    fun endFlow() {
        if (flowUntil == 0L) return
        val mins = ((System.currentTimeMillis() - flowStartedAt) / 60_000L).toInt()
        flowMinutesTotal += mins
        flowUntil = 0L
        saveStats()
        notify("Focus ended")
    }

    fun flowAllows(url: String): Boolean {
        if (!flowActive) return true
        if (!Url.matchesDomain(url, settings.flowDomains)) return true
        val site = Url.host(url)?.let(Url::site) ?: return true
        return (flowBypass[site] ?: 0L) > System.currentTimeMillis()
    }

    fun allowFlowBypass(tab: Tab, minutes: Int = 5) {
        val url = tab.flowBlocked ?: return
        Url.host(url)?.let { flowBypass[Url.site(it)] = System.currentTimeMillis() + minutes * 60_000L }
        tab.flowBlocked = null
        navigate(url, tab)
    }

    // =====================================================================================
    // Page tools
    // =====================================================================================

    fun toggleDesktop(tab: Tab? = current) {
        val t = tab ?: return
        t.desktop = !t.desktop
        t.webView?.let { applyWebSettings(it, t); it.reload() }
        notify(if (t.desktop) "Showing desktop site" else "Showing mobile site")
    }

    fun toggleZap(tab: Tab? = current) {
        val t = tab ?: return
        val wv = t.webView ?: return
        t.zapMode = !t.zapMode
        wv.evaluateJavascript(if (t.zapMode) Scripts.ZAP_ON else Scripts.ZAP_OFF, null)
        if (t.zapMode) notify("Tap something on the page to hide it")
    }

    private fun addZap(tab: Tab, selector: String) {
        val site = tab.pageHost?.let(Url::site) ?: return
        zaps[site] = ((zaps[site] ?: emptyList()) + selector).distinct()
        saveZaps()
    }

    fun removeZap(site: String, selector: String?) {
        val left = if (selector == null) emptyList() else (zaps[site] ?: emptyList()) - selector
        if (left.isEmpty()) zaps.remove(site) else zaps[site] = left
        saveZaps()
        tabs.filter { it.pageHost?.let(Url::site) == site }.forEach { injectPageStyles(it) }
    }

    /** The filter lists in use for [site], as [Shields] bits (0 = Shields off there). */
    fun shieldLists(site: String?): Int =
        if (!settings.shields || (site != null && site in shieldsOff)) 0 else Shields.lists(settings)

    fun shieldsOn(tab: Tab?): Boolean {
        val site = tab?.pageHost?.let(Url::site) ?: return settings.shields
        return settings.shields && site !in shieldsOff
    }

    fun toggleSiteShields(tab: Tab) {
        val site = tab.pageHost?.let(Url::site) ?: return
        if (site in shieldsOff) shieldsOff.remove(site) else shieldsOff.add(site)
        saveStats()
        changed()
        syncAllScripts()
        tab.webView?.reload()
    }

    fun openReader(tab: Tab? = current) {
        val t = tab ?: return
        val wv = t.webView ?: return
        wv.evaluateJavascript(Scripts.READER) { raw ->
            val doc = ReaderDoc.parse(Scripts.unwrap(raw), t.url)
            if (doc != null) reader = doc else notify("Reader isn't available for this page")
        }
    }

    fun find(query: String) {
        val wv = current?.webView ?: return
        if (query.isEmpty()) {
            wv.clearMatches(); findTotal = 0; findCurrent = 0
        } else wv.findAllAsync(query)
    }

    fun findNext(forward: Boolean) = current?.webView?.findNext(forward)

    fun closeFind() {
        findOpen = false
        current?.webView?.clearMatches()
        findTotal = 0; findCurrent = 0
    }

    fun share(tab: Tab? = current) {
        val t = tab ?: return
        if (t.showHome) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, t.url)
            putExtra(Intent.EXTRA_SUBJECT, t.title)
        }
        runCatching { activity.startActivity(Intent.createChooser(send, t.title)) }
    }

    fun copy(text: String, label: String = "Link copied") {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("link", text))
        notify(label)
    }

    fun togglePin(url: String, title: String, quiet: Boolean = false) {
        val existing = pins.indexOfFirst { it.url.trimEnd('/') == url.trimEnd('/') }
        if (existing >= 0) {
            pins.removeAt(existing); if (!quiet) notify("Removed from favorites")
        } else {
            pins.add(0, Pin(url, title.ifBlank { Url.pretty(url) })); if (!quiet) notify("Added to favorites")
        }
        savePins()
    }

    fun isPinned(url: String) = pins.any { it.url.trimEnd('/') == url.trimEnd('/') }

    /** The sites on the start page orbit: your favorites, nothing added from history. */
    val orbitPins: List<Pin> get() = pins.take(ORBIT_SIZE)

    /** The orbit sites you picked for Satellite, at most [SATELLITE_SIZE]. */
    val satellitePins: List<Pin> get() = orbitPins.filter { it.satellite }.take(SATELLITE_SIZE)

    fun inSatellite(url: String) = pins.any { it.satellite && it.url.trimEnd('/') == url.trimEnd('/') }

    /** Adds an orbit site to Satellite or takes it off. Only sites on the orbit can be added. */
    fun toggleSatelliteSite(url: String, quiet: Boolean = false) {
        val i = pins.indexOfFirst { it.url.trimEnd('/') == url.trimEnd('/') }
        if (i < 0) return
        val p = pins[i]
        if (!p.satellite && satellitePins.size >= SATELLITE_SIZE) {
            notify("Satellite holds $SATELLITE_SIZE sites. Take one off first")
            return
        }
        pins[i] = p.copy(satellite = !p.satellite)
        savePins()
        if (quiet) return
        when {
            p.satellite -> notify("Removed from Satellite")
            settings.satellite -> notify("Added to Satellite")
            else -> notify("Added to Satellite", "Turn on") { toggleSatellite() }
        }
    }

    fun removeHistory(entry: HistoryEntry) {
        history.remove(entry); saveHistory()
    }

    /**
     * Removes a page from "Recently visited": every visit to it in its space, so an older visit
     * doesn't take its place. Can be undone.
     */
    fun removeRecent(entry: HistoryEntry) {
        val page = entry.url.substringBefore('#')
        val title = entry.title.takeIf { it.isNotBlank() }
        val removed = history.filter {
            it.spaceId == entry.spaceId && (it.url.substringBefore('#') == page || (title != null && it.title == title))
        }
        if (removed.isEmpty()) return
        history.removeAll(removed.toSet())
        saveHistory()
        notify("Removed from history", "Undo") { restoreHistory(removed) }
    }

    private fun restoreHistory(entries: List<HistoryEntry>) {
        val merged = (history + entries).distinct().sortedByDescending { it.time }
        history.clear()
        history.addAll(merged)
        saveHistory()
    }

    /** Empties "Recently visited" for a space. History itself is kept (search still finds it). */
    fun clearRecent(spaceId: String) {
        val space = spaces.firstOrNull { it.id == spaceId } ?: return
        updateSpace(space.copy(recentSince = System.currentTimeMillis()))
        notify("Recently visited cleared", "Undo") {
            spaces.firstOrNull { it.id == spaceId }?.let { updateSpace(it.copy(recentSince = space.recentSince)) }
        }
    }

    /** Empties the orbit (and so Satellite): every favorite is removed. Can be undone. */
    fun clearOrbit() {
        val oldPins = pins.toList()
        pins.clear()
        savePins()
        notify("Orbit cleared", "Undo") { pins.clear(); pins.addAll(oldPins); savePins() }
    }

    fun forgetSite(host: String) {
        val site = Url.site(host)
        history.removeAll { Url.host(it.url)?.let(Url::site) == site }
        pins.removeAll { Url.host(it.url)?.let(Url::site) == site }
        saveHistory(); savePins()
    }

    // ---- Peek: preview a link in a floating card without leaving the page ----

    fun openPeek(url: String, from: Tab) {
        closePeek()
        val t = Tab(spaceId = from.spaceId, ghost = from.ghost, url = url, parentId = from.id)
        t.showHome = false
        t.startedFromHome = false
        peek = t
        ensureWebView(t)
    }

    fun closePeek() {
        peek?.let { destroyWebView(it) }
        peek = null
    }

    fun promotePeek() {
        val t = peek ?: return
        peek = null
        val parentIndex = tabs.indexOfFirst { it.id == t.parentId }
        if (parentIndex >= 0) tabs.add(parentIndex + 1, t) else tabs.add(t)
        select(t)
    }

    fun exitFullscreen() {
        customCallback?.onCustomViewHidden()
        customView = null
        customCallback = null
    }

    // =====================================================================================
    // Intents
    // =====================================================================================

    /** Something a home-screen widget asked for; the UI carries it out and clears it. */
    var launch by mutableStateOf<Launch?>(null)

    /** Bumped whenever another app hands us a link, so the UI can close what's in the way. */
    var externalOpens by mutableIntStateOf(0)
        private set

    fun handleIntent(intent: Intent?) {
        intent ?: return
        Widgets.launchFor(intent)?.let { launch = it; return }
        if (intent.action == MediaCenter.ACTION_OPEN) {
            intent.getStringExtra(MediaCenter.EXTRA_TAB)?.let { id -> tabs.firstOrNull { it.id == id }?.let(::select) }
            return
        }
        if (intent.action in setOf(Intent.ACTION_VIEW, Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEND)) externalOpens++
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let { openFromOutside(it) }
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")?.let { openFromOutside(it) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { openFromOutside(Url.extract(it) ?: it) }
        }
    }

    /** The current tab if it's an empty start page, else a new tab. */
    fun blankTab(): Tab {
        val cur = current
        return if (cur != null && cur.showHome && !cur.ghost && cur.webView == null) cur.also(::select) else newTab()
    }

    private fun openFromOutside(input: String) {
        val t = blankTab()
        val resolved = Url.resolve(input, settings.engine)
        // Other apps may only hand us web pages; anything else becomes a search.
        navigate(if (resolved.startsWith("https://") || resolved.startsWith("http://")) resolved else Url.search(input, settings.engine), t)
    }

    private fun openExternal(url: String): Boolean = try {
        val intent = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        // Only browsable activities of *other* apps, with no URI grants or explicit targets.
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.clipData = null
        intent.flags = intent.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            ).inv()
        if (intent.`package` == activity.packageName) intent.`package` = null
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
                current?.let { navigate(fallback, it) }
            } else notify("No app can open this link")
        }
        true
    } catch (e: Exception) {
        false
    }

    // =====================================================================================
    // Search suggestions (Google's or DuckDuckGo's autocomplete; no cookies, no identifiers)
    // =====================================================================================

    suspend fun suggestions(query: String, ghost: Boolean = false): List<String> = withContext(Dispatchers.IO) {
        if (ghost || !settings.suggestions || query.isBlank() || query.startsWith(">")) return@withContext emptyList()
        runCatching {
            val q = URLEncoder.encode(query, "UTF-8")
            val endpoint = if (settings.engine == SearchEngine.GOOGLE) "https://suggestqueries.google.com/complete/search?client=firefox&ie=UTF-8&oe=UTF-8&q=$q"
            else "https://ac.duckduckgo.com/ac/?q=$q&type=list"
            val conn = Net.open(endpoint, connectMs = 3000, readMs = 3000)
            conn.useCaches = false
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            if (arr.length() > 1 && arr.opt(1) is JSONArray) {
                val list = arr.getJSONArray(1)
                List(list.length()) { list.getString(it) }
            } else List(arr.length()) { arr.getJSONObject(it).optString("phrase") }
        }.getOrDefault(emptyList()).filter { it.isNotBlank() && it != query }.take(5)
    }

    // =====================================================================================
    // WebView management
    // =====================================================================================

    private fun liveTabs() = tabs.filter { it.webView != null }

    /** Keeps memory in check: tabs not used recently are serialized and their WebView freed. */
    private fun hibernateIdle(maxLive: Int = 6) {
        val live = liveTabs().filter { it.id != currentId && it.id != mediaTabId }.sortedBy { it.lastActive }
        val excess = live.size - (maxLive - 1)
        if (excess <= 0) return
        live.take(excess).forEach { t ->
            val wv = t.webView ?: return@forEach
            val b = Bundle()
            runCatching { wv.saveState(b) }
            t.savedState = b
            destroyWebView(t)
        }
    }

    private fun destroyWebView(tab: Tab) {
        if (tab.id == mediaTabId) stopMedia()
        val wv = tab.webView ?: return
        tab.webView = null
        tab.youtubeScript = null
        tab.noRtcScript = null
        if (tab.pendingUrl == RESTORE) tab.pendingUrl = null
        (wv.parent as? ViewGroup)?.removeView(wv)
        runCatching {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        }
    }

    fun ensureWebView(tab: Tab, loadInitial: Boolean = true): WebView {
        tab.webView?.let { return it }
        val wv = createWebView(tab)
        tab.painted = false
        tab.webView = wv
        if (!Vpn.routeReady) {
            // Restored or loaded once Orbit VPN's route is in place (see flushPendingLoads).
            if (tab.savedState != null) tab.pendingUrl = RESTORE
            else if (loadInitial && tab.url.isNotBlank()) tab.pendingUrl = tab.url
            return wv
        }
        val restored = tab.savedState?.let { runCatching { wv.restoreState(it) }.getOrNull() }
        tab.savedState = null
        if (restored == null && loadInitial && tab.url.isNotBlank()) wv.loadUrl(tab.url)
        return wv
    }

    private fun applyWebSettings(wv: WebView, tab: Tab) {
        wv.settings.apply {
            userAgentString = if (tab.desktop || settings.desktopDefault) desktopUa else mobileUa
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = settings.pageZoom
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, settings.darkPages)
        }
        // (12) Safe Browsing is on by default; make it explicit so it can't silently regress.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(wv.settings, true)
        }
        // Third-party cookies are refused whenever Shields is on, and always in ghost tabs.
        cookiesFor(tab).setAcceptThirdPartyCookies(wv, !tab.ghost && !settings.shields)
    }

    private fun injectPageStyles(tab: Tab) {
        val wv = tab.webView ?: return
        val site = tab.pageHost?.let(Url::site)
        // Without the Shields channel (old WebView), the page's own rules are applied from here.
        if (!shieldsChannel) {
            val host = tab.pageHost
            val lists = shieldLists(site)
            val css = if (host != null && lists != 0) Shields.pageCss(host, lists, tab.shieldFlags) else ""
            wv.evaluateJavascript(Scripts.style("orbit-shields", css), null)
        }
        val z = site?.let { zaps[it] }.orEmpty()
        wv.evaluateJavascript(Scripts.style("orbit-zap", Scripts.zapCss(z)), null)
        if (tab.zapMode) wv.evaluateJavascript(Scripts.ZAP_ON, null)
    }

    private fun readThemeColor(tab: Tab) {
        tab.webView?.evaluateJavascript(Scripts.THEME) { raw ->
            tab.themeColor = parseCssColor(Scripts.unwrap(raw))
        }
    }

    fun captureThumbnail(tab: Tab) {
        val wv = tab.webView ?: return
        if (tab.showHome || !tab.painted || wv.width == 0 || wv.height == 0 || wv.parent == null) return
        runCatching {
            val scale = 0.4f
            val bmp = Bitmap.createBitmap((wv.width * scale).toInt(), (wv.height * scale).toInt(), Bitmap.Config.RGB_565)
            val c = Canvas(bmp)
            c.scale(scale, scale)
            c.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
            wv.draw(c)
            tab.thumbnail = bmp.asImageBitmap()
        }
    }

    private fun recordVisit(tab: Tab, url: String, title: String) {
        if (tab.ghost || url.startsWith("about:") || url.startsWith("data:")) return
        val top = history.firstOrNull()
        if (top != null && top.url == url && top.spaceId == tab.spaceId) {
            if (title.isNotBlank() && title != top.title) history[0] = top.copy(title = title)
        } else {
            history.add(0, HistoryEntry(url, title, System.currentTimeMillis(), tab.spaceId))
            while (history.size > 3000) history.removeAt(history.lastIndex)
        }
        saveHistory()
    }

    private fun download(tab: Tab, url: String, ua: String?, disposition: String?, mime: String?, confirmed: Boolean = false) {
        if (!url.startsWith("http")) return notify("Can't download this file")
        // Android's download manager is a separate app: it can't go through Orbit VPN.
        if (Vpn.enabled && !confirmed) {
            return notify("Downloads don't go through Orbit VPN", "Download") { download(tab, url, ua, disposition, mime, confirmed = true) }
        }
        runCatching {
            val name = URLUtil.guessFileName(url, disposition, mime)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mime)
                addRequestHeader("User-Agent", ua)
                cookiesFor(tab).getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            notify("Downloading $name")
        }.onFailure { notify("Download failed") }
    }

    fun downloadUrl(url: String, tab: Tab) = download(tab, url, mobileUa, null, null)

    /**
     * The only page -> app channel. Messages are accepted only from the top frame of the page the
     * tab is showing, and only while the user has "Hide elements" switched on.
     */
    private fun installBridge(wv: WebView, tab: Tab) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        runCatching {
            WebViewCompat.addWebMessageListener(wv, "OrbitBridge", setOf("*")) { _, message, sourceOrigin, isMainFrame, _ ->
                val selector = message.data ?: return@addWebMessageListener
                if (!isMainFrame || !tab.zapMode) return@addWebMessageListener
                if (sourceOrigin.host?.lowercase() != tab.pageHost) return@addWebMessageListener
                if (selector.isNotBlank() && selector.length < 2000) addZap(tab, selector)
            }
        }
    }

    /**
     * Media reports from pages (see [Scripts.MEDIA]). Any frame may report, since players are
     * often embedded; the data is only shown in the notification, and commands go back only to
     * the frame that reported.
     */
    private fun installMediaBridge(wv: WebView, tab: Tab) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        runCatching {
            WebViewCompat.addWebMessageListener(wv, "OrbitMedia", setOf("*")) { _, message, _, _, reply ->
                val data = message.data ?: return@addWebMessageListener
                if (data.length < 8000) onMediaMessage(tab, data, reply)
            }
            if (docStartScripts) WebViewCompat.addDocumentStartJavaScript(wv, Scripts.MEDIA, setOf("*"))
        }
    }

    private val shieldsChannel = docStartScripts &&
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }.getOrDefault(false)

    /**
     * Element hiding (see [Scripts.SHIELDS]). Any frame may ask, for its own origin; it only ever
     * gets CSS built from the filter lists, so a page that misuses this learns nothing new.
     */
    private fun installShields(wv: WebView, tab: Tab) {
        if (!shieldsChannel) return
        runCatching {
            WebViewCompat.addWebMessageListener(wv, "OrbitShields", setOf("*")) { _, message, sourceOrigin, _, reply ->
                val data = message.data ?: return@addWebMessageListener
                if (data.length > 200_000) return@addWebMessageListener
                val scheme = sourceOrigin.scheme
                val host = sourceOrigin.host?.lowercase()
                if (host == null || (scheme != "https" && scheme != "http")) return@addWebMessageListener
                // Rules follow the page in the tab, so a site's own switch covers its frames too.
                val lists = shieldLists(tab.pageHost?.let(Url::site) ?: Url.site(host))
                val o = runCatching { JSONObject(data) }.getOrNull() ?: return@addWebMessageListener
                when (o.optString("t")) {
                    "init" -> {
                        if (lists == 0) {
                            reply.postMessage("{}")
                            return@addWebMessageListener
                        }
                        val flags = Shields.pageFlags("$scheme://$host/", lists)
                        val hide = flags and (RType.DOCUMENT or RType.ELEMHIDE) == 0
                        val reply2 = JSONObject()
                            .put("css", Shields.pageCss(host, lists, flags))
                            .put("scan", hide && flags and RType.GENERICHIDE == 0)
                        reply.postMessage(reply2.toString())
                    }
                    "scan" -> {
                        if (lists == 0) return@addWebMessageListener
                        fun names(key: String): List<String> {
                            val a = o.optJSONArray(key) ?: return emptyList()
                            return List(minOf(a.length(), 2000)) { a.optString(it) }.filter { it.length in 1..200 }
                        }
                        val css = Shields.namesCss(host, names("c"), names("i"), lists)
                        if (css.isNotEmpty()) reply.postMessage("+$css")
                    }
                }
            }
            WebViewCompat.addDocumentStartJavaScript(wv, Scripts.SHIELDS, setOf("*"))
        }
    }

    /** Adds or removes the document-start scripts that depend on settings, for pages loaded from now on. */
    private fun syncScripts(tab: Tab) {
        val wv = tab.webView ?: return
        if (!docStartScripts) return
        val youtube = shieldLists("youtube.com") and Shields.ADS != 0
        if (youtube && tab.youtubeScript == null) {
            tab.youtubeScript = runCatching { WebViewCompat.addDocumentStartJavaScript(wv, Scripts.YOUTUBE, YOUTUBE_ORIGINS) }.getOrNull()
        } else if (!youtube) {
            tab.youtubeScript?.let { runCatching { it.remove() } }
            tab.youtubeScript = null
        }
        if (Vpn.enabled && tab.noRtcScript == null) {
            tab.noRtcScript = runCatching { WebViewCompat.addDocumentStartJavaScript(wv, Scripts.NO_WEBRTC, setOf("*")) }.getOrNull()
        } else if (!Vpn.enabled) {
            tab.noRtcScript?.let { runCatching { it.remove() } }
            tab.noRtcScript = null
        }
    }

    private fun syncAllScripts() = (tabs + listOfNotNull(peek)).forEach(::syncScripts)

    /** True if this tab should keep running while Orbit is in the background. */
    fun keepsPlaying(tab: Tab) = settings.backgroundPlay && mediaPlaying && tab.id == mediaTabId

    private fun onMediaMessage(tab: Tab, raw: String, reply: JavaScriptReplyProxy) {
        if (!settings.backgroundPlay) return
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        // The player went away (e.g. the site navigated to another page without reloading).
        if (o.optBoolean("gone")) {
            if (tab.id == mediaTabId) stopMedia()
            return
        }
        val playing = o.optBoolean("playing")
        // A paused report only matters for the tab the notification is about.
        if (!playing && tab.id != mediaTabId) return
        if (playing && mediaTabId == null) askNotifications?.invoke()
        mediaTabId = tab.id
        mediaReply = reply
        mediaPlaying = playing
        lastMedia = o
        val art = o.optString("art").takeIf { it.startsWith("https://") && it.length < 2000 && !tab.ghost }
        if (art != mediaArtUrl) {
            mediaArtUrl = art
            mediaArt = null
            if (art != null) scope.launch {
                val bmp = runCatching { Images.remote(art, maxWidth = 512)?.asAndroidBitmap() }.getOrNull()
                if (mediaArtUrl == art && bmp != null) { mediaArt = bmp; publishMedia() }
            }
        }
        publishMedia()
    }

    private fun publishMedia() {
        val id = mediaTabId ?: return
        val tab = tabs.firstOrNull { it.id == id } ?: return stopMedia()
        val o = lastMedia ?: return
        val site = tab.pageHost?.let(Url::site).orEmpty()
        val info = if (tab.ghost) {
            MediaInfo(id, "Ghost tab", "Playing privately", mediaPlaying, 0, 0, false, false, null, private = true)
        } else {
            MediaInfo(
                tabId = id,
                title = o.optString("title").trim().take(200).ifBlank { tab.displayTitle },
                artist = o.optString("artist").trim().take(200).ifBlank { site },
                playing = mediaPlaying,
                positionMs = o.optLong("pos").coerceAtLeast(0),
                durationMs = o.optLong("dur").coerceAtLeast(0),
                hasNext = o.optBoolean("next"),
                hasPrev = o.optBoolean("prev"),
                art = mediaArt ?: tab.favicon?.asAndroidBitmap(),
                private = false,
            )
        }
        MediaCenter.publish(activity, info)
    }

    /** Commands from the notification, lock screen or headset. */
    private fun mediaCommand(cmd: String) {
        val reply = mediaReply
        if (cmd == "stop") {
            runCatching { reply?.postMessage("pause") }
            stopMedia()
            return
        }
        if (reply == null) return stopMedia()
        runCatching { reply.postMessage(cmd) }.onFailure { stopMedia() }
        // While Orbit is in the background a paused tab's WebView may be paused too; wake it to play.
        if (cmd == "play") tabs.firstOrNull { it.id == mediaTabId }?.webView?.onResume()
    }

    fun stopMedia() {
        val id = mediaTabId
        mediaTabId = null
        mediaPlaying = false
        mediaReply = null
        mediaArtUrl = null
        mediaArt = null
        lastMedia = null
        MediaCenter.clear(activity)
        // Back in the background with nothing playing: let the tab rest like any other.
        if (!foreground) tabs.firstOrNull { it.id == id }?.webView?.onPause()
    }

    private fun createWebView(tab: Tab): WebView {
        val wv = OrbitWebView(activity) { keepsPlaying(tab) }
        if (multiProfile) runCatching { WebViewCompat.setProfile(wv, profileName(tab.spaceId, tab.ghost)) }
        wv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            if (tab.ghost) cacheMode = WebSettings.LOAD_NO_CACHE
        }
        applyWebSettings(wv, tab)
        installBridge(wv, tab)
        installMediaBridge(wv, tab)
        installShields(wv, tab)
        // Registered on the WebView object, so it exists from here on.
        tab.webView = wv
        syncScripts(tab)

        wv.setFindListener { active, total, done ->
            if (done && tab.id == currentId) { findCurrent = if (total == 0) 0 else active + 1; findTotal = total }
        }

        wv.setOnScrollChangeListener { _, _, y, _, oldY ->
            if (!settings.collapseOnScroll || tab.id != currentId) return@setOnScrollChangeListener
            val dy = y - oldY
            if (y <= 0) barCollapsed = false
            else if (dy > 14) barCollapsed = true
            else if (dy < -24) barCollapsed = false
        }

        wv.setOnLongClickListener { v ->
            val r = (v as WebView).hitTestResult
            when (r.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    linkMenu = LinkTarget(r.extra, null, tab); true
                }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val img = r.extra
                    val msg = main.obtainMessage()
                    msg.target = Handler(Looper.getMainLooper()) { m ->
                        linkMenu = LinkTarget(m.data.getString("url") ?: img, img, tab); true
                    }
                    v.requestFocusNodeHref(msg)
                    true
                }
                WebView.HitTestResult.IMAGE_TYPE -> {
                    linkMenu = LinkTarget(null, r.extra, tab); true
                }
                else -> false
            }
        }

        wv.setDownloadListener { url, ua, disposition, mime, _ -> download(tab, url, ua, disposition, mime) }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme?.lowercase()
                if (request.isForMainFrame && (scheme == "http" || scheme == "https") && request.method.equals("GET", ignoreCase = true)) {
                    val host = request.url.host?.lowercase()
                    // A site that sends its https page back to http has no secure version.
                    if (scheme == "http" && request.isRedirect && host != null && host == tab.upgradedHost) {
                        offerHttp(tab, host, url)
                        return true
                    }
                    val target = linkFor(url)
                    // A site that adds a removed parameter back gets its way, rather than a loop.
                    if (target != null && !(request.isRedirect && target == tab.cleanedUrl)) {
                        if (target.startsWith("https://") && scheme == "http") tab.upgradedHost = host
                        tab.cleanedUrl = target
                        view.loadUrl(target)
                        return true
                    }
                }
                if (scheme !in setOf("http", "https", "about", "data", "blob", "javascript")) {
                    if (scheme == "file" || scheme == "content") return true
                    // Other apps only open from a tap; anything else has to be confirmed.
                    if (request.hasGesture()) return openExternal(url)
                    notify("${Url.pretty(tab.url)} wants to open another app", "Open") { openExternal(url) }
                    return true
                }
                // Pages may not send you to a top-level data: URL (a common phishing trick).
                if (request.isForMainFrame && scheme == "data") {
                    notify("Blocked a data: page")
                    return true
                }
                if (request.isForMainFrame && !flowAllows(url)) {
                    tab.flowBlocked = url
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame || !settings.shields) return null
                val pageHost = tab.pageHost
                val lists = shieldLists(pageHost?.let(Url::site))
                if (lists == 0) return null
                val res = Shields.intercept(request, pageHost, lists, tab.shieldFlags)
                if (res != null) main.post { tab.blockedCount++; totalBlocked++; saveStats() }
                return res
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                val newHost = Url.host(url)
                if (newHost == null || tab.pageHost == null || Url.site(newHost) != Url.site(tab.pageHost!!)) tab.favicon = null
                tab.url = url
                tab.pageHost = Url.host(url)
                tab.shieldFlags = Shields.pageFlags(url, shieldLists(tab.pageHost?.let(Url::site)))
                tab.loading = true
                tab.blockedCount = 0
                tab.zapMode = false
                if (!url.startsWith("about:")) tab.showHome = false
                favicon?.let { tab.favicon = it.asImageBitmap() }
                injectPageStyles(tab)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                tab.painted = true
                injectPageStyles(tab)
                readThemeColor(tab)
            }

            override fun onPageFinished(view: WebView, url: String) {
                tab.loading = false
                tab.painted = true
                tab.canBack = view.canGoBack()
                tab.canForward = view.canGoForward()
                injectPageStyles(tab)
                if (!docStartScripts) view.evaluateJavascript(Scripts.MEDIA, null)
                readThemeColor(tab)
                recordVisit(tab, url, view.title ?: "")
                if (tab.id == currentId) main.postDelayed({ captureThumbnail(tab) }, 600)
                saveSession()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (url.startsWith("about:")) return
                tab.url = url
                tab.pageHost = Url.host(url)
                tab.canBack = view.canGoBack()
                tab.canForward = view.canGoForward()
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                val host = Url.host(error.url)
                if (host != null && host == tab.upgradedHost) offerHttp(tab, host, error.url)
                else notify("Connection to ${Url.pretty(error.url)} isn't secure")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                val host = request.url.host?.lowercase() ?: return
                if (request.isForMainFrame && request.url.scheme == "https" && host == tab.upgradedHost) {
                    offerHttp(tab, host, request.url.toString())
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                tab.progress = newProgress
                if (newProgress == 100) tab.loading = false
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                tab.title = title.orEmpty()
                if (!tab.ghost && title != null) {
                    val top = history.firstOrNull()
                    if (top != null && top.url == view.url) history[0] = top.copy(title = title)
                }
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                icon ?: return
                tab.favicon = icon.asImageBitmap()
                // Key by the WebView's own URL: callbacks can arrive after the tab has moved on.
                if (!tab.ghost) Url.host(view.url)?.let { h -> scope.launch(Dispatchers.IO) { Images.saveIcon(h, icon) } }
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
                val child = Tab(spaceId = tab.spaceId, ghost = tab.ghost, parentId = tab.id)
                child.showHome = false
                child.startedFromHome = false
                val parentIndex = tabs.indexOf(tab)
                if (parentIndex >= 0) tabs.add(parentIndex + 1, child) else tabs.add(child)
                val childView = ensureWebView(child, loadInitial = false)
                (resultMsg.obj as WebView.WebViewTransport).webView = childView
                resultMsg.sendToTarget()
                if (peek === tab) closePeek()
                select(child)
                return true
            }

            override fun onCloseWindow(window: WebView) {
                tabs.firstOrNull { it.webView === window }?.let { closeTab(it, undoable = false) }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customCallback?.onCustomViewHidden()
                customView = view
                customCallback = callback
            }

            override fun onHideCustomView() {
                customView = null
                customCallback = null
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                val launcher = fileChooser ?: return false
                val multiple = fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                launcher(filePickerIntent(fileChooserParams.acceptTypes, multiple)) { uris ->
                    // A single-file input takes one file even if the picker returned several.
                    filePathCallback.onReceiveValue(if (multiple || uris == null) uris else uris.take(1).toTypedArray())
                }
                return true
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }
        }
        return wv
    }

    companion object {
        /** How many favorites the start page orbit shows. */
        const val ORBIT_SIZE = 12

        /** How many orbit sites Satellite holds. */
        const val SATELLITE_SIZE = 5

        /**
         * The system picker for an `<input type="file">`. WebView's own createIntent only looks at the
         * first accept type (often an extension like ".jpg", which matches nothing) and never allows
         * picking several files, so the intent is built here instead.
         */
        fun filePickerIntent(acceptTypes: Array<String>?, multiple: Boolean): Intent {
            val mimes = acceptTypes.orEmpty()
                .flatMap { it.split(',') }
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .map { if (it.startsWith('.')) MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.drop(1)) ?: "*/*" else it }
                .distinct()
            return Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                when {
                    mimes.isEmpty() || "*/*" in mimes -> type = "*/*"
                    mimes.size == 1 -> type = mimes[0]
                    else -> {
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                    }
                }
                if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }

        /** The files a picker returned: several come back as ClipData, a single one as the data URI. */
        fun pickedFiles(resultCode: Int, data: Intent?): Array<Uri>? {
            if (resultCode != android.app.Activity.RESULT_OK || data == null) return null
            val clip = data.clipData
            val uris = if (clip != null && clip.itemCount > 0) {
                (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            } else {
                listOfNotNull(data.data)
            }
            return uris.takeIf { it.isNotEmpty() }?.toTypedArray()
        }

        /** [Tab.pendingUrl] for a tab whose saved state is restored once the route is ready. */
        private const val RESTORE = "orbit:restore"

        private val YOUTUBE_ORIGINS = setOf(
            "https://www.youtube.com", "https://m.youtube.com", "https://music.youtube.com", "https://youtube.com",
        )

        /** How much history is backed up to Google. */
        private const val SYNCED_HISTORY = 500

        fun parseCssColor(raw: String): Color? {
            val s = raw.trim().lowercase()
            if (s.isEmpty()) return null
            Regex("rgba?\\(\\s*(\\d+)[,\\s]+(\\d+)[,\\s]+(\\d+)(?:[,\\s/]+([\\d.]+)(%?))?\\s*\\)").find(s)?.let { m ->
                val (r, g, b) = m.destructured
                var a = m.groupValues[4].toFloatOrNull() ?: 1f
                if (m.groupValues[5] == "%") a /= 100f
                if (a < 0.6f) return null
                return Color(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
            }
            val hex = if (Regex("^#[0-9a-f]{3}$").matches(s)) "#" + s.drop(1).map { "$it$it" }.joinToString("") else s
            return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
        }
    }
}

/**
 * A WebView that can keep playing in the background: while [keepAlive] is true it isn't told its
 * window went away, so the engine doesn't pause or throttle the page's media.
 */
@SuppressLint("ViewConstructor")
private class OrbitWebView(context: Context, private val keepAlive: () -> Boolean) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(if (visibility != View.VISIBLE && keepAlive()) View.VISIBLE else visibility)
    }
}
