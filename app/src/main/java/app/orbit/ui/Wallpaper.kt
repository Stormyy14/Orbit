package app.orbit.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.orbit.core.Settings
import app.orbit.core.Wallpapers
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Blur needs RenderEffect, which arrived in Android 12. */
val CanBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** The start page wallpaper, framed and dimmed as set in [s]. */
@Composable
fun WallpaperLayer(img: ImageBitmap, s: Settings, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        val blur = if (CanBlur && s.wallpaperBlur > 0) Modifier.blur(s.wallpaperBlur.dp, BlurredEdgeTreatment.Rectangle) else Modifier
        Canvas(Modifier.fillMaxSize().then(blur)) {
            val r = wallpaperRect(img, size, s)
            drawImage(
                img,
                dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
                filterQuality = FilterQuality.High,
            )
        }
        // Dimmed towards the canvas colour so text and icons stay readable.
        Box(Modifier.fillMaxSize().background(Orb.Bg.copy(alpha = s.wallpaperDim / 100f)))
    }
}

/**
 * Where the image lands in [box]: scaled to fill (or fit) it, zoomed, with the chosen point at the
 * centre, but never pulled so far that an edge comes away from the screen's edge.
 */
private fun wallpaperRect(img: ImageBitmap, box: Size, s: Settings): Rect {
    val iw = img.width.toFloat()
    val ih = img.height.toFloat()
    val base = if (s.wallpaperFit) min(box.width / iw, box.height / ih) else max(box.width / iw, box.height / ih)
    val w = iw * base * s.wallpaperZoom
    val h = ih * base * s.wallpaperZoom
    return Rect(Offset(place(w, box.width, s.wallpaperX), place(h, box.height, s.wallpaperY)), Size(w, h))
}

private fun place(len: Float, room: Float, focus: Float): Float =
    if (len <= room) (room - len) / 2 else (room / 2 - focus * len).coerceIn(room - len, 0f)

/** The point of the image at the screen's centre, kept where the image can still cover the screen. */
private fun reachable(focus: Float, len: Float, room: Float): Float =
    if (len <= room) 0.5f else focus.coerceIn(room / 2 / len, 1 - room / 2 / len)

/**
 * Full-screen framing for the wallpaper: pinch to zoom, drag to move, fill or fit, dim and blur.
 * Shows the image exactly as the start page will; nothing is saved until Done.
 */
@Composable
fun WallpaperEditor(onClose: () -> Unit) {
    val browser = LocalBrowser.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val start = browser.settings
    var s by remember { mutableStateOf(start) }
    val img by produceState<ImageBitmap?>(null, browser.profileId, start.wallpaper) {
        value = if (start.wallpaper == 0L) null else Wallpapers.load(context, browser.profileId)
    }
    var box by remember { mutableStateOf(Size.Zero) }
    val current by rememberUpdatedState(s)
    BackHandler { onClose() }

    Box(Modifier.fillMaxSize().background(Orb.Bg).onSizeChanged { box = it.toSize() }) {
        val image = img
        if (image != null) {
            WallpaperLayer(image, s)
            Box(
                Modifier.fillMaxSize().pointerInput(image) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val cur = current
                        val r = wallpaperRect(image, box, cur)
                        val z = (cur.wallpaperZoom * zoom).coerceIn(1f, 5f)
                        val scale = z / cur.wallpaperZoom
                        val w = r.width * scale
                        val h = r.height * scale
                        // Zoom around the fingers: the point under them stays under them.
                        val left = centroid.x - (centroid.x - r.left) * scale + pan.x
                        val top = centroid.y - (centroid.y - r.top) * scale + pan.y
                        s = cur.copy(
                            wallpaperZoom = z,
                            wallpaperX = reachable((box.width / 2 - left) / w, w, box.width),
                            wallpaperY = reachable((box.height / 2 - top) / h, h, box.height),
                        )
                    }
                },
            )
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("Cancel") { onClose() }
            Spacer(Modifier.weight(1f))
            Pill("Done", strong = true) {
                haptics.confirm()
                browser.updateSettings(
                    browser.settings.copy(
                        wallpaperFit = s.wallpaperFit, wallpaperZoom = s.wallpaperZoom, wallpaperX = s.wallpaperX,
                        wallpaperY = s.wallpaperY, wallpaperDim = s.wallpaperDim, wallpaperBlur = s.wallpaperBlur,
                    ),
                )
                onClose()
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp)
                .fillMaxWidth()
                .clip(R12)
                .background(Orb.Surface.copy(alpha = 0.96f))
                .border(1.dp, Orb.Border, R12)
                // Drags on the panel stay on the panel instead of moving the image.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .padding(vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Pinch to zoom · drag to move",
                    style = MaterialTheme.typography.bodyMedium, color = Orb.Text2, modifier = Modifier.weight(1f),
                )
                Text(
                    "Reset",
                    style = MaterialTheme.typography.labelLarge, color = Orb.Text,
                    modifier = Modifier.tap(R8) {
                        haptics.tick()
                        val d = Settings()
                        s = s.copy(wallpaperZoom = d.wallpaperZoom, wallpaperX = d.wallpaperX, wallpaperY = d.wallpaperY)
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            val inset = Modifier.padding(horizontal = 12.dp)
            Spacer(Modifier.height(8.dp))
            Segmented(listOf(false, true), s.wallpaperFit, inset, label = { if (it) "Fit whole image" else "Fill screen" }) {
                // Each mode starts from its own natural framing.
                s = s.copy(wallpaperFit = it, wallpaperZoom = 1f, wallpaperX = 0.5f, wallpaperY = 0.5f)
            }
            EditorLabel("Dim")
            Segmented(Dims, Dims.minBy { kotlin.math.abs(it - s.wallpaperDim) }, inset, label = ::dimLabel) {
                s = s.copy(wallpaperDim = it)
            }
            if (CanBlur) {
                EditorLabel("Blur")
                Segmented(Blurs, Blurs.minBy { kotlin.math.abs(it - s.wallpaperBlur) }, inset, label = ::blurLabel) {
                    s = s.copy(wallpaperBlur = it)
                }
            }
        }
    }
}

private val Dims = listOf(0, 30, 50, 70)
private val Blurs = listOf(0, 6, 16)

fun dimLabel(v: Int) = when (v) { 0 -> "Off"; 30 -> "Light"; 50 -> "Medium"; else -> "Strong" }
fun blurLabel(v: Int) = when (v) { 0 -> "Off"; 6 -> "Soft"; else -> "Strong" }

@Composable
private fun EditorLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Orb.Text2, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp))
}

@Composable
private fun Pill(text: String, strong: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (strong) Orb.OnContrast else Orb.Text,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(R8)
            .background(if (strong) Orb.Contrast else Orb.Surface.copy(alpha = 0.92f))
            .border(1.dp, if (strong) Orb.Contrast else Orb.Border, R8)
            .tap(R8) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .width(56.dp),
    )
}
