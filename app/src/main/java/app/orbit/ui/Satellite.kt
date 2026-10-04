package app.orbit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.orbit.core.Browser
import app.orbit.core.Url
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val HandleW = 26.dp
private val HandleH = 58.dp
private val Node = 48.dp
private val LabelW = 76.dp
private val MaxRadius = 140.dp
/** Room under the lowest site for its name. */
private val LabelRoom = 20.dp
/** Angle between neighbouring sites on the half orbit. */
private const val STEP = (PI / 5).toFloat()

/**
 * Satellite: a small handle on the left or right edge, over pages and fullscreen games alike.
 * Tap it and the orbit sites you picked for it (up to five) swing out on a half orbit around it;
 * tap one to jump to it, long-press to change which ones. Drag the handle to move it up and down
 * or to the other side. It fades back when left alone.
 */
@Composable
fun Satellite(onEdit: () -> Unit) {
    val browser = LocalBrowser.current
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val s = browser.settings
    val sites = browser.satellitePins.map { Favorite(it.url, Url.siteName(it.title, it.url), true) }
    val count = sites.size.coerceAtLeast(1) // Empty, it offers a button to pick sites.

    var open by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    /** The half orbit's spin as it opens: 0 = just starting to whirl, 1 = settled. */
    val spin = remember { Animatable(1f) }
    val nodes = remember { List(Browser.SATELLITE_SIZE) { Animatable(0f) } }
    var left by remember(s.satelliteLeft) { mutableStateOf(s.satelliteLeft) }
    var y by remember(s.satelliteY) { mutableFloatStateOf(s.satelliteY) }
    val dragX = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var pokes by remember { mutableIntStateOf(0) }
    var idle by remember { mutableStateOf(false) }

    BackHandler(open) { open = false }

    // Quick both ways: the orbit is there in a blink and gone even faster.
    LaunchedEffect(open) {
        if (open) {
            launch { progress.animateTo(1f, tween(150, easing = FastOutSlowInEasing)) }
            // The half orbit whirls round the handle and settles, a moon riding its end.
            launch { spin.snapTo(0f); spin.animateTo(1f, tween(560, easing = FastOutSlowInEasing)) }
            // Each site pops out a beat after the one before, overshooting a little.
            nodes.forEachIndexed { i, a ->
                launch { delay(18L * i); a.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 1100f)) }
            }
        } else {
            launch { progress.animateTo(0f, tween(110, easing = FastOutLinearInEasing)) }
            nodes.forEach { a -> launch { a.animateTo(0f, tween(100, easing = FastOutLinearInEasing)) } }
        }
    }
    // Out of the way while you play: the handle fades back after a few quiet seconds.
    LaunchedEffect(open, dragging, pokes) {
        idle = false
        if (!open && !dragging) { delay(3000); idle = true }
    }
    val handleAlpha by animateFloatAsState(if (idle) 0.4f else 1f, tween(400), label = "handle")

    fun go(url: String) {
        open = false
        if (browser.customView != null) browser.exitFullscreen()
        openSite(browser, url)
    }

    val p = progress.value
    if (p > 0.01f || open) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f * p.coerceIn(0f, 1f)))
                .pointerInput(Unit) { detectTapGestures { open = false } },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val handleW = with(density) { HandleW.toPx() }
        val handleH = with(density) { HandleH.toPx() }
        val nodePx = with(density) { Node.toPx() }
        val labelPx = with(density) { LabelW.toPx() }
        val labelRoom = with(density) { LabelRoom.toPx() }
        // Smaller on short screens (landscape), so the whole half orbit fits.
        val radius = minOf(with(density) { MaxRadius.toPx() }, (h - nodePx - labelRoom) / 2f).coerceAtLeast(nodePx)
        val handleY = (y * h).coerceIn(handleH / 2, h - handleH / 2)
        val minCy = radius + nodePx / 2
        val maxCy = h - radius - nodePx / 2 - labelRoom
        val orbitY = if (minCy <= maxCy) handleY.coerceIn(minCy, maxCy) else h / 2
        // While opening, the handle glides to the middle of the orbit.
        val cy = handleY + (orbitY - handleY) * p.coerceIn(0f, 1f)
        val edge = if (left) 0f else w
        val dir = if (left) 1f else -1f

        val ring = Orb.BorderStrong
        val glow = Orb.Contrast
        if (p > 0.01f) {
            Canvas(Modifier.fillMaxSize()) {
                val pc = p.coerceIn(0f, 1f)
                drawCircle(
                    Brush.radialGradient(
                        listOf(glow.copy(alpha = 0.18f * pc), Color.Transparent),
                        center = Offset(edge, cy),
                        radius = radius * 1.45f,
                    ),
                    radius = radius * 1.45f,
                    center = Offset(edge, cy),
                )
                // The orbit draws itself from the top while both rings spin into place, the inner
                // one the other way round, like the Orbit mark loading.
                val turn = (1f - spin.value) * 540f
                listOf(Triple(1f, 1f, 1f), Triple(0.55f, 0.5f, -0.7f)).forEach { (scale, a, way) ->
                    val r = radius * scale
                    val start = -90f - turn * way * dir
                    val sweep = 180f * pc * dir
                    // Bold while it whirls, easing back to a hairline as it settles.
                    val whirl = 1f - spin.value
                    drawArc(
                        lerp(ring, glow, whirl * 0.85f).copy(alpha = a * pc),
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(edge - r, cy - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke((1f + 2f * whirl).dp.toPx(), cap = StrokeCap.Round),
                    )
                    if (way > 0f) {
                        val sv = spin.value
                        val moonAlpha = pc * (1f - sv * sv * sv * sv)
                        if (moonAlpha > 0.01f) {
                            val m = Math.toRadians((start + sweep).toDouble())
                            drawCircle(
                                glow.copy(alpha = moonAlpha),
                                radius = 5.dp.toPx(),
                                center = Offset(edge + r * cos(m).toFloat(), cy + r * sin(m).toFloat()),
                            )
                        }
                    }
                }
            }
        }

        for (i in 0 until count) {
            val np = nodes[i].value
            if (np <= 0.01f) continue
            val target = (i - (count - 1) / 2f) * STEP
            // Sites start near the top of the orbit and swing round into place.
            val a = target - (1f - np) * 1.1f
            val r = radius * np
            val x = edge + dir * r * cos(a)
            val ny = cy + r * sin(a)
            val f = sites.getOrNull(i)
            Column(
                Modifier
                    .offset { IntOffset((x - labelPx / 2).roundToInt(), (ny - nodePx / 2).roundToInt()) }
                    .zIndex(1f)
                    .width(LabelW)
                    .graphicsLayer {
                        val sc = 0.35f + 0.65f * np
                        scaleX = sc; scaleY = sc
                        alpha = np.coerceIn(0f, 1f)
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, nodePx / 2 / size.height.coerceAtLeast(1f))
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(Node)
                        .shadow(8.dp, CircleShape)
                        .background(Orb.Surface, CircleShape)
                        .border(1.dp, Orb.Border, CircleShape)
                        .tap(CircleShape, onLongClick = { haptics.heavy(); open = false; onEdit() }) {
                            haptics.confirm()
                            if (f != null) go(f.url) else { open = false; onEdit() }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (f != null) SiteIcon(f.url, 24.dp, shape = RoundedCornerShape(5.dp), framed = false)
                    else Icon(Icons.Outlined.Add, "Pick sites", tint = Orb.Text, modifier = Modifier.size(22.dp))
                }
                Text(
                    f?.name ?: "Pick sites",
                    style = MaterialTheme.typography.labelSmall,
                    color = Orb.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .background(Orb.Surface.copy(alpha = 0.92f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }

        val handleX = (if (left) 0f else w - handleW) + dragX.value
        val shape = if (left) RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp) else RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
        Box(
            Modifier
                .offset { IntOffset(handleX.roundToInt(), (cy - handleH / 2).roundToInt()) }
                .zIndex(2f)
                .size(HandleW, HandleH)
                // On the screen edge, so Android's back swipe would otherwise take the drag.
                .systemGestureExclusion()
                .graphicsLayer { alpha = if (open || dragging) 1f else handleAlpha }
                .shadow(6.dp, shape)
                .background(Orb.Surface, shape)
                .border(1.dp, Orb.BorderStrong, shape)
                .pointerInput(left, w, h) {
                    detectDragGestures(
                        onDragStart = { if (!open) { dragging = true; pokes++ } },
                        onDragEnd = {
                            if (!dragging) return@detectDragGestures
                            dragging = false
                            // Snap to whichever side it was dropped nearer to.
                            val absX = (if (left) 0f else w - handleW) + dragX.value
                            val toLeft = absX + handleW / 2 < w / 2
                            val newBase = if (toLeft) 0f else w - handleW
                            left = toLeft
                            scope.launch {
                                dragX.snapTo(absX - newBase)
                                dragX.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f))
                            }
                            browser.updateSettings(browser.settings.copy(satelliteLeft = toLeft, satelliteY = y))
                        },
                        onDragCancel = { dragging = false; scope.launch { dragX.animateTo(0f) } },
                    ) { change, d ->
                        if (!dragging) return@detectDragGestures
                        change.consume()
                        y = ((y * h + d.y) / h).coerceIn(0f, 1f)
                        scope.launch { dragX.snapTo(dragX.value + d.x) }
                    }
                }
                .tap(shape) { haptics.tick(); pokes++; open = !open },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(app.orbit.R.drawable.orbit_mark),
                if (open) "Close favorites" else "Favorites",
                tint = Orb.Text,
                modifier = Modifier.size(16.dp).rotate(270f * p),
            )
        }
    }
}

/** Jumps to a site: its open tab in this space if there is one, otherwise a new tab. */
private fun openSite(browser: Browser, url: String) {
    val site = Url.host(url)?.let(Url::site)
    val existing = site?.let { s ->
        browser.tabsIn(browser.currentSpaceId)
            .filter { !it.showHome && Url.host(it.url)?.let(Url::site) == s }
            .maxByOrNull { it.lastActive }
    }
    val current = browser.current
    when {
        existing != null -> browser.select(existing)
        current != null && !current.ghost && current.showHome -> browser.navigate(url, current)
        else -> browser.navigate(url, browser.newTab())
    }
}
