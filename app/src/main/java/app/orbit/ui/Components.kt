package app.orbit.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orbit.core.Images
import app.orbit.core.Url
import app.orbit.core.UserProfile
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Haptics — the one place Orbit is "loud": a tick confirms every gesture.
// ---------------------------------------------------------------------------------------------

class Haptics(private val enabled: () -> Boolean, private val perform: (HapticFeedbackType) -> Unit) {
    fun tick() { if (enabled()) perform(HapticFeedbackType.SegmentTick) }
    fun confirm() { if (enabled()) perform(HapticFeedbackType.Confirm) }
    fun heavy() { if (enabled()) perform(HapticFeedbackType.LongPress) }
    fun reject() { if (enabled()) perform(HapticFeedbackType.Reject) }
}

@Composable
fun rememberHaptics(): Haptics {
    val h = LocalHapticFeedback.current
    val browser = LocalBrowser.current
    return remember(h) { Haptics({ browser.settings.haptics }) { h.performHapticFeedback(it) } }
}

// ---------------------------------------------------------------------------------------------
// Press feedback: a neutral, bounded ripple clipped to the element's shape.
// ---------------------------------------------------------------------------------------------

// Corner shapes follow the "Corners" choice in Customize.
val R6: Shape get() = Orb.tiny
val R8: Shape get() = Orb.small
val R12: Shape get() = Orb.large

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tap(
    shape: Shape = R8,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    this
        .clip(shape)
        .combinedClickable(
            interactionSource = source,
            indication = ripple(color = Orb.Text),
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

// ---------------------------------------------------------------------------------------------
// Identity
// ---------------------------------------------------------------------------------------------

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun SiteIcon(url: String, size: Dp, favicon: ImageBitmap? = null, shape: Shape = RoundedCornerShape(size * 0.25f), framed: Boolean = true) {
    val host = Url.host(url).orEmpty()
    var icon by remember(host) { mutableStateOf(favicon) }
    LaunchedEffect(host, favicon) {
        if (favicon != null) icon = favicon else if (host.isNotEmpty()) icon = Images.icon(host)
    }
    val bm = icon
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .then(if (framed) Modifier.background(Orb.Field).border(1.dp, Orb.Border, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (bm != null) {
            Image(bm, null, Modifier.size(if (framed) size * 0.62f else size), contentScale = ContentScale.Fit)
        } else {
            val letter = (if (host.isNotEmpty()) Url.site(host) else url).firstOrNull()?.uppercaseChar() ?: '·'
            Text(letter.toString(), color = Orb.Text2, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.42f).sp, fontFamily = Geist)
        }
    }
}

/**
 * The Orbit mark, spinning: the moon and the gap it sits in travel round the orbit while a page
 * loads. Same geometry as res/drawable/orbit_mark.xml (24-unit grid).
 */
@Composable
fun OrbitSpinner(size: Dp, modifier: Modifier = Modifier, color: Color = Orb.Tint) {
    val spin = rememberInfiniteTransition(label = "orbit")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "moon")
    Canvas(modifier.size(size).semantics { contentDescription = "Loading" }) {
        val unit = this.size.minDimension / 24f
        val r = 7.125f * unit
        val moon = -45f + angle
        drawArc(
            color,
            startAngle = moon + 34f,
            sweepAngle = 292f,
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = Size(r * 2, r * 2),
            style = Stroke(2.4f * unit, cap = StrokeCap.Round),
        )
        val a = Math.toRadians(moon.toDouble())
        drawCircle(color, 1.95f * unit, Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat()))
    }
}

/** A profile's Google photo, or its initial, in a circle; the guest gets a plain person. */
@Composable
fun ProfileBadge(profile: UserProfile?, size: Dp, tint: Color = Orb.Text) {
    val photoUrl = profile?.photo
    var photo by remember(photoUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(photoUrl) { if (photoUrl != null) photo = Images.remote(photoUrl, maxWidth = 192) }
    Box(
        Modifier.size(size).clip(CircleShape).border(1.dp, Orb.BorderStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val bm = photo
        if (bm != null) {
            Image(bm, profile?.name, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
        } else if (profile == null) {
            Icon(Icons.Outlined.Person, null, tint = tint, modifier = Modifier.size(size * 0.58f))
        } else {
            val letter = profile.name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '·'
            Text(letter.toString(), color = tint, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.44f).sp, fontFamily = Geist)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheets
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Orb.Surface,
        contentColor = Orb.Text,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = if (Orb.palette.dark) 0.6f else 0.32f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 8.dp, bottom = 4.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Orb.BorderStrong),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp), content = content)
    }
}

@Composable
fun SheetTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Orb.Text)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Orb.Text2)
        }
    }
}

/** Sentence-case group heading. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier, trailing: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        // An action brings its own touch padding, so the heading's text stays where it was.
        if (action == null) modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp)
        else modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Orb.Text2, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TNUM), color = Orb.Text3)
        if (action != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = Orb.Text,
                modifier = Modifier.tap(R8) { onAction() }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(modifier.fillMaxWidth().padding(start = inset).height(1.dp).background(Orb.Border))
}

// ---------------------------------------------------------------------------------------------
// Rows & controls
// ---------------------------------------------------------------------------------------------

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    leading: @Composable (() -> Unit)? = null,
    trailingText: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    titleColor: Color = Orb.Text,
    subtitleMono: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .tap(RoundedCornerShape(0.dp), onLongClick = onLongClick, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> { leading(); Spacer(Modifier.width(14.dp)) }
            icon != null -> { Icon(icon, null, tint = Orb.Text2, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(16.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = if (subtitleMono) MonoSmall else MaterialTheme.typography.bodySmall,
                    color = Orb.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingText != null) {
            Spacer(Modifier.width(12.dp))
            Text(trailingText, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TNUM), color = Orb.Text2)
        }
        trailing?.let { Spacer(Modifier.width(8.dp)); it() }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    ListRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        trailing = { OrbSwitch(checked) { haptics.tick(); onChange(it) } },
    ) { haptics.tick(); onChange(!checked) }
}

@Composable
fun OrbSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Orb.OnContrast,
            checkedTrackColor = Orb.Contrast,
            checkedBorderColor = Orb.Contrast,
            uncheckedThumbColor = Orb.Text3,
            uncheckedTrackColor = Orb.Field,
            uncheckedBorderColor = Orb.BorderStrong,
        ),
    )
}

/** A bordered segmented control; the selected segment is filled, nothing else is coloured. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, modifier: Modifier = Modifier, label: (T) -> String, onSelect: (T) -> Unit) {
    val haptics = rememberHaptics()
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(R8)
            .border(1.dp, Orb.Border, R8)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { o ->
            val sel = o == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(R6)
                    .background(if (sel) Orb.Field else Color.Transparent)
                    .tap(R6) { haptics.tick(); onSelect(o) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TNUM),
                    color = if (sel) Orb.Text else Orb.Text2,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Row(
        modifier
            .height(44.dp)
            .clip(R8)
            .background(if (enabled) Orb.Contrast else Orb.Field)
            .tap(R8, enabled) { haptics.confirm(); onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Orb.OnContrast, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = if (enabled) Orb.OnContrast else Orb.Text3, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = Orb.Text, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Row(
        modifier
            .height(44.dp)
            .clip(R8)
            .background(Orb.Surface)
            .border(1.dp, Orb.Border, R8)
            .tap(R8) { haptics.tick(); onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun IconButton(icon: ImageVector, desc: String, modifier: Modifier = Modifier, tint: Color = Orb.Text, enabled: Boolean = true, size: Dp = 44.dp, onClick: () -> Unit) {
    Box(
        modifier.size(size).tap(CircleShape, enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (enabled) tint else Orb.Text3.copy(alpha = 0.5f), modifier = Modifier.size(20.dp)) }
}

/** A text field shell: hairline border, no fill, focus shown by a stronger border. */
@Composable
fun FieldShell(modifier: Modifier = Modifier, focused: Boolean = true, height: Dp = 48.dp, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(R12)
            .background(Orb.Bg)
            .border(1.dp, if (focused) Orb.BorderStrong else Orb.Border, R12),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

// ---------------------------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------------------------

fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
    n >= 10_000 -> "${n / 1000}k"
    else -> "%,d".format(n)
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s) else "%d:%02d".format(m, s)
}

fun formatMinutes(mins: Int): String = if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"

fun ago(ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 1 -> "now"
        m < 60 -> "${m}m"
        m < 1440 -> "${m / 60}h"
        else -> "${m / 1440}d"
    }
}
