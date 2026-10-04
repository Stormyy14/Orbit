package app.orbit.ui

import androidx.compose.runtime.rememberCoroutineScope
import app.orbit.core.Updates
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material.icons.outlined.Palette
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WebAssetOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.webkit.WebViewCompat
import app.orbit.core.SearchEngine
import app.orbit.core.Settings
import app.orbit.core.Space
import app.orbit.core.Tab
import app.orbit.core.Shields
import app.orbit.core.Url
import app.orbit.core.Vpn
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.VpnLock
import app.orbit.core.UserProfile
import java.text.DateFormat
import java.util.Date

enum class SheetKind { Menu, Shields, Vpn, Spaces, Flow, Settings, Trail, Profiles, QuickAdd, Customize, Update }

private val Flat = RoundedCornerShape(0.dp)

// =============================================================================================
// Page menu
// =============================================================================================

@Composable
fun PageMenu(tab: Tab?, open: (SheetKind) -> Unit, onDismiss: () -> Unit) {
    val browser = LocalBrowser.current
    val haptics = rememberHaptics()
    val page = tab != null && !tab.showHome
    fun act(block: () -> Unit) { haptics.tick(); onDismiss(); block() }
    val pinned = tab?.let { browser.isPinned(it.url) } == true

    OrbSheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
            IconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", enabled = page) { act { browser.goBack() } }
            IconButton(Icons.AutoMirrored.Outlined.ArrowForward, "Forward", enabled = tab?.canForward == true || (tab?.showHome == true && tab.webView != null)) {
                act { browser.goForward() }
            }
            IconButton(Icons.Outlined.Refresh, "Reload", enabled = page) { act { browser.reload() } }
            IconButton(if (pinned) Icons.Outlined.Star else Icons.Outlined.StarBorder, if (pinned) "Remove from favorites" else "Add to favorites", enabled = page && tab?.ghost == false) {
                act { tab?.let { browser.togglePin(it.url, it.title) } }
            }
            IconButton(Icons.Outlined.Share, "Share", enabled = page) { act { browser.share() } }
        }
        Hairline()
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            MenuRow(Icons.Outlined.Add, "New tab") { act { browser.newTab() } }
            MenuRow(Icons.Outlined.VisibilityOff, "New ghost tab") { act { browser.newTab(ghost = true) } }
            Hairline()
            MenuRow(Icons.AutoMirrored.Outlined.MenuBook, "Reader view", enabled = page) { act { browser.openReader() } }
            MenuRow(Icons.Outlined.FindInPage, "Find in page", enabled = page) { act { browser.findOpen = true } }
            MenuRow(Icons.Outlined.DesktopWindows, "Desktop site", enabled = page, checked = tab?.desktop == true) { act { browser.toggleDesktop() } }
            MenuRow(Icons.Outlined.WebAssetOff, if (tab?.zapMode == true) "Stop hiding elements" else "Hide elements", enabled = page) { act { browser.toggleZap() } }
            MenuRow(Icons.Outlined.ContentCopy, "Copy link", enabled = page) { act { tab?.let { browser.copy(it.url) } } }
            MenuRow(Icons.Outlined.History, "Tab history", enabled = page) { act { open(SheetKind.Trail) } }
            Hairline()
            MenuRow(
                Icons.Outlined.Shield, "Shields", enabled = page,
                meta = if (!page) null else if (browser.shieldsOn(tab)) "${tab?.blockedCount ?: 0} blocked" else "Off",
            ) { act { open(SheetKind.Shields) } }
            MenuRow(Icons.Outlined.VpnLock, "Orbit VPN", meta = vpnMeta()) { act { open(SheetKind.Vpn) } }
            MenuRow(
                Icons.Outlined.Timer, "Focus",
                meta = if (browser.flowActive) "until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(browser.flowUntil)) else null,
            ) { act { open(SheetKind.Flow) } }
            MenuRow(Icons.Outlined.SatelliteAlt, "Satellite", checked = browser.settings.satellite) { act { browser.toggleSatellite() } }
            MenuRow(spaceIcon(browser.currentSpace.icon), "Spaces", meta = browser.currentSpace.name) { act { open(SheetKind.Spaces) } }
            MenuRow(Icons.Outlined.AccountCircle, "Profiles", meta = browser.profile?.name ?: "Guest") { act { open(SheetKind.Profiles) } }
            MenuRow(Icons.Outlined.Palette, "Customize") { act { open(SheetKind.Customize) } }
            MenuRow(Icons.Outlined.Settings, "Settings") { act { open(SheetKind.Settings) } }
            if (tab != null) {
                Hairline()
                MenuRow(Icons.Outlined.Close, "Close tab", color = Orb.Red) { act { browser.closeTab(tab) } }
            }
        }
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    meta: String? = null,
    checked: Boolean = false,
    color: Color? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).tap(Flat, enabled) { onClick() }.padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dim = Orb.Text3.copy(alpha = 0.55f)
        Icon(icon, null, tint = if (!enabled) dim else color ?: Orb.Text2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (!enabled) dim else color ?: Orb.Text, modifier = Modifier.weight(1f))
        if (meta != null) Text(meta, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TNUM), color = Orb.Text2)
        if (checked) Icon(Icons.Outlined.Check, "On", tint = Orb.Text, modifier = Modifier.size(18.dp))
    }
}

// =============================================================================================
// Shields
// =============================================================================================

@Composable
fun ShieldsSheet(tab: Tab?, onDismiss: () -> Unit) {
    val browser = LocalBrowser.current
    val scope = rememberCoroutineScope()
    val site = tab?.pageHost?.takeIf { tab.showHome.not() }?.let(Url::site)
    val on = browser.shieldsOn(tab)
    val s = browser.settings
    fun set(n: Settings) {
        browser.updateSettings(n)
        if (tab != null && !tab.showHome) browser.reload(tab)
    }
    OrbSheet(onDismiss) {
        Column(Modifier.heightIn(max = 680.dp).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp)) {
                Text("Shields", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        !s.shields -> "Off everywhere"
                        site == null -> "${formatCount(browser.totalBlocked)} ads and trackers blocked so far"
                        !on -> "Off for $site"
                        else -> "${tab?.blockedCount ?: 0} ads and trackers blocked on $site"
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TNUM), color = Orb.Text2,
                )
            }
            if (tab != null && site != null && s.shields) {
                ToggleRow("Shields on this site", "Turn off if $site doesn't work properly", site !in browser.shieldsOff, Icons.Outlined.Shield) {
                    browser.toggleSiteShields(tab)
                }
            }
            Heading("Everywhere")
            ToggleRow("Shields", null, s.shields, Icons.Outlined.Shield) { set(s.copy(shields = it)) }
            if (s.shields) {
                ToggleRow("Block ads", "Including YouTube ads", s.blockAds, Icons.Outlined.Block) { set(s.copy(blockAds = it)) }
                ToggleRow("Block trackers", "Analytics, tracking pixels, third-party cookies", s.blockTrackers, Icons.Outlined.Fingerprint) {
                    set(s.copy(blockTrackers = it))
                }
                ToggleRow("Hide cookie banners", "Hidden, never accepted", s.hideCookieBanners, Icons.Outlined.Cookie) {
                    set(s.copy(hideCookieBanners = it))
                }
                ToggleRow("Remove tracking from links", "utm_, fbclid, gclid and others", s.cleanLinks, Icons.Outlined.LinkOff) {
                    browser.updateSettings(s.copy(cleanLinks = it))
                }
            }
            FilterListsRow(scope)
            val hidden = site?.let { browser.zaps[it] }.orEmpty()
            if (hidden.isNotEmpty()) {
                Heading("Hidden on this site")
                hidden.forEach { sel ->
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(sel, style = MonoSmall, color = Orb.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(Icons.Outlined.Close, "Show again", tint = Orb.Text2, size = 40.dp) { browser.removeZap(site!!, sel) }
                    }
                }
            }
            if (tab != null && !tab.showHome) {
                SecondaryButton(
                    if (tab.zapMode) "Stop hiding elements" else "Hide elements on this page",
                    Modifier.padding(horizontal = 16.dp, vertical = 16.dp).fillMaxWidth(),
                    icon = Icons.Outlined.WebAssetOff,
                ) { onDismiss(); browser.toggleZap(tab) }
            }
        }
    }
}

/** The filter lists: how many rules, when they were checked, and a way to check now. */
@Composable
private fun FilterListsRow(scope: kotlinx.coroutines.CoroutineScope) {
    val browser = LocalBrowser.current
    val checked = Shields.checkedAt
    ListRow(
        "Filter lists",
        subtitle = when {
            Shields.updating -> "Updating"
            Shields.error != null -> Shields.error
            checked == 0L -> "Built-in list · downloading EasyList"
            else -> "${formatCount(Shields.rules.toLong())} rules · checked ${ago(System.currentTimeMillis() - checked).let { if (it == "now") "just now" else "$it ago" }}"
        },
        icon = Icons.Outlined.FilterList,
        trailingText = if (Shields.updating) null else "Update",
    ) {
        Shields.update(scope) { ok -> browser.notify(if (ok) "Filter lists are up to date" else Shields.error ?: "Couldn't update the filter lists") }
    }
}

private fun vpnMeta(): String = when (Vpn.state) {
    Vpn.State.OFF -> "Off"
    Vpn.State.CONNECTING -> "${Vpn.progress}%"
    Vpn.State.ON -> "On"
    Vpn.State.FAILED -> "Not connected"
}

// =============================================================================================
// Spaces
// =============================================================================================

@Composable
fun SpacesSheet(onDismiss: () -> Unit) {
    val browser = LocalBrowser.current
    var editing by remember { mutableStateOf<Space?>(null) }
    var creating by remember { mutableStateOf(false) }
    OrbSheet(onDismiss) {
        val e = editing
        if (e != null || creating) {
            SpaceEditor(
                initial = e,
                onSave = { name, icon ->
                    if (e == null) browser.switchSpace(browser.addSpace(name, icon).id)
                    else browser.updateSpace(e.copy(name = name, icon = icon))
                    editing = null; creating = false
                },
                onDelete = e?.let { s -> { browser.deleteSpace(s.id); editing = null } },
                onCancel = { editing = null; creating = false },
            )
            return@OrbSheet
        }
        Text("Spaces", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp))
        browser.spaces.forEach { s ->
            val selected = s.id == browser.currentSpaceId && browser.current?.ghost != true
            ListRow(
                title = s.name,
                subtitle = browser.tabsIn(s.id).size.let { if (it == 1) "1 tab" else "$it tabs" },
                leading = { SpaceGlyph(s, 22.dp, tint = if (selected) Orb.Text else Orb.Text2) },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selected) Icon(Icons.Outlined.Check, "Current space", tint = Orb.Text, modifier = Modifier.size(18.dp))
                        IconButton(Icons.Outlined.Edit, "Edit ${s.name}", tint = Orb.Text2, size = 40.dp) { editing = s }
                    }
                },
                contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            ) { browser.switchSpace(s.id); onDismiss() }
        }
        ListRow("Ghost tab", leading = { Icon(Icons.Outlined.VisibilityOff, null, tint = Orb.Ghost, modifier = Modifier.size(22.dp)) }) {
            onDismiss(); browser.newTab(ghost = true)
        }
        Hairline()
        ListRow("New space", leading = { Icon(Icons.Outlined.Add, null, tint = Orb.Text2, modifier = Modifier.size(22.dp)) }) { creating = true }
        if (!browser.multiProfile) {
            Text(
                "Logins are shared between spaces on this version of Android System WebView.",
                style = MaterialTheme.typography.bodySmall, color = Orb.Text2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SpaceEditor(initial: Space?, onSave: (String, String) -> Unit, onDelete: (() -> Unit)?, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var icon by remember { mutableStateOf(initial?.icon ?: "folder") }
    Text(
        if (initial == null) "New space" else "Edit space",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 12.dp),
    )
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp).clip(R12).background(Orb.Field).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(spaceIcon(icon), null, tint = Orb.Text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (name.isEmpty()) Text("Name", color = Orb.Text3, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                name, { name = it.take(24) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Orb.Text),
                cursorBrush = SolidColor(Orb.Text), modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Heading("Icon")
    Column(Modifier.padding(horizontal = 12.dp)) {
        SpaceIconSet.chunked(6).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (key, vector) ->
                    val sel = key == icon
                    Box(Modifier.weight(1f).padding(4.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(R12)
                                .background(if (sel) Orb.Field else Color.Transparent)
                                .then(if (sel) Modifier.border(1.5.dp, Orb.Text, R12) else Modifier)
                                .tap(R12) { icon = key },
                            contentAlignment = Alignment.Center,
                        ) { Icon(vector, key, tint = if (sel) Orb.Text else Orb.Text2, modifier = Modifier.size(22.dp)) }
                    }
                }
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onDelete != null) SecondaryButton("Delete", color = Orb.Red) { onDelete() }
        Spacer(Modifier.weight(1f))
        SecondaryButton("Cancel") { onCancel() }
        PrimaryButton("Save", enabled = name.isNotBlank()) { onSave(name.trim(), icon) }
    }
}

// =============================================================================================
// Profiles
// =============================================================================================

@Composable
fun ProfilesSheet(onDismiss: () -> Unit) {
    val browser = LocalBrowser.current
    var editing by remember { mutableStateOf<UserProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    OrbSheet(onDismiss) {
        val e = editing
        if (e != null || creating) {
            ProfileEditor(
                initial = e,
                onSave = { name ->
                    if (e == null) { browser.addLocalProfile(name); onDismiss() } else browser.renameProfile(e.id, name)
                    editing = null; creating = false
                },
                onRemove = e?.let { p -> { browser.removeProfile(p.id); editing = null } },
                onCancel = { editing = null; creating = false },
            )
            return@OrbSheet
        }
        Text("Profiles", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp))
        val rowPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
        ListRow(
            title = "Guest",
            subtitle = "This device only",
            leading = { ProfileBadge(null, 28.dp) },
            trailing = {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (browser.profileId == null) Icon(Icons.Outlined.Check, "Current profile", tint = Orb.Text, modifier = Modifier.size(18.dp))
                }
            },
            contentPadding = rowPadding,
        ) { browser.switchProfile(null); onDismiss() }
        browser.profiles.forEach { p ->
            val selected = p.id == browser.profileId
            ListRow(
                title = p.name,
                subtitle = profileStatus(p, syncing = selected && browser.syncing, error = browser.syncError.takeIf { selected }, now = browser.now),
                leading = { ProfileBadge(p, 28.dp) },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selected) Icon(Icons.Outlined.Check, "Current profile", tint = Orb.Text, modifier = Modifier.size(18.dp))
                        IconButton(Icons.Outlined.Edit, "Edit ${p.name}", tint = Orb.Text2, size = 40.dp) { editing = p }
                    }
                },
                contentPadding = rowPadding,
            ) { browser.switchProfile(p.id); onDismiss() }
        }
        Hairline()
        ListRow("Add Google profile", subtitle = "Saved to your Google account", icon = Icons.Outlined.CloudSync) {
            onDismiss(); browser.addGoogleProfile()
        }
        ListRow("Add profile on this device", icon = Icons.Outlined.Add) { creating = true }
        if (browser.profile?.google == true) {
            Hairline()
            ListRow("Sync now", icon = Icons.Outlined.Sync, trailingText = if (browser.syncing) "Syncing" else null) {
                browser.syncNow(interactive = true)
            }
            browser.syncError?.takeIf { !browser.syncing }?.let { error ->
                Text(
                    "Last sync failed: $error",
                    style = MaterialTheme.typography.bodySmall, color = Orb.Text,
                    modifier = Modifier.padding(start = 56.dp, end = 20.dp, bottom = 4.dp),
                )
            }
        }
        Text(
            "Each profile has its own spaces, favorites, history, settings and logins. Google profiles save everything except logins and open tabs.",
            style = MaterialTheme.typography.bodySmall, color = Orb.Text2,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

private fun profileStatus(p: UserProfile, syncing: Boolean, error: String?, now: Long): String = when {
    !p.google -> "This device only"
    syncing -> "Syncing"
    error != null -> "Not synced · $error"
    p.syncedAt == 0L -> p.email!!
    else -> "${p.email} · synced ${ago(now - p.syncedAt).let { if (it == "now") "just now" else "$it ago" }}"
}

@Composable
private fun ProfileEditor(initial: UserProfile?, onSave: (String) -> Unit, onRemove: (() -> Unit)?, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)) {
        Text(if (initial == null) "New profile" else "Edit profile", style = MaterialTheme.typography.titleLarge)
        initial?.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Orb.Text2) }
    }
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp).clip(R12).background(Orb.Field).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileBadge(if (name.isBlank()) null else UserProfile("", name), 24.dp)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (name.isEmpty()) Text("Name", color = Orb.Text3, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                name, { name = it.take(24) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Orb.Text),
                cursorBrush = SolidColor(Orb.Text), modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onRemove != null) SecondaryButton("Remove", color = Orb.Red) { onRemove() }
        Spacer(Modifier.weight(1f))
        SecondaryButton("Cancel") { onCancel() }
        PrimaryButton(if (initial == null) "Create" else "Save", enabled = name.isNotBlank()) { onSave(name.trim()) }
    }
}

// =============================================================================================
// Quick add: put sites on the orbit
// =============================================================================================

private val QuickSites = listOf(
    "https://www.google.com" to "Google",
    "https://www.youtube.com" to "YouTube",
    "https://mail.google.com" to "Gmail",
    "https://maps.google.com" to "Maps",
    "https://en.wikipedia.org" to "Wikipedia",
    "https://web.whatsapp.com" to "WhatsApp",
    "https://www.instagram.com" to "Instagram",
    "https://x.com" to "X",
    "https://www.facebook.com" to "Facebook",
    "https://www.reddit.com" to "Reddit",
    "https://github.com" to "GitHub",
    "https://www.linkedin.com" to "LinkedIn",
    "https://www.netflix.com" to "Netflix",
    "https://open.spotify.com" to "Spotify",
    "https://www.amazon.com" to "Amazon",
    "https://www.bbc.com/news" to "BBC News",
)

@Composable
fun QuickAddSheet(onDismiss: () -> Unit) {
    val browser = LocalBrowser.current
    val haptics = rememberHaptics()
    var custom by remember { mutableStateOf("") }
    OrbSheet(onDismiss) {
        Text("Add sites", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
        Text(
            "They sit on the inner orbit of every space.",
            style = MaterialTheme.typography.bodyMedium, color = Orb.Text2,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
        )
        Column(Modifier.padding(horizontal = 12.dp)) {
            QuickSites.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { (url, name) ->
                        val on = browser.isPinned(url)
                        Column(
                            Modifier.weight(1f).padding(2.dp).tap(R12) { haptics.tick(); browser.togglePin(url, name, quiet = true) }.padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(contentAlignment = Alignment.BottomEnd) {
                                Box(
                                    Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Orb.Field)
                                        .border(if (on) 1.5.dp else 0.dp, if (on) Orb.Text else Color.Transparent, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) { SiteIcon(url, 24.dp, shape = RoundedCornerShape(4.dp), framed = false) }
                                if (on) {
                                    Box(
                                        Modifier.size(18.dp).clip(CircleShape).background(Orb.Contrast),
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(Icons.Outlined.Check, "Added", tint = Orb.OnContrast, modifier = Modifier.size(12.dp)) }
                                }
                            }
                            Text(
                                name, style = MaterialTheme.typography.labelSmall, color = if (on) Orb.Text else Orb.Text2,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(48.dp).clip(R12).background(Orb.Field)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val host = Url.host(if (custom.contains("://")) custom else "https://$custom")?.takeIf { it.contains('.') }
            Box(Modifier.weight(1f)) {
                if (custom.isEmpty()) Text("Another site, like example.com", color = Orb.Text3, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(
                    custom, { custom = it.trim() }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Orb.Text), cursorBrush = SolidColor(Orb.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            IconButton(Icons.Outlined.Add, "Add", enabled = host != null, size = 40.dp) {
                val url = "https://${host ?: return@IconButton}"
                if (!browser.isPinned(url)) browser.togglePin(url, Url.siteName("", url), quiet = true)
                haptics.confirm()
                custom = ""
            }
        }
        PrimaryButton("Done", Modifier.padding(16.dp).fillMaxWidth()) { onDismiss() }
    }
}

// =============================================================================================
// Focus
// =============================================================================================

@Composable
fun FlowSheet(onDismiss: () -> Unit) {
    val browser = LocalBrowser.current
    var minutes by remember { mutableIntStateOf(25) }
    var newDomain by remember { mutableStateOf("") }
    OrbSheet(onDismiss) {
        Text("Focus", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
        if (browser.flowActive) {
            Text(
                "Blocking ${browser.settings.flowDomains.size} sites until " +
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(browser.flowUntil)),
                style = MaterialTheme.typography.bodyMedium, color = Orb.Text2,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
            )
            FlowTimer(browser.flowUntil - browser.now, browser.flowUntil - browser.flowStartedAt, Modifier.padding(horizontal = 20.dp))
            SecondaryButton("End now", Modifier.padding(16.dp).fillMaxWidth(), color = Orb.Red) { browser.endFlow(); onDismiss() }
            return@OrbSheet
        }
        Text(
            "Block distracting sites for a while.",
            style = MaterialTheme.typography.bodyMedium, color = Orb.Text2,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
        )
        Segmented(listOf(15, 25, 50, 90), minutes, Modifier.padding(horizontal = 16.dp), label = { "$it min" }) { minutes = it }
        Heading("Blocked sites")
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            browser.settings.flowDomains.forEach { d ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SiteIcon("https://$d", 20.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(d, style = MaterialTheme.typography.bodyLarge, color = Orb.Text, modifier = Modifier.weight(1f))
                    IconButton(Icons.Outlined.Close, "Remove $d", tint = Orb.Text2, size = 40.dp) {
                        browser.updateSettings(browser.settings.copy(flowDomains = browser.settings.flowDomains - d))
                    }
                }
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(48.dp).clip(R12).background(Orb.Field)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (newDomain.isEmpty()) Text("Add a site", color = Orb.Text3, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(
                    newDomain, { newDomain = it.trim().lowercase() }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Orb.Text), cursorBrush = SolidColor(Orb.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            IconButton(Icons.Outlined.Add, "Add", enabled = newDomain.contains('.'), size = 40.dp) {
                val d = Url.host(if (newDomain.contains("://")) newDomain else "https://$newDomain")?.removePrefix("www.")
                if (!d.isNullOrBlank() && d.contains('.')) {
                    browser.updateSettings(browser.settings.copy(flowDomains = (browser.settings.flowDomains + d).distinct()))
                    newDomain = ""
                }
            }
        }
        PrimaryButton("Start", Modifier.padding(16.dp).fillMaxWidth()) { browser.startFlow(minutes); onDismiss() }
    }
}

// =============================================================================================
// Settings
// =============================================================================================

@Composable
fun SettingsSheet(onCustomize: () -> Unit, onUpdate: () -> Unit, onShields: () -> Unit, onVpn: () -> Unit, onDismiss: () -> Unit) {
    val browser = LocalBrowser.current
    val context = LocalContext.current
    val s = browser.settings
    fun set(n: Settings) = browser.updateSettings(n)
    val scope = rememberCoroutineScope()
    var licenses by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<Component?>(null) }
    OrbSheet(onDismiss) {
        if (licenses) {
            LicensesView(reading, onOpen = { reading = it }, onBack = { if (reading != null) reading = null else licenses = false })
            return@OrbSheet
        }
        Column(Modifier.heightIn(max = 680.dp).verticalScroll(rememberScrollState())) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp))
            ListRow("Customize", subtitle = "Theme, colours, font, start page, address bar, app icon", icon = Icons.Outlined.Palette) { onCustomize() }
            Heading("Search engine")
            SearchEngine.entries.forEach { e ->
                ListRow(
                    e.label,
                    trailing = {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            if (s.engine == e) Icon(Icons.Outlined.Check, "Selected", tint = Orb.Text, modifier = Modifier.size(18.dp))
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                ) { set(s.copy(engine = e)) }
            }
            ToggleRow("Show search suggestions", null, s.suggestions, Icons.Outlined.Search) { set(s.copy(suggestions = it)) }
            Heading("Privacy")
            ListRow(
                "Shields",
                subtitle = if (!s.shields) "Off" else listOfNotNull(
                    "ads".takeIf { s.blockAds }, "trackers".takeIf { s.blockTrackers }, "cookie banners".takeIf { s.hideCookieBanners },
                ).joinToString(", ").ifEmpty { "Nothing blocked" }.replaceFirstChar { it.uppercase() },
                icon = Icons.Outlined.Shield,
            ) { onShields() }
            ListRow("Orbit VPN", subtitle = vpnMeta(), icon = Icons.Outlined.VpnLock) { onVpn() }
            Text(
                "Close ghost tabs after",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp),
            )
            Segmented(listOf(5, 15, 30, 60), s.ghostMinutes, Modifier.padding(horizontal = 16.dp), label = { "$it min" }) { set(s.copy(ghostMinutes = it)) }
            Heading("Browsing")
            ToggleRow(
                "Keep playing in the background", "Video and music continue with Orbit minimized, with controls in notifications",
                s.backgroundPlay, Icons.Outlined.PlayCircle,
            ) {
                set(s.copy(backgroundPlay = it))
                if (!it) browser.stopMedia()
            }
            ToggleRow("Desktop sites", null, s.desktopDefault, Icons.Outlined.DesktopWindows) { set(s.copy(desktopDefault = it)) }
            ToggleRow("Vibration", null, s.haptics, Icons.Outlined.Vibration) { set(s.copy(haptics = it)) }
            Heading("Data")
            ListRow("Reopen closed tab", icon = Icons.Outlined.Restore) { onDismiss(); browser.reopenClosed() }
            ListRow("Clear data in ${browser.currentSpace.name}", icon = Icons.Outlined.Delete, titleColor = Orb.Red) {
                browser.clearSpaceData(browser.currentSpaceId)
            }
            Heading("About")
            val wv = remember { runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.versionName }.getOrNull() ?: "unknown" }
            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
            Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(app.orbit.R.drawable.orbit_mark), null, tint = Orb.Text, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Orbit", style = MaterialTheme.typography.titleMedium)
                    Text("Version $version", style = MaterialTheme.typography.bodySmall, color = Orb.Text2)
                }
            }
            val update = Updates.available
            ListRow(
                if (update != null) "Update to ${update.version}" else "Check for updates",
                subtitle = when {
                    Updates.phase == Updates.Phase.CHECKING -> "Checking"
                    update != null -> "A new version of Orbit is ready"
                    Updates.phase == Updates.Phase.FAILED -> Updates.error
                    Updates.upToDate -> "Orbit is up to date"
                    else -> null
                },
                icon = Icons.Outlined.SystemUpdate,
            ) {
                if (update != null) onUpdate()
                else Updates.check(scope) { r -> if (r != null) onUpdate() else if (Updates.phase != Updates.Phase.FAILED) browser.notify("Orbit is up to date") }
            }
            ToggleRow("Check for updates automatically", null, Updates.autoCheck, Icons.Outlined.Autorenew) { Updates.changeAutoCheck(it) }
            ListRow("Open-source licenses", icon = Icons.Outlined.Description) { licenses = true }
            Column(Modifier.padding(horizontal = 20.dp)) {
                AboutLine("Android", Build.VERSION.RELEASE)
                AboutLine("WebView", wv)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Orb.Text2, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Orb.Text)
    }
}

// =============================================================================================
// Tab history
// =============================================================================================

@Composable
fun TrailSheet(tab: Tab?, onDismiss: () -> Unit) {
    val list = remember { tab?.webView?.copyBackForwardList() }
    OrbSheet(onDismiss) {
        Text("Tab history", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp))
        if (list == null || list.size == 0) {
            Text("Nothing here yet", color = Orb.Text2, modifier = Modifier.padding(20.dp))
            return@OrbSheet
        }
        val cur = list.currentIndex
        LazyColumn(Modifier.heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            items((list.size - 1 downTo 0).toList()) { i ->
                val item = list.getItemAtIndex(i)
                val isCur = i == cur
                ListRow(
                    title = item.title.ifBlank { Url.pretty(item.url) },
                    subtitle = Url.pretty(item.url),
                    leading = { SiteIcon(item.url, 24.dp) },
                    titleColor = if (isCur) Orb.Text else Orb.Text2,
                    trailing = if (isCur) ({ Icon(Icons.Outlined.Check, "Current page", tint = Orb.Text, modifier = Modifier.size(18.dp)) }) else null,
                ) { onDismiss(); tab?.webView?.goBackOrForward(i - cur) }
            }
        }
    }
}

// =============================================================================================
// Link long-press menu
// =============================================================================================

@Composable
fun LinkMenu(onDismiss: () -> Unit) {
    val browser = LocalBrowser.current
    val target = browser.linkMenu ?: return
    val url = target.url
    val image = target.image
    val tab = target.tab
    fun act(block: () -> Unit) { onDismiss(); block() }
    OrbSheet(onDismiss) {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SiteIcon(url ?: image ?: "", 24.dp)
            Spacer(Modifier.width(12.dp))
            Text(url ?: image ?: "", style = MaterialTheme.typography.bodySmall, color = Orb.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Hairline()
        if (url != null) {
            MenuRow(Icons.Outlined.Visibility, "Preview") { act { browser.openPeek(url, tab) } }
            MenuRow(Icons.AutoMirrored.Outlined.OpenInNew, "Open in new tab") {
                act { browser.navigate(url, browser.newTab(spaceId = tab.spaceId, ghost = tab.ghost, parent = tab)) }
            }
            MenuRow(Icons.Outlined.Layers, "Open in background") {
                act {
                    val t = browser.newTab(url, spaceId = tab.spaceId, ghost = tab.ghost, select = false, parent = tab)
                    browser.notify("Opened in background", "Show") { browser.select(t) }
                }
            }
            if (!tab.ghost) MenuRow(Icons.Outlined.VisibilityOff, "Open in ghost tab") { act { browser.navigate(url, browser.newTab(ghost = true)) } }
            MenuRow(Icons.Outlined.ContentCopy, "Copy link") { act { browser.copy(url) } }
            MenuRow(Icons.Outlined.Share, "Share link") {
                act {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, url)
                    runCatching { tab.webView?.context?.startActivity(android.content.Intent.createChooser(send, null)) }
                }
            }
        }
        if (image != null) {
            if (url != null) Hairline()
            MenuRow(Icons.Outlined.Image, "Open image") {
                act { browser.navigate(image, browser.newTab(spaceId = tab.spaceId, ghost = tab.ghost, parent = tab)) }
            }
            MenuRow(Icons.Outlined.Download, "Download image") { act { browser.downloadUrl(image, tab) } }
            MenuRow(Icons.Outlined.ContentCopy, "Copy image address") { act { browser.copy(image) } }
        }
    }
}

// =============================================================================================
// Open-source licenses
// =============================================================================================

private data class Component(val name: String, val owner: String, val license: String, val asset: String)

private val Components = listOf(
    Component("Orbit", "Stormyy14 and Orbit contributors", "Apache License 2.0", "Apache-2.0.txt"),
    Component("Jetpack Compose (UI, Foundation, Material 3, Animation)", "The Android Open Source Project", "Apache License 2.0", "Apache-2.0.txt"),
    Component("AndroidX Activity, Core, Lifecycle, WebKit", "The Android Open Source Project", "Apache License 2.0", "Apache-2.0.txt"),
    Component("Material Icons", "Google LLC", "Apache License 2.0", "Apache-2.0.txt"),
    Component("Kotlin standard library, kotlinx.coroutines", "JetBrains s.r.o. and contributors", "Apache License 2.0", "Apache-2.0.txt"),
    Component("Geist and Geist Mono fonts", "The Geist Project Authors", "SIL Open Font License 1.1", "OFL-1.1.txt"),
    Component("Tor and tor-android (Orbit VPN)", "The Tor Project, Inc.; Guardian Project", "BSD 3-Clause License", "Tor-BSD-3-Clause.txt"),
    Component("jtorctl", "The Tor Project, Inc.; Guardian Project", "BSD 3-Clause License", "jtorctl-BSD-3-Clause.txt"),
    Component("OpenSSL (in Tor)", "The OpenSSL Project Authors", "Apache License 2.0", "Apache-2.0.txt"),
    Component("libevent (in Tor)", "Niels Provos, Nick Mathewson and contributors", "BSD 3-Clause License", "libevent-BSD-3-Clause.txt"),
    Component("zlib (in Tor)", "Jean-loup Gailly and Mark Adler", "zlib License", "zlib.txt"),
    Component("Zstandard (in Tor)", "Meta Platforms, Inc. and affiliates", "BSD 3-Clause License", "zstd-BSD-3-Clause.txt"),
    Component("AndroidX LocalBroadcastManager", "The Android Open Source Project", "Apache License 2.0", "Apache-2.0.txt"),
)

@Composable
private fun LicensesView(reading: Component?, onOpen: (Component) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back") { onBack() }
        Text(reading?.name ?: "Open-source licenses", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (reading == null) {
        Components.forEach { c ->
            ListRow(c.name, "${c.owner} · ${c.license}") { onOpen(c) }
        }
        Spacer(Modifier.height(8.dp))
    } else {
        val text = remember(reading) {
            runCatching { context.assets.open("licenses/" + reading.asset).bufferedReader().use { it.readText() } }.getOrDefault("")
        }
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Copyright ${reading.owner}", style = MaterialTheme.typography.bodyMedium, color = Orb.Text2)
            Spacer(Modifier.height(12.dp))
            Text(text, style = MonoSmall, color = Orb.Text)
        }
    }
}
