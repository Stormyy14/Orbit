package app.orbit.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.orbit.core.Images
import app.orbit.core.ReaderBlock
import app.orbit.core.ReaderDoc
import app.orbit.core.Tab
import app.orbit.core.Url
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Hosts a WebView inside Compose, swapping the child whenever the shown tab changes. */
@Composable
fun WebHost(view: View?, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> FrameLayout(ctx) },
        modifier = modifier,
        update = { container -> container.show(view) },
        onRelease = { it.removeAllViews() },
    )
}

private fun FrameLayout.show(view: View?) {
    if (view != null && childCount == 1 && getChildAt(0) === view) return
    removeAllViews()
    if (view != null) {
        (view.parent as? ViewGroup)?.removeView(view)
        addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

// =============================================================================================
// Toast
// =============================================================================================

@Composable
fun NoticeHost(modifier: Modifier = Modifier) {
    val browser = LocalBrowser.current
    val n = browser.notice
    var shown by remember { mutableStateOf(n) }
    LaunchedEffect(n?.id) {
        if (n != null) {
            shown = n
            delay(if (n.action != null) 4200 else 2400)
            browser.dismissNotice(n)
        }
    }
    AnimatedVisibility(
        visible = n != null,
        enter = fadeIn(tween(150)) + slideInVertically(tween(150)) { it / 3 },
        exit = fadeOut(tween(120)),
        modifier = modifier,
    ) {
        val cur = shown ?: return@AnimatedVisibility
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(R12)
                .background(Orb.Surface)
                .border(1.dp, Orb.BorderStrong, R12)
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(cur.text, style = MaterialTheme.typography.bodyMedium, color = Orb.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (cur.action != null) {
                Text(
                    cur.action,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Orb.Text,
                    modifier = Modifier.tap(R8) { cur.onAction?.invoke(); browser.dismissNotice(cur) }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// =============================================================================================
// Find in page
// =============================================================================================

@Composable
fun FindBar(modifier: Modifier = Modifier) {
    val browser = LocalBrowser.current
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(q) { delay(120); browser.find(q) }
    Row(
        modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(52.dp)
            .clip(R12)
            .background(Orb.Surface)
            .border(1.dp, Orb.BorderStrong, R12)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (q.isEmpty()) Text("Find in page", color = Orb.Text3, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                q, { q = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Orb.Text),
                cursorBrush = SolidColor(Orb.Text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { browser.findNext(true) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (q.isNotEmpty()) {
            Text(
                "${browser.findCurrent} of ${browser.findTotal}",
                color = Orb.Text2, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TNUM),
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        IconButton(Icons.Outlined.KeyboardArrowUp, "Previous match", size = 40.dp) { browser.findNext(false) }
        IconButton(Icons.Outlined.KeyboardArrowDown, "Next match", size = 40.dp) { browser.findNext(true) }
        IconButton(Icons.Outlined.Close, "Close", tint = Orb.Text2, size = 40.dp) { browser.closeFind() }
    }
}

// =============================================================================================
// Flow
// =============================================================================================

/** Time remaining as a large tabular figure over a thin, honest progress bar. */
@Composable
fun FlowTimer(leftMs: Long, totalMs: Long, modifier: Modifier = Modifier) {
    val frac = if (totalMs <= 0) 0f else (leftMs / totalMs.toFloat()).coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatDuration(leftMs), style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.width(8.dp))
            Text("remaining", style = MaterialTheme.typography.bodyMedium, color = Orb.Text2, modifier = Modifier.padding(bottom = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Orb.Field)) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Orb.Text))
        }
    }
}

@Composable
fun FlowInterstitial(tab: Tab) {
    val browser = LocalBrowser.current
    val haptics = rememberHaptics()
    val url = tab.flowBlocked ?: return
    Column(
        Modifier.fillMaxSize().background(Orb.Bg).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Timer, null, tint = Orb.Text2, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(16.dp))
        Text("${Url.pretty(url)} is blocked", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "You're focusing until " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(browser.flowUntil)) + ".",
            style = MaterialTheme.typography.bodyLarge, color = Orb.Text2,
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton("Go back", Modifier.fillMaxWidth()) {
            haptics.confirm()
            tab.flowBlocked = null
            if (!browser.goBack(tab)) browser.goHome(tab)
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Open anyway for 5 min", Modifier.fillMaxWidth(), color = Orb.Text2) { browser.allowFlowBypass(tab) }
    }
}

// =============================================================================================
// Peek — a preview card; swipe up to keep it as a tab, down to dismiss
// =============================================================================================

@Composable
fun PeekOverlay(peek: Tab) {
    val browser = LocalBrowser.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dy = remember { Animatable(0f) }
    val enter = remember { Animatable(1f) }
    LaunchedEffect(peek.id) { enter.animateTo(0f, tween(220)) }
    val threshold = with(density) { 90.dp.toPx() }
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = (if (Orb.palette.dark) 0.6f else 0.32f) * (1f - enter.value)))
                .tap(RoundedCornerShape(0.dp)) { browser.closePeek() },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .graphicsLayer { translationY = dy.value.coerceAtLeast(-threshold) + enter.value * size.height }
                .clip(shape)
                .background(Orb.Surface)
                .border(1.dp, Orb.Border, shape),
        ) {
            Column(
                Modifier.fillMaxWidth().pointerInput(peek.id) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                when {
                                    dy.value < -threshold * 0.8f -> { haptics.confirm(); browser.promotePeek() }
                                    dy.value > threshold -> { haptics.tick(); browser.closePeek() }
                                    else -> dy.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy))
                                }
                            }
                        },
                    ) { c, amount -> c.consume(); scope.launch { dy.snapTo(dy.value + amount) } }
                },
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(32.dp, 4.dp).clip(CircleShape).background(Orb.BorderStrong))
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (peek.loading) OrbitSpinner(24.dp) else SiteIcon(peek.url, 24.dp, peek.favicon)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(peek.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Url.pretty(peek.url), style = MaterialTheme.typography.bodySmall, color = Orb.Text2, maxLines = 1)
                    }
                    IconButton(Icons.AutoMirrored.Outlined.OpenInNew, "Open as tab") { haptics.confirm(); browser.promotePeek() }
                    IconButton(Icons.Outlined.Close, "Close", tint = Orb.Text2) { browser.closePeek() }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Orb.Border))
            }
            WebHost(peek.webView, Modifier.fillMaxSize())
        }
    }
}

// =============================================================================================
// Reader
// =============================================================================================

private enum class ReaderTheme(val bg: Color, val fg: Color, val dim: Color, val rule: Color, val label: String) {
    Dark(Color(0xFF0A0A0A), Color(0xFFE8E8E8), Color(0xFF8F8F8F), Color(0xFF262626), "Dark"),
    Paper(Color(0xFFF6F3EC), Color(0xFF26231F), Color(0xFF7A7469), Color(0xFFE2DDD2), "Paper"),
    Light(Color(0xFFFFFFFF), Color(0xFF171717), Color(0xFF666666), Color(0xFFEBEBEB), "Light"),
}

@Composable
fun ReaderOverlay(doc: ReaderDoc, onClose: () -> Unit) {
    var theme by remember { mutableStateOf(if (Orb.palette.dark) ReaderTheme.Dark else ReaderTheme.Light) }
    var scale by remember { mutableFloatStateOf(1f) }
    var serif by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val progress by remember {
        derivedStateOf {
            val total = list.layoutInfo.totalItemsCount
            if (total == 0) 0f else (list.firstVisibleItemIndex + 1) / total.toFloat()
        }
    }
    val family = if (serif) FontFamily.Serif else Geist
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val showHero = doc.hero.startsWith("http") && doc.blocks.take(6).none { it.type == "img" }
    val animatedProgress by animateFloatAsState(progress, tween(150), label = "read")

    Box(Modifier.fillMaxSize().background(theme.bg)) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = topInset + 72.dp, bottom = 120.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(doc.site, style = MaterialTheme.typography.labelMedium, color = theme.dim)
                Spacer(Modifier.height(10.dp))
                Text(doc.title, fontFamily = family, fontSize = (30 * scale).sp, lineHeight = (37 * scale).sp, fontWeight = FontWeight.SemiBold, color = theme.fg)
                Spacer(Modifier.height(10.dp))
                Text(
                    listOf(doc.byline, "${doc.minutes} min read").filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = theme.dim,
                )
                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(theme.rule))
                Spacer(Modifier.height(8.dp))
                if (showHero) RemoteImage(doc.hero)
            }
            items(doc.blocks) { b -> ReaderBlockView(b, theme, family, scale) }
        }
        Column(Modifier.fillMaxWidth().background(theme.bg).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(Icons.Outlined.Close, "Close reader", tint = theme.fg) { onClose() }
                Text(Url.pretty(doc.url), color = theme.dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(Icons.Outlined.TextFields, "Text settings", tint = theme.fg) { panel = !panel }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(theme.rule)) {
                Box(Modifier.fillMaxWidth(animatedProgress).fillMaxHeight().background(theme.fg))
            }
            AnimatedVisibility(panel, enter = expandVertically(tween(150)), exit = shrinkVertically(tween(120))) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderTheme.entries.forEach { t ->
                            Box(
                                Modifier.weight(1f).height(40.dp).clip(R8).background(t.bg)
                                    .border(if (theme == t) 1.5.dp else 1.dp, if (theme == t) theme.fg else theme.rule, R8)
                                    .tap(R8) { theme = t },
                                contentAlignment = Alignment.Center,
                            ) { Text(t.label, color = t.fg, style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("A", color = theme.fg, fontSize = 13.sp, fontFamily = Geist)
                        Slider(
                            scale, { scale = it }, valueRange = 0.8f..1.5f, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                            colors = SliderDefaults.colors(thumbColor = theme.fg, activeTrackColor = theme.fg, inactiveTrackColor = theme.rule),
                        )
                        Text("A", color = theme.fg, fontSize = 21.sp, fontFamily = Geist)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(true to "Serif", false to "Sans").forEach { (v, l) ->
                            Box(
                                Modifier.weight(1f).height(40.dp).clip(R8)
                                    .border(if (serif == v) 1.5.dp else 1.dp, if (serif == v) theme.fg else theme.rule, R8)
                                    .tap(R8) { serif = v },
                                contentAlignment = Alignment.Center,
                            ) { Text(l, color = theme.fg, fontFamily = if (v) FontFamily.Serif else Geist) }
                        }
                    }
                }
            }
            if (panel) Box(Modifier.fillMaxWidth().height(1.dp).background(theme.rule))
        }
    }
}

@Composable
private fun ReaderBlockView(b: ReaderBlock, theme: ReaderTheme, family: FontFamily, scale: Float) {
    when (b.type) {
        "h1", "h2" -> Text(b.text, fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = (23 * scale).sp, lineHeight = (30 * scale).sp, color = theme.fg, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
        "h3", "h4" -> Text(b.text, fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = (19 * scale).sp, lineHeight = (26 * scale).sp, color = theme.fg, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        "blockquote" -> Row(Modifier.padding(vertical = 10.dp)) {
            Box(Modifier.width(2.dp).height((24 * scale * 2).dp).background(theme.fg))
            Spacer(Modifier.width(14.dp))
            Text(b.text, fontFamily = family, fontSize = (18 * scale).sp, lineHeight = (28 * scale).sp, color = theme.dim)
        }
        "li" -> Row(Modifier.padding(vertical = 4.dp)) {
            Text("–  ", color = theme.dim, fontSize = (17 * scale).sp)
            Text(b.text, fontFamily = family, fontSize = (17 * scale).sp, lineHeight = (27 * scale).sp, color = theme.fg)
        }
        "pre" -> Text(
            b.text, fontFamily = GeistMono, fontSize = (13 * scale).sp, color = theme.fg,
            modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth().clip(R8).border(1.dp, theme.rule, R8).padding(12.dp),
        )
        "img" -> RemoteImage(b.text)
        "figcaption" -> Text(b.text, fontSize = (13 * scale).sp, color = theme.dim, fontFamily = Geist, modifier = Modifier.padding(bottom = 12.dp))
        else -> Text(b.text, fontFamily = family, fontSize = (18 * scale).sp, lineHeight = (29 * scale).sp, color = theme.fg, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun RemoteImage(url: String) {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { bmp = Images.remote(url) }
    val b = bmp ?: return
    Image(
        b, null,
        Modifier.fillMaxWidth().padding(vertical = 12.dp).clip(R8).aspectRatio(b.width / b.height.toFloat().coerceAtLeast(1f)),
        contentScale = ContentScale.Crop,
    )
}

/** Shown until a fresh WebView paints, so a cold load reads as "arriving" rather than blank. */
@Composable
fun LoadingVeil(tab: Tab) {
    Box(Modifier.fillMaxSize().background(Orb.Bg)) {
        Column(Modifier.align(Alignment.Center).width(200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SiteIcon(tab.url, 36.dp, tab.favicon)
            Spacer(Modifier.height(14.dp))
            Text(Url.pretty(tab.url), style = MaterialTheme.typography.bodyMedium, color = Orb.Text2, maxLines = 1)
            Spacer(Modifier.height(16.dp))
            OrbitSpinner(28.dp)
        }
    }
}
