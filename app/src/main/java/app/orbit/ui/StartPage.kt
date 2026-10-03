package app.orbit.ui

import app.orbit.core.Wallpapers
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orbit.core.Browser
import app.orbit.core.HistoryEntry
import app.orbit.core.Space
import app.orbit.core.Tab
import app.orbit.core.Url
import java.text.DateFormat
import java.util.Date

data class Favorite(val url: String, val name: String, val pinned: Boolean)

@Composable
fun StartPage(
    tab: Tab,
    onPulse: () -> Unit,
    onSpaces: () -> Unit,
    onFlow: () -> Unit,
    onShields: () -> Unit,
    onProfiles: () -> Unit,
    onAddSites: () -> Unit,
    onCustomize: () -> Unit,
) {
    val browser = LocalBrowser.current
    if (tab.ghost) {
        GhostStart(onPulse)
        return
    }
    val space = browser.currentSpace
    val favorites = remember(browser.profileId, browser.history.size, browser.pins.size, space) { favorites(browser, space) }
    val recent = remember(browser.profileId, browser.history.size, space) {
        browser.history.asSequence().filter { it.spaceId == space.id && it.time > space.recentSince }
            .distinctBy { it.url.substringBefore('#') }.distinctBy { it.title.ifBlank { it.url } }.take(6).toList()
    }

    val s = browser.settings
    val context = LocalContext.current
    val wallpaper by produceState<ImageBitmap?>(null, browser.profileId, s.wallpaper) {
        value = if (s.wallpaper == 0L) null else Wallpapers.load(context, browser.profileId)
    }

    Box(Modifier.fillMaxSize().background(Orb.Bg)) {
        wallpaper?.let { img ->
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // Dimmed towards the canvas colour so text and icons stay readable.
            Box(Modifier.fillMaxSize().background(Orb.Bg.copy(alpha = s.wallpaperDim / 100f)))
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(top = if (s.barTop) 68.dp else 0.dp, bottom = if (s.barTop) 32.dp else 120.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 16.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.tap(R8) { onSpaces() }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SpaceGlyph(space, 20.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(space.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Outlined.ExpandMore, "Switch space", tint = Orb.Text2, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.weight(1f))
                if (browser.flowActive) {
                    Row(
                        Modifier.tap(R8) { onFlow() }.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Timer, null, tint = Orb.Text2, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Focus until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(browser.flowUntil)),
                            style = MaterialTheme.typography.labelLarge, color = Orb.Text2,
                        )
                    }
                }
                IconButton(Icons.Outlined.Palette, "Customize", tint = Orb.Text2) { onCustomize() }
                Box(Modifier.size(44.dp).tap(CircleShape) { onProfiles() }, contentAlignment = Alignment.Center) {
                    ProfileBadge(browser.profile, 30.dp, tint = Orb.Text2)
                }
            }
            if (s.showClock) Clock()
            Spacer(Modifier.height(20.dp))
            if (s.showSearch) SearchField("Search or type URL", onPulse)

            if (s.showOrbit) {
                Spacer(Modifier.height(12.dp))
                OrbitView(favorites, space, onSpaces, onAddSites)
            }

            if (s.showRecent && recent.isNotEmpty()) {
                Heading("Recently visited", Modifier.padding(top = 8.dp), action = "Clear") { browser.clearRecent(space.id) }
                recent.forEach { h -> RecentRow(h, tab, browser) }
            }
        }
    }
}

/** A large clock and the date. Reads the time on its own, so only it redraws every second. */
@Composable
private fun Clock() {
    val browser = LocalBrowser.current
    val now = Date(browser.now)
    val datePattern = remember { android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEEdMMMM") }
    Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(now), style = MaterialTheme.typography.displayLarge)
        Text(
            java.text.SimpleDateFormat(datePattern, java.util.Locale.getDefault()).format(now),
            style = MaterialTheme.typography.bodyMedium, color = Orb.Text2,
        )
    }
}

@Composable
private fun RecentRow(h: HistoryEntry, tab: Tab, browser: Browser) {
    ListRow(
        title = h.title.ifBlank { Url.pretty(h.url) },
        subtitle = Url.pretty(h.url),
        leading = { SiteIcon(h.url, 24.dp) },
        trailingText = ago(browser.now - h.time),
        trailing = { IconButton(Icons.Outlined.Close, "Remove from history", tint = Orb.Text3, size = 40.dp) { browser.removeRecent(h) } },
        contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        onLongClick = { browser.removeRecent(h) },
    ) { browser.navigate(h.url, tab) }
}

@Composable
private fun SearchField(hint: String, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(R12)
            .background(Orb.Field)
            .tap(R12) { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = Orb.Text2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(hint, color = Orb.Text2, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
    }
}

private fun favorites(browser: Browser, space: Space): List<Favorite> {
    val cutoff = maxOf(System.currentTimeMillis() - 30L * 86_400_000L, space.orbitSince)
    val counts = HashMap<String, Pair<Int, HistoryEntry>>()
    browser.history.forEach { h ->
        if (h.spaceId != space.id || h.time <= cutoff) return@forEach
        val site = Url.host(h.url)?.let(Url::site) ?: return@forEach
        val prev = counts[site]
        counts[site] = (prev?.first ?: 0) + 1 to (prev?.second ?: h)
    }
    val pinned = browser.pins.map { Favorite(it.url, Url.siteName(it.title, it.url), true) }
    val pinnedSites = pinned.mapNotNull { Url.host(it.url)?.let(Url::site) }.toSet()
    val frequent = counts.entries
        .filter { it.key !in pinnedSites }
        .sortedByDescending { it.value.first }
        .map { (_, v) ->
            val h = v.second
            Favorite("https://" + (Url.host(h.url) ?: ""), Url.siteName(h.title, h.url), false)
        }
    return (pinned + frequent).take(ORBIT_CAPACITY)
}

private const val ORBIT_CAPACITY = 12

/** How many sites sit on each orbit, inner to outer. */
private val RingSizes = listOf(3, 4, 5)
/** Relative orbit radius, inner to outer. */
private val RingRadii = listOf(0.43f, 0.72f, 1f)
/** Inner orbits turn faster, like planets. */
private val RingSpeeds = listOf(1.6f, 1f, 0.68f)
/** Node diameter per ring. */
private val RingNode = listOf(44, 38, 34)
/** Starting angle per ring, so sites on neighbouring orbits don't line up. */
private val RingPhase = listOf(0.35f, 1.25f, 2.2f)
/** Vertical squash of the orbits, which gives the tilted, 3-D look. */
private const val TILT = 0.62f

/**
 * Your most-visited and pinned sites on three tilted orbits around the current space.
 * Closest friends sit on the inner orbit. Drag sideways to spin; sites on the far side of an
 * orbit are drawn smaller and fainter, and pass behind the centre. Starts empty, with a button
 * to add sites.
 */
@Composable
private fun OrbitView(items: List<Favorite>, space: Space, onCenter: () -> Unit, onAdd: () -> Unit) {
    val browser = LocalBrowser.current
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val spin = remember { Animatable(0f) }
    var menuFor by remember { mutableStateOf<Favorite?>(null) }
    val ringColor = Orb.Border

    val rings = buildList {
        var from = 0
        RingSizes.forEach { n ->
            val slice = items.drop(from).take(n)
            if (slice.isNotEmpty()) add(slice)
            from += n
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        val outerRx = maxWidth / 2 - 30.dp
        val outerRy = outerRx * TILT
        val height = outerRy * 2 + 92.dp
        val rxPx = with(density) { outerRx.toPx() }
        val ryPx = with(density) { outerRy.toPx() }
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { height.toPx() }
        val cx = widthPx / 2
        val cy = heightPx / 2 - with(density) { 8.dp.toPx() }

        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { tracker.resetTracking(); scope.launch { spin.stop() } },
                        onDragEnd = {
                            val v = tracker.calculateVelocity().x / rxPx
                            scope.launch { spin.animateDecay(v, exponentialDecay(frictionMultiplier = 1.6f)) }
                        },
                    ) { change, dx ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch { spin.snapTo(spin.value + dx / rxPx) }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // Empty orbits still show their paths, waiting for sites.
                (if (items.isEmpty()) RingSizes.indices else rings.indices).forEach { r ->
                    val rx = rxPx * RingRadii[r]
                    val ry = ryPx * RingRadii[r]
                    drawOval(
                        ringColor,
                        topLeft = Offset(cx - rx, cy - ry),
                        size = Size(rx * 2, ry * 2),
                        style = Stroke(1.dp.toPx()),
                    )
                }
            }

            // The centre: the space you're in. Tap to switch.
            val centerPx = with(density) { 56.dp.toPx() }
            Box(
                Modifier
                    .offset { IntOffset((cx - centerPx / 2).roundToInt(), (cy - centerPx / 2).roundToInt()) }
                    .zIndex(0.5f)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Orb.Bg)
                    .border(1.dp, Orb.BorderStrong, CircleShape)
                    .tap(CircleShape) { haptics.tick(); onCenter() },
                contentAlignment = Alignment.Center,
            ) { SpaceGlyph(space, 24.dp) }

            rings.forEachIndexed { r, sites ->
                val rx = rxPx * RingRadii[r]
                val ry = ryPx * RingRadii[r]
                val nodeDp = RingNode[r].dp
                val nodePx = with(density) { nodeDp.toPx() }
                val labelW = nodeDp + 48.dp
                val labelPx = with(density) { labelW.toPx() }
                sites.forEachIndexed { i, f ->
                    // Minus: the near side of each orbit follows your finger.
                    val a = RingPhase[r] + i * (2f * PI.toFloat() / sites.size) - spin.value * RingSpeeds[r]
                    val x = cx + rx * cos(a)
                    val y = cy + ry * sin(a)
                    val depth = (sin(a) + 1f) / 2f // 0 = far side, 1 = near side
                    Box(
                        Modifier
                            .offset { IntOffset((x - labelPx / 2).roundToInt(), (y - nodePx / 2).roundToInt()) }
                            .zIndex(depth)
                            .width(labelW)
                            .graphicsLayer {
                                val sc = 0.72f + 0.28f * depth
                                scaleX = sc; scaleY = sc
                                alpha = 0.45f + 0.55f * depth
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                            },
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(nodeDp)
                                    .clip(CircleShape)
                                    .background(Orb.Field)
                                    .border(if (f.pinned) 1.5.dp else 0.dp, if (f.pinned) Orb.Contrast else Color.Transparent, CircleShape)
                                    .tap(CircleShape, onLongClick = { haptics.heavy(); menuFor = f }) { haptics.tick(); browser.navigate(f.url) },
                                contentAlignment = Alignment.Center,
                            ) { SiteIcon(f.url, nodeDp * 0.5f, shape = RoundedCornerShape(4.dp), framed = false) }
                            // Only the near side is labelled; the far side stays quiet.
                            val labelAlpha = ((depth - 0.45f) / 0.25f).coerceIn(0f, 1f)
                            Text(
                                f.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = Orb.Text2.copy(alpha = labelAlpha),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .padding(top = 3.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Orb.Bg.copy(alpha = labelAlpha))
                                    .padding(horizontal = 4.dp),
                            )
                        }
                        DropdownMenu(expanded = menuFor == f, onDismissRequest = { menuFor = null }, containerColor = Orb.Surface) {
                            DropdownMenuItem(text = { Text("Add sites") }, onClick = { menuFor = null; onAdd() })
                            DropdownMenuItem(text = { Text(if (f.pinned) "Unpin" else "Pin to inner orbit") }, onClick = {
                                browser.togglePin(f.url, f.name); menuFor = null
                            })
                            DropdownMenuItem(text = { Text("Open in ghost tab") }, onClick = {
                                menuFor = null
                                browser.navigate(f.url, browser.newTab(ghost = true))
                            })
                            DropdownMenuItem(text = { Text("Forget this site", color = Orb.Red) }, onClick = {
                                Url.host(f.url)?.let(browser::forgetSite); menuFor = null
                            })
                            DropdownMenuItem(text = { Text("Clear orbit", color = Orb.Red) }, onClick = {
                                menuFor = null; browser.clearOrbit(space.id)
                            })
                        }
                    }
                }
            }

            if (items.isEmpty()) {
                PrimaryButton(
                    "Add sites",
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                    icon = Icons.Outlined.Add,
                ) { onAdd() }
            } else {
                IconButton(Icons.Outlined.DeleteSweep, "Clear orbit", Modifier.align(Alignment.TopStart), tint = Orb.Text2) {
                    haptics.heavy(); browser.clearOrbit(space.id)
                }
                if (items.size < ORBIT_CAPACITY) {
                    IconButton(Icons.Outlined.Add, "Add sites", Modifier.align(Alignment.TopEnd), tint = Orb.Text2) { haptics.tick(); onAdd() }
                }
            }
        }
    }
}

@Composable
private fun GhostStart(onPulse: () -> Unit) {
    val browser = LocalBrowser.current
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxSize().background(Orb.Bg).statusBarsPadding().padding(top = 28.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.VisibilityOff, null, tint = Orb.Ghost, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("Ghost tab", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Not saved to history. Closes after ${browser.settings.ghostMinutes} min in the background.",
            style = MaterialTheme.typography.bodyMedium,
            color = Orb.Text2,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
        )
        Spacer(Modifier.height(20.dp))
        SearchField("Search privately", onPulse)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Close all ghost tabs", color = Orb.Red) { haptics.heavy(); browser.burnGhosts() }
            SecondaryButton("Back to ${browser.currentSpace.name}") { browser.switchSpace(browser.currentSpaceId) }
        }
    }
}
