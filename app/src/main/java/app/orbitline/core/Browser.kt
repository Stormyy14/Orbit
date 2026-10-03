package app.orbitline.core

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
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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

    fun updateSettings(s: Settings) {
        settings = s
        store.write("settings") { s.toJson().toString() }
        tabs.forEach { t -> t.webView?.let { applyWebSettings(it, t) } }
        changed()
    }

    fun onPause() {
        foreground = false
        current?.webView?.onPause()
        saveSession()
        // Don't leave changes waiting for the debounce: the app may not come back.
        if (pushJob?.isActive == true) syncNow()
    }

    fun onResume() {
        foreground = true
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
        prev?.let { captureThumbnail(it); it.webView?.onPause() }
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
            o.optJSONObject("settings")?.let { updateSettings(Settings.fromJson(it)) }
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
        t.showHome = false
        t.url = url
        t.pageHost = Url.host(url)
        val wv = ensureWebView(t, loadInitial = false)
        wv.loadUrl(url)
        barCollapsed = false
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

    fun shieldsOn(tab: Tab?): Boolean {
        val site = tab?.pageHost?.let(Url::site) ?: return settings.shields
        return settings.shields && site !in shieldsOff
    }

    fun toggleSiteShields(tab: Tab) {
        val site = tab.pageHost?.let(Url::site) ?: return
        if (site in shieldsOff) shieldsOff.remove(site) else shieldsOff.add(site)
        saveStats()
        changed()
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

    /**
     * Empties the orbit of a space: favorites are unpinned and earlier visits stop counting.
     * Sites come back as you visit them again. Can be undone.
     */
    fun clearOrbit(spaceId: String) {
        val space = spaces.firstOrNull { it.id == spaceId } ?: return
        val oldPins = pins.toList()
        pins.clear()
        savePins()
        updateSpace(space.copy(orbitSince = System.currentTimeMillis()))
        notify("Orbit cleared", "Undo") {
            pins.clear(); pins.addAll(oldPins); savePins()
            spaces.firstOrNull { it.id == spaceId }?.let { updateSpace(it.copy(orbitSince = space.orbitSince)) }
        }
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

    /** Bumped whenever another app hands us a link, so the UI can close what's in the way. */
    var externalOpens by mutableIntStateOf(0)
        private set

    fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action in setOf(Intent.ACTION_VIEW, Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEND)) externalOpens++
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let { openFromOutside(it) }
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")?.let { openFromOutside(it) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { openFromOutside(Url.extract(it) ?: it) }
        }
    }

    private fun openFromOutside(input: String) {
        val cur = current
        val t = if (cur != null && cur.showHome && !cur.ghost && cur.webView == null) cur else newTab()
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
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.useCaches = false
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
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
        val live = liveTabs().filter { it.id != currentId }.sortedBy { it.lastActive }
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
        val wv = tab.webView ?: return
        tab.webView = null
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
        if (settings.hideCookieBanners && shieldsOn(tab)) wv.evaluateJavascript(Scripts.style("orbit-cookie", Shields.cookieCss), null)
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

    private fun download(tab: Tab, url: String, ua: String?, disposition: String?, mime: String?) {
        if (!url.startsWith("http")) return notify("Can't download this file")
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

    private fun createWebView(tab: Tab): WebView {
        val wv = WebView(activity)
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
        if (docStartScripts && settings.hideCookieBanners) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(wv, Scripts.style("orbit-cookie", Shields.cookieCss), setOf("*")) }
        }

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
                val pageSite = tab.pageHost?.let(Url::site)
                if (pageSite != null && pageSite in shieldsOff) return null
                val res = Shields.intercept(request.url, tab.pageHost)
                if (res != null) main.post { tab.blockedCount++; totalBlocked++; saveStats() }
                return res
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                val newHost = Url.host(url)
                if (newHost == null || tab.pageHost == null || Url.site(newHost) != Url.site(tab.pageHost!!)) tab.favicon = null
                tab.url = url
                tab.pageHost = Url.host(url)
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
                notify("Connection to ${Url.pretty(error.url)} isn't secure")
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
                val intent = runCatching { fileChooserParams.createIntent() }.getOrNull() ?: return false
                launcher(intent) { uris -> filePathCallback.onReceiveValue(uris) }
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
