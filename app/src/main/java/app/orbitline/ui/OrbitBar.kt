package app.orbitline.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orbitline.core.Tab
import app.orbitline.core.Url
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// =============================================================================================
// Orbit menu: long-press the bar, slide to an action, release.
// =============================================================================================

data class RadialItem(val icon: ImageVector, val label: String, val enabled: Boolean = true, val action: () -> Unit)

private const val ARC_START = 196.0
private const val ARC_SWEEP = 148.0

class RadialState(
    private val radiusPx: Float,
    private val marginPx: Float,
    private val deadPx: Float,
    private val liftPx: Float,
) {
    var active by mutableStateOf(false)
        private set
    var center by mutableStateOf(Offset.Zero)
        private set
    var selected by mutableIntStateOf(-1)
        private set
    var items by mutableStateOf<List<RadialItem>>(emptyList())
        private set
    var rootWidth by mutableFloatStateOf(0f)
    val radius get() = radiusPx

    fun angleOf(i: Int): Double = Math.toRadians(ARC_START + i * ARC_SWEEP / (items.size - 1).coerceAtLeast(1))

    fun begin(origin: Offset, list: List<RadialItem>) {
        items = list
        val minX = radiusPx + marginPx
        val maxX = (rootWidth - radiusPx - marginPx).coerceAtLeast(minX)
        center = Offset(origin.x.coerceIn(minX, maxX), origin.y - liftPx)
        selected = -1
        active = true
    }

    /** Returns true if the selection changed. */
    fun move(pointer: Offset): Boolean {
        val dx = pointer.x - center.x
        val dy = pointer.y - center.y
        var next = -1
        if (sqrt(dx * dx + dy * dy) > deadPx) {
            var a = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
            if (a < 0) a += 360.0
            if (a in 0.0..90.0) a = ARC_START + ARC_SWEEP else if (a in 90.0..180.0) a = ARC_START
            var best = Double.MAX_VALUE
            items.indices.forEach { i ->
                val d = abs(Math.toDegrees(angleOf(i)) - a)
                if (d < best && d < 32) { best = d; next = i }
            }
        }
        if (next >= 0 && !items[next].enabled) next = -1
        val changed = next != selected
        selected = next
        return changed
    }

    fun release(): RadialItem? {
        val pick = items.getOrNull(selected)
        active = false
        selected = -1
        return pick
    }
}

@Composable
fun RadialOverlay(state: RadialState) {
    val appear by animateFloatAsState(if (state.active) 1f else 0f, tween(140), label = "radial")
    if (appear <= 0.01f) return
    val density = LocalDensity.current
    val itemSize = 52.dp
    val itemPx = with(density) { itemSize.toPx() }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = appear }) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (Orb.palette.dark) 0.6f else 0.35f)))
        Canvas(Modifier.fillMaxSize()) {
            val r = state.radius
            drawArc(
                Orb.BorderStrong, ARC_START.toFloat(), ARC_SWEEP.toFloat(), false,
                topLeft = Offset(state.center.x - r, state.center.y - r), size = Size(r * 2, r * 2),
                style = Stroke(1.dp.toPx()),
            )
            drawCircle(Orb.Surface, radius = 6.dp.toPx(), center = state.center)
            drawCircle(Orb.Text, radius = 6.dp.toPx(), center = state.center, style = Stroke(1.5.dp.toPx()))
        }
        state.items.forEachIndexed { i, item ->
            val a = state.angleOf(i)
            val x = state.center.x + (cos(a) * state.radius).toFloat()
            val y = state.center.y + (sin(a) * state.radius).toFloat()
            val sel = state.selected == i
            Box(
                Modifier
                    .offset { IntOffset((x - itemPx / 2).roundToInt(), (y - itemPx / 2).roundToInt()) }
                    .size(itemSize)
                    .clip(CircleShape)
                    .background(if (sel) Orb.Contrast else Orb.Surface)
                    .border(1.dp, if (sel) Orb.Contrast else Orb.BorderStrong, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    item.icon, item.label,
                    tint = when {
                        !item.enabled -> Orb.Text3.copy(alpha = 0.5f)
                        sel -> Orb.OnContrast
                        else -> Orb.Text
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Box(
            Modifier
                .offset { IntOffset(0, (state.center.y - state.radius - 84.dp.toPx()).roundToInt()) }
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                state.items.getOrNull(state.selected)?.label ?: "Slide to choose",
                style = MaterialTheme.typography.titleSmall,
                color = if (state.selected >= 0) Orb.Text else Orb.Text2,
                modifier = Modifier
                    .clip(R8)
                    .background(Orb.Surface)
                    .border(1.dp, Orb.Border, R8)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

// =============================================================================================
// Orbit bar
// =============================================================================================

@Composable
fun OrbitBar(
    tab: Tab?,
    tabCount: Int,
    radial: RadialState,
    radialItems: () -> List<RadialItem>,
    onPulse: () -> Unit,
    onDeck: () -> Unit,
    onMenu: () -> Unit,
    onSpaces: () -> Unit,
    onShields: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val browser = LocalBrowser.current
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var barOrigin by remember { mutableStateOf(Offset.Zero) }
    val collapsed = browser.barCollapsed && tab != null && !tab.showHome

    val switchPx = with(density) { 80.dp.toPx() }
    val deckPx = with(density) { 54.dp.toPx() }
    val reloadPx = with(density) { 44.dp.toPx() }
    val settle = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    val items by rememberUpdatedState(radialItems)
    val deck by rememberUpdatedState(onDeck)

    val gestures = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var total = Offset.Zero
            var dragging = false
            var released = false
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id }
                    if (c == null || !c.pressed) { released = true; return@withTimeoutOrNull }
                    total += c.positionChange()
                    if (total.getDistance() > viewConfiguration.touchSlop) { dragging = true; return@withTimeoutOrNull }
                }
            }
            if (released) return@awaitEachGesture

            if (!dragging) {
                haptics.heavy()
                radial.begin(barOrigin + down.position, items())
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    c.consume()
                    if (!c.pressed) break
                    if (radial.move(barOrigin + c.position) && radial.selected >= 0) haptics.tick()
                }
                radial.release()?.let { haptics.confirm(); it.action() }
                return@awaitEachGesture
            }

            val horizontal = abs(total.x) > abs(total.y)
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                total += c.positionChange()
                c.consume()
                scope.launch {
                    if (horizontal) offsetX.snapTo(total.x * 0.5f)
                    else offsetY.snapTo((total.y * 0.35f).coerceIn(-56f * density.density, 20f * density.density))
                }
            }

            scope.launch {
                when {
                    horizontal && abs(total.x) > switchPx -> {
                        val dir = if (total.x < 0) 1 else -1
                        if (browser.switchRelative(dir)) {
                            haptics.confirm()
                            offsetX.snapTo(dir * switchPx * 0.6f)
                        } else haptics.reject()
                        offsetX.animateTo(0f, settle)
                    }
                    !horizontal && total.y < -deckPx -> {
                        haptics.confirm(); deck()
                        offsetY.animateTo(0f, settle)
                    }
                    !horizontal && total.y > reloadPx -> {
                        haptics.confirm(); browser.reload()
                        offsetY.animateTo(0f, settle)
                    }
                    else -> {
                        launch { offsetX.animateTo(0f, settle) }
                        offsetY.animateTo(0f, settle)
                    }
                }
            }
        }
    }

    val ghost = tab?.ghost == true
    val edge = if (ghost) Orb.Ghost.copy(alpha = 0.7f) else Orb.Border

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .onGloballyPositioned { barOrigin = it.positionInRoot() }
            .graphicsLayer { translationY = offsetY.value },
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedContent(
            targetState = collapsed,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) using SizeTransform(clip = false) },
            label = "bar",
        ) { mini ->
            if (mini) {
                Box(
                    Modifier
                        .then(gestures)
                        .clip(R8)
                        .background(Orb.Surface)
                        .border(1.dp, edge, R8)
                        .tap(R8) { browser.barCollapsed = false },
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (tab?.loading == true) OrbitSpinner(14.dp) else LockGlyph(tab, 12)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            tab?.let { Url.pretty(it.url) } ?: "",
                            color = Orb.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = Geist,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(R12)
                        .background(Orb.Surface)
                        .border(1.dp, edge, R12)
                        .then(gestures),
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 4.dp).graphicsLayer {
                            translationX = offsetX.value
                            alpha = 1f - (abs(offsetX.value) / (switchPx * 3f)).coerceIn(0f, 0.5f)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(44.dp).tap(CircleShape) { haptics.tick(); onSpaces() }, contentAlignment = Alignment.Center) {
                            if (ghost) Icon(Icons.Outlined.VisibilityOff, "Ghost tab", tint = Orb.Ghost, modifier = Modifier.size(20.dp))
                            else SpaceGlyph(browser.currentSpace, 20.dp, tint = Orb.Text2)
                        }
                        Row(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .tap(R8) { haptics.tick(); onPulse() }
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (tab == null || tab.showHome) {
                                Icon(Icons.Outlined.Search, null, tint = Orb.Text3, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (ghost) "Search privately" else "Search or type URL",
                                    color = Orb.Text3, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                if (tab.loading) OrbitSpinner(18.dp) else LockGlyph(tab, 14)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    Url.pretty(tab.url),
                                    color = Orb.Text, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (browser.flowActive) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Outlined.Timer, "Flow active", tint = Orb.Text2, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                        if (tab != null && !tab.showHome) ShieldBadge(tab, onShields)
                        TabCounter(tabCount, ghost, onDeck)
                        IconButton(Icons.Outlined.MoreHoriz, "Menu") { haptics.tick(); onMenu() }
                    }
                    val hint = (abs(offsetX.value) / switchPx).coerceIn(0f, 1f)
                    if (hint > 0.05f) {
                        Icon(
                            if (offsetX.value > 0) Icons.AutoMirrored.Outlined.ArrowBack else Icons.AutoMirrored.Outlined.ArrowForward,
                            null,
                            tint = Orb.Text2.copy(alpha = hint),
                            modifier = Modifier
                                .align(if (offsetX.value > 0) Alignment.CenterStart else Alignment.CenterEnd)
                                .padding(horizontal = 14.dp)
                                .size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LockGlyph(tab: Tab?, size: Int) {
    val secure = tab?.url?.let(Url::isSecure) ?: true
    Icon(
        if (secure) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
        if (secure) "Secure" else "Not secure",
        tint = if (secure) Orb.Text3 else Orb.Red,
        modifier = Modifier.size(size.dp),
    )
}

@Composable
private fun ShieldBadge(tab: Tab, onClick: () -> Unit) {
    val browser = LocalBrowser.current
    val haptics = rememberHaptics()
    val on = browser.shieldsOn(tab)
    Row(
        Modifier.height(44.dp).tap(R8) { haptics.tick(); onClick() }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Shield, "Shields", tint = if (on) Orb.Text2 else Orb.Text3.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        if (on && tab.blockedCount > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                if (tab.blockedCount > 99) "99+" else tab.blockedCount.toString(),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TNUM),
                color = Orb.Text2,
            )
        }
    }
}

@Composable
private fun TabCounter(count: Int, ghost: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val color = if (ghost) Orb.Ghost else Orb.Text
    Box(Modifier.size(44.dp).tap(CircleShape) { haptics.tick(); onClick() }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(22.dp).border(1.5.dp, color, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (count > 99) "∞" else count.toString(),
                color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = Geist,
            )
        }
    }
}
