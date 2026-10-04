package app.orbit.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TripOrigin
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.Wallpaper
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orbit.R
import app.orbit.core.Accent
import app.orbit.core.AppIcon
import app.orbit.core.Corners
import app.orbit.core.FontChoice
import app.orbit.core.Settings
import app.orbit.core.ThemeMode

/**
 * Customize: theme, accent, corners, font, text size, start page, address bar, web pages and the
 * app icon. Everything applies live and is saved with the profile (the wallpaper image and app
 * icon stay on this device).
 */
@Composable
fun CustomizeSheet(onAdjustWallpaper: () -> Unit, onDismiss: () -> Unit) {
    val browser = LocalBrowser.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val s = browser.settings
    fun set(n: Settings) = browser.updateSettings(n)
    val pickWallpaper = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // A new image goes straight to framing it.
        uri?.let { browser.setWallpaper(it, onAdjustWallpaper) }
    }
    var icon by remember { mutableStateOf(runCatching { AppIcon.current(context) }.getOrDefault(AppIcon.CLASSIC)) }
    val inset = Modifier.padding(horizontal = 16.dp)

    OrbSheet(onDismiss) {
        Column(Modifier.heightIn(max = 720.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Customize", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    "Reset",
                    style = MaterialTheme.typography.labelLarge,
                    color = Orb.Text2,
                    modifier = Modifier.tap(R8) { haptics.tick(); set(s.withDefaultLook()) }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Preview()

            Heading("Theme")
            Segmented(ThemeMode.entries, s.theme, inset, label = { it.label }) { set(s.copy(theme = it)) }

            Heading("Accent", trailing = s.accent.label)
            AccentPicker(s.accent) { haptics.tick(); set(s.copy(accent = it)) }

            Heading("Corners")
            Segmented(Corners.entries, s.corners, inset, label = { it.label }) { set(s.copy(corners = it)) }

            Heading("Font")
            FontPicker(s.font) { haptics.tick(); set(s.copy(font = it)) }

            Heading("Text size")
            Segmented(TextSizes, TextSizes.minBy { kotlin.math.abs(it - s.textScale) }, inset, label = ::textSizeLabel) {
                set(s.copy(textScale = it))
            }

            Heading("Start page")
            ToggleRow("Clock", null, s.showClock, Icons.Outlined.Schedule) { set(s.copy(showClock = it)) }
            ToggleRow("Search field", null, s.showSearch, Icons.Outlined.Search) { set(s.copy(showSearch = it)) }
            ToggleRow("Orbit", "Your favorite sites", s.showOrbit, Icons.Outlined.TripOrigin) { set(s.copy(showOrbit = it)) }
            ToggleRow("Recently visited", null, s.showRecent, Icons.Outlined.History) { set(s.copy(showRecent = it)) }
            ListRow(
                "Wallpaper",
                subtitle = if (s.wallpaper != 0L) "Your image · kept on this device" else "None",
                icon = Icons.Outlined.Wallpaper,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (s.wallpaper != 0L) {
                            Text(
                                "Remove",
                                style = MaterialTheme.typography.labelLarge, color = Orb.Red,
                                modifier = Modifier.tap(R8) { haptics.tick(); browser.removeWallpaper() }.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                        Text(
                            if (s.wallpaper != 0L) "Change" else "Choose",
                            style = MaterialTheme.typography.labelLarge, color = Orb.Text,
                            modifier = Modifier.tap(R8) { pickImage(pickWallpaper) }.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                },
            ) { pickImage(pickWallpaper) }
            if (s.wallpaper != 0L) {
                ListRow(
                    "Adjust wallpaper",
                    subtitle = buildList {
                        add(if (s.wallpaperFit) "Whole image" else "Fills the screen")
                        if (s.wallpaperZoom > 1.01f) add("zoomed")
                        add("dim " + dimLabel(s.wallpaperDim).lowercase())
                        if (CanBlur && s.wallpaperBlur > 0) add("blur " + blurLabel(s.wallpaperBlur).lowercase())
                    }.joinToString(" · "),
                    icon = Icons.Outlined.Crop,
                    trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Orb.Text3, modifier = Modifier.size(20.dp)) },
                ) { haptics.tick(); onAdjustWallpaper() }
            }

            Heading("Address bar")
            Segmented(listOf(false, true), s.barTop, inset, label = { if (it) "Top" else "Bottom" }) { set(s.copy(barTop = it)) }
            Spacer(Modifier.height(4.dp))
            ToggleRow("Show full address", null, s.fullAddress, Icons.Outlined.Link) { set(s.copy(fullAddress = it)) }
            ToggleRow(
                "Shrink on scroll", if (s.barTop) "Only when the bar is at the bottom" else null,
                s.collapseOnScroll, Icons.Outlined.VerticalAlignBottom,
            ) { set(s.copy(collapseOnScroll = it)) }

            Heading("Web pages")
            Label("Text size")
            Segmented(PageZooms, s.pageZoom, inset, label = { "$it%" }) { set(s.copy(pageZoom = it)) }
            Spacer(Modifier.height(4.dp))
            ToggleRow("Dark websites", "When Orbit is dark", s.darkPages, Icons.Outlined.DarkMode) { set(s.copy(darkPages = it)) }

            Heading("App icon")
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AppIcon.entries.forEach { choice ->
                    IconChoice(choice, selected = choice == icon) {
                        if (choice == icon) return@IconChoice
                        haptics.confirm()
                        runCatching { AppIcon.set(context, choice) }
                            .onSuccess { icon = choice; browser.notify("App icon changed. Your launcher may take a moment.") }
                            .onFailure { browser.notify("Couldn't change the app icon") }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private val TextSizes = listOf(0.9f, 1f, 1.15f, 1.3f)
private val PageZooms = listOf(80, 90, 100, 115, 130, 150)

private fun textSizeLabel(scale: Float) = when (scale) {
    0.9f -> "Small"
    1f -> "Default"
    1.15f -> "Large"
    else -> "Larger"
}

private fun pickImage(launcher: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>) {
    runCatching { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp))
}

/** A small live sample of the current look. */
@Composable
private fun Preview() {
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(R12)
            .border(1.dp, Orb.Border, R12)
            .background(Orb.Bg)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Orbit", style = MaterialTheme.typography.titleMedium)
            Text("The quick brown fox", style = MaterialTheme.typography.bodySmall, color = Orb.Text2)
        }
        OrbitSpinner(22.dp)
        Spacer(Modifier.width(12.dp))
        OrbSwitch(true) { }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(36.dp).clip(R8).background(Orb.Contrast), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Check, null, tint = Orb.OnContrast, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AccentPicker(selected: Accent, onPick: (Accent) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Accent.entries.forEach { a ->
            val color = if (a == Accent.MONO) Orb.Text else Color(if (Orb.palette.dark) a.dark else a.light)
            val sel = a == selected
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(if (sel) 2.dp else 1.dp, if (sel) Orb.Text else Orb.Border, CircleShape)
                    .padding(if (sel) 5.dp else 3.dp)
                    .clip(CircleShape)
                    .background(color)
                    .tap(CircleShape) { onPick(a) },
                contentAlignment = Alignment.Center,
            ) {
                if (sel) Icon(Icons.Outlined.Check, a.label, tint = if (a == Accent.MONO) Orb.Bg else Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun FontPicker(selected: FontChoice, onPick: (FontChoice) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FontChoice.entries.forEach { f ->
            val family = when (f) {
                FontChoice.GEIST -> GeistFamily
                FontChoice.SYSTEM -> FontFamily.Default
                FontChoice.SERIF -> FontFamily.Serif
                FontChoice.MONO -> GeistMono
            }
            val sel = f == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(R8)
                    .border(if (sel) 1.5.dp else 1.dp, if (sel) Orb.Text else Orb.Border, R8)
                    .background(if (sel) Orb.Field else Color.Transparent)
                    .tap(R8) { onPick(f) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Aa", fontFamily = family, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Orb.Text)
                Text(f.label, fontFamily = family, style = MaterialTheme.typography.labelSmall, color = Orb.Text2)
            }
        }
    }
}

@Composable
private fun IconChoice(choice: AppIcon, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(60.dp)
                .border(if (selected) 2.dp else 0.dp, if (selected) Orb.Text else Color.Transparent, RoundedCornerShape(18.dp))
                .padding(4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(choice.background))
                .border(1.dp, Orb.Border, RoundedCornerShape(14.dp))
                .tap(RoundedCornerShape(14.dp)) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.orbit_mark), choice.label, tint = Color(choice.mark), modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(choice.label, style = MaterialTheme.typography.labelSmall, color = if (selected) Orb.Text else Orb.Text2)
    }
}
