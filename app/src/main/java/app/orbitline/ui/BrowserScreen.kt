package app.orbitline.ui

import android.app.Activity
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.orbitline.core.Browser

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(browser: Browser, onExit: () -> Unit) {
    val tab = browser.current
    val ghost = tab?.ghost == true
    val accent = if (ghost) Orb.Ghost else Orb.Text
    var pulse by remember { mutableStateOf(false) }
    var deck by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<SheetKind?>(null) }
    val density = LocalDensity.current
    val radial = remember {
        with(density) { RadialState(radiusPx = 120.dp.toPx(), marginPx = 30.dp.toPx(), deadPx = 36.dp.toPx(), liftPx = 26.dp.toPx()) }
    }
    val page = tab != null && !tab.showHome
    LaunchedEffect(browser.externalOpens) {
        if (browser.externalOpens > 0) { pulse = false; deck = false; sheet = null; browser.linkMenu = null; browser.reader = null }
    }

    // Ghost tabs are kept out of screenshots, screen recordings and the recent-apps preview.
    val hostView = LocalView.current
    DisposableEffect(ghost) {
        val window = (hostView.context as? Activity)?.window
        if (ghost) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }

    // Create (or wake from hibernation) the visible tab's WebView once we're on screen.
    LaunchedEffect(tab?.id, page) { if (tab != null && page) browser.ensureWebView(tab) }
    val imeVisible = WindowInsets.isImeVisible

    val topTint by animateColorAsState(
        if (!page || tab?.flowBlocked != null || tab?.painted != true) Orb.Bg else tab.themeColor ?: Orb.Bg, tween(350), label = "top",
    )

    fun open(kind: SheetKind) { sheet = kind }

    val commands = remember(browser.spaces.toList(), tab?.id) {
        buildList {
            add(Command("New tab", "Open a new tab", Icons.Outlined.Add, "open") { browser.newTab(); pulse = true })
            add(Command("Ghost tab", "New private tab", Icons.Outlined.VisibilityOff, "incognito private burner") { browser.newTab(ghost = true); pulse = true })
            add(Command("Reader view", "Just the article", Icons.AutoMirrored.Outlined.MenuBook, "read article") { browser.openReader() })
            add(Command("Find in page", "Search text on this page", Icons.Outlined.FindInPage, "search text") { browser.findOpen = true })
            add(Command("Hide elements", "Remove parts of this page", Icons.Outlined.AutoFixHigh, "zap block remove") { browser.toggleZap() })
            add(Command("Focus", "Block distracting sites", Icons.Outlined.Timer, "flow pomodoro timer") { open(SheetKind.Flow) })
            add(Command("Shields", "Tracker blocking", Icons.Outlined.Shield, "privacy block ads") { open(SheetKind.Shields) })
            add(Command("Spaces", "Switch or edit spaces", Icons.Outlined.Layers, "profiles containers") { open(SheetKind.Spaces) })
            add(Command("Tab history", "Pages visited in this tab", Icons.Outlined.History, "trail back") { open(SheetKind.Trail) })
            add(Command("Desktop site", "Request the desktop version", Icons.Outlined.DesktopWindows, "request desktop") { browser.toggleDesktop() })
            add(Command("Share", "Send this page", Icons.Outlined.Share, "send") { browser.share() })
            add(Command("Copy link", "Copy this page's address", Icons.Outlined.ContentCopy, "url clipboard") { browser.current?.let { browser.copy(it.url) } })
            add(Command("Add to favorites", "Show on the start page", Icons.Outlined.Star, "bookmark pin favourite") {
                browser.current?.takeIf { !it.showHome }?.let { browser.togglePin(it.url, it.title) }
            })
            add(Command("Reopen closed tab", "Undo the last close", Icons.Outlined.Restore, "undo restore") { browser.reopenClosed() })
            add(Command("Close ghost tabs", "Close all private tabs", Icons.Outlined.LocalFireDepartment, "clear private") { browser.burnGhosts() })
            add(Command("Dark websites", "Darken pages in dark mode", Icons.Outlined.DarkMode, "night theme") {
                browser.updateSettings(browser.settings.copy(darkPages = !browser.settings.darkPages))
            })
            add(Command("Reload", "Refresh this page", Icons.Outlined.Refresh, "refresh") { browser.reload() })
            add(Command("Close tab", "Close the current tab", Icons.Outlined.Close, "") { browser.current?.let { browser.closeTab(it) } })
            add(Command("Settings", "Search engine, privacy", Icons.Outlined.Settings, "preferences options") { open(SheetKind.Settings) })
            browser.spaces.forEach { s ->
                add(Command("Switch to ${s.name}", "Space", spaceIcon(s.icon), "space") { browser.switchSpace(s.id) })
            }
        }
    }

    val radialItems = {
        val t = browser.current
        val p = t != null && !t.showHome
        listOf(
            RadialItem(Icons.AutoMirrored.Outlined.ArrowBack, "Back", p) { browser.goBack() },
            RadialItem(Icons.Outlined.Refresh, "Reload", p) { browser.reload() },
            RadialItem(Icons.AutoMirrored.Outlined.MenuBook, "Reader", p) { browser.openReader() },
            RadialItem(Icons.Outlined.Add, "New tab") { browser.newTab(); pulse = true },
            RadialItem(Icons.Outlined.VisibilityOff, "Ghost tab") { browser.newTab(ghost = true); pulse = true },
            RadialItem(Icons.AutoMirrored.Outlined.ArrowForward, "Forward", t?.canForward == true || (t?.showHome == true && t.webView != null)) { browser.goForward() },
        )
    }

    BackHandler {
        val t = browser.current
        when {
            browser.customView != null -> browser.exitFullscreen()
            radial.active -> Unit
            pulse -> pulse = false
            deck -> deck = false
            browser.reader != null -> browser.reader = null
            browser.peek != null -> browser.closePeek()
            browser.findOpen -> browser.closeFind()
            t?.zapMode == true -> browser.toggleZap(t)
            t?.flowBlocked != null -> { t.flowBlocked = null; if (!browser.goBack(t)) browser.goHome(t) }
            browser.goBack() -> Unit
            t != null && t.parentId != null && browser.tabs.any { it.id == t.parentId } -> browser.closeTab(t, undoable = false)
            else -> onExit()
        }
    }

    val overlay = pulse || deck || browser.reader != null || browser.peek != null || radial.active || tab?.flowBlocked != null
    val barTone = if (page && !overlay) topTint else Orb.Bg
    StatusBarIcons(light = barTone.luminance() > 0.55f, lightNav = !Orb.palette.dark)

    CompositionLocalProvider(LocalAccent provides accent) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Orb.Bg)
                .onSizeChanged { radial.rootWidth = it.width.toFloat() },
        ) {
            Column(Modifier.fillMaxSize().imePadding()) {
                if (page) Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(topTint))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (tab != null && !tab.showHome) WebHost(tab.webView, Modifier.fillMaxSize())
                    if (tab != null && !tab.showHome && !tab.painted) LoadingVeil(tab)
                    if (tab != null && tab.showHome) {
                        StartPage(
                            tab,
                            onPulse = { pulse = true },
                            onSpaces = { open(SheetKind.Spaces) },
                            onFlow = { open(SheetKind.Flow) },
                            onShields = { open(SheetKind.Shields) },
                        )
                    }
                    if (tab?.flowBlocked != null) FlowInterstitial(tab)
                }
                // The dock: keeps the tucked pill from ever covering page content.
                if (page && !imeVisible) {
                    Spacer(Modifier.fillMaxWidth().navigationBarsPadding().height(50.dp).background(Orb.Bg))
                }
            }

            val barVisible = !pulse && !deck && !browser.findOpen && browser.reader == null && !(imeVisible && page)
            AnimatedVisibility(
                visible = barVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            ) {
                OrbitBar(
                    tab = tab,
                    tabCount = if (ghost) browser.ghostTabs.size else browser.tabsIn(browser.currentSpaceId).size,
                    radial = radial,
                    radialItems = radialItems,
                    onPulse = { pulse = true },
                    onDeck = { deck = true },
                    onMenu = { open(SheetKind.Menu) },
                    onSpaces = { open(SheetKind.Spaces) },
                    onShields = { open(SheetKind.Shields) },
                )
            }

            if (browser.findOpen) {
                FindBar(Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding())
            }

            NoticeHost(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = if (barVisible) 86.dp else 20.dp),
            )

            AnimatedVisibility(
                visible = deck,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(120)),
            ) { Deck(onClose = { deck = false }, onSpaces = { open(SheetKind.Spaces) }) }

            browser.peek?.let { PeekOverlay(it) }

            AnimatedVisibility(
                visible = browser.reader != null,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(120)),
            ) {
                val doc = browser.reader
                if (doc != null) ReaderOverlay(doc) { browser.reader = null }
            }

            AnimatedVisibility(
                visible = pulse,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(100)),
            ) { Pulse(browser.current, commands) { pulse = false } }

            RadialOverlay(radial)

            browser.customView?.let { FullscreenHost(it) }
        }

        when (sheet) {
            SheetKind.Menu -> PageMenu(tab, ::open) { sheet = null }
            SheetKind.Shields -> ShieldsSheet(tab) { sheet = null }
            SheetKind.Spaces -> SpacesSheet { sheet = null }
            SheetKind.Flow -> FlowSheet { sheet = null }
            SheetKind.Settings -> SettingsSheet { sheet = null }
            SheetKind.Trail -> TrailSheet(tab) { sheet = null }
            null -> Unit
        }
        if (browser.linkMenu != null) LinkMenu { browser.linkMenu = null }
    }
}

@Composable
private fun StatusBarIcons(light: Boolean, lightNav: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val c = WindowCompat.getInsetsController(window, view)
        c.isAppearanceLightStatusBars = light
        c.isAppearanceLightNavigationBars = lightNav
    }
}

@Composable
private fun FullscreenHost(view: View) {
    val host = LocalView.current
    DisposableEffect(view) {
        val window = (host.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, host) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx -> android.widget.FrameLayout(ctx) }, update = { c ->
            if (c.childCount == 0 || c.getChildAt(0) !== view) {
                c.removeAllViews()
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                c.addView(view)
            }
        }, modifier = Modifier.fillMaxSize())
    }
}
