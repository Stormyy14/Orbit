package app.orbit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.orbit.core.Updates

/** "Update available": what's new, and one button that downloads and installs it. */
@Composable
fun UpdateSheet(onDismiss: () -> Unit) {
    val browser = LocalBrowser.current
    val scope = rememberCoroutineScope()
    val r = Updates.available
    OrbSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Orb.Contrast), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.SystemUpdate, null, tint = Orb.OnContrast, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(if (r != null) "Update to Orbit ${r.version}" else "Orbit is up to date", style = MaterialTheme.typography.titleLarge)
                    Text("You have ${Updates.current}", style = MaterialTheme.typography.bodySmall, color = Orb.Text2)
                }
            }
            if (r != null && r.notes.isNotEmpty()) {
                Text("What's new", style = MaterialTheme.typography.labelMedium, color = Orb.Text2, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    r.notes.forEach { line ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text("•", style = MaterialTheme.typography.bodyMedium, color = Orb.Text2, modifier = Modifier.width(14.dp))
                            Text(line, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            when (Updates.phase) {
                Updates.Phase.DOWNLOADING -> Progress("Downloading", Updates.progress)
                Updates.Phase.INSTALLING -> Progress("Installing. Confirm in the window Android shows.", 1f)
                Updates.Phase.NEEDS_PERMISSION -> Column {
                    Text(
                        "Allow Orbit to install updates (\"Allow from this source\"), then come back. The update continues by itself.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Open settings", Modifier.fillMaxWidth()) { Updates.openInstallPermission() }
                }
                else -> {
                    Updates.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Orb.Red, modifier = Modifier.padding(bottom = 10.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Later", Modifier.weight(1f)) { onDismiss() }
                        if (r != null) {
                            PrimaryButton(if (Updates.phase == Updates.Phase.FAILED) "Try again" else "Update", Modifier.weight(1f), icon = Icons.Outlined.SystemUpdate) {
                                Updates.install(scope, r)
                            }
                        }
                    }
                    if (r != null) {
                        Text(
                            "Orbit closes while Android installs it. Your tabs and settings stay.",
                            style = MaterialTheme.typography.bodySmall, color = Orb.Text2,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
            if (r != null) {
                Text(
                    "Release page",
                    style = MaterialTheme.typography.labelLarge, color = Orb.Text2,
                    modifier = Modifier.padding(top = 6.dp).tap(R8) { onDismiss(); browser.navigate(r.page, browser.newTab()) }.padding(vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Progress(label: String, value: Float) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OrbitSpinner(18.dp)
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (value < 1f) Text("${(value * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TNUM), color = Orb.Text2)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Orb.Field)) {
            Box(Modifier.fillMaxWidth(value.coerceIn(0.02f, 1f)).fillMaxHeight().background(Orb.Contrast))
        }
    }
}

/** A quiet card on the start page while an update is waiting. */
@Composable
fun UpdateCard(onOpen: () -> Unit) {
    val r = Updates.available ?: return
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(R12)
            .border(1.dp, Orb.Border, R12)
            .background(Orb.Surface)
            .tap(R12) { onOpen() }
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.SystemUpdate, null, tint = Orb.Contrast, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Orbit ${r.version} is available", style = MaterialTheme.typography.titleSmall)
            Text("You have ${Updates.current}", style = MaterialTheme.typography.bodySmall, color = Orb.Text2)
        }
        Text(
            "Update",
            style = MaterialTheme.typography.labelLarge, color = Orb.OnContrast,
            modifier = Modifier.clip(R8).background(Orb.Contrast).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
