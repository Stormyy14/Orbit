package app.orbit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.orbit.core.Vpn

/** Orbit VPN: one switch, what it's doing, and what it does and doesn't cover. */
@Composable
fun VpnSheet(onDismiss: () -> Unit) {
    val browser = LocalBrowser.current
    val on = Vpn.enabled
    val state = Vpn.state
    OrbSheet(onDismiss) {
        Column(Modifier.heightIn(max = 680.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(if (state == Vpn.State.ON) Orb.Contrast else Orb.Field),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.VpnLock, null, tint = if (state == Vpn.State.ON) Orb.OnContrast else Orb.Text2, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Orbit VPN", style = MaterialTheme.typography.titleLarge)
                    Text(statusLine(), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TNUM), color = Orb.Text2)
                }
                OrbSwitch(on) { if (it) Vpn.turnOn() else Vpn.turnOff() }
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.padding(horizontal = 20.dp)) {
                when {
                    Vpn.unsupported != null -> Text(Vpn.unsupported!!, style = MaterialTheme.typography.bodyMedium, color = Orb.Red)
                    state == Vpn.State.CONNECTING -> Meter(Vpn.progress / 100f, Vpn.step.ifBlank { "Connecting" })
                    state == Vpn.State.FAILED -> {
                        Text(Vpn.error ?: "Orbit VPN stopped", style = MaterialTheme.typography.bodyMedium, color = Orb.Red)
                        Spacer(Modifier.height(4.dp))
                        Text("Pages won't load until it's back on or you turn it off.", style = MaterialTheme.typography.bodySmall, color = Orb.Text2)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton("Turn off", Modifier.weight(1f)) { Vpn.turnOff() }
                            PrimaryButton("Try again", Modifier.weight(1f)) { Vpn.retry() }
                        }
                    }
                    state == Vpn.State.ON -> {
                        Vpn.error?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Orb.Red)
                            Spacer(Modifier.height(8.dp))
                        }
                        SecondaryButton("New location", Modifier.fillMaxWidth(), icon = Icons.Outlined.Shuffle) {
                            Vpn.newLocation()
                            browser.notify("Switching to a new location")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            // Each site can get its own Tor address, so no single one is shown as "yours".
                            if (Vpn.exitIp != null) "Checked with Tor's own check service. Sites see a Tor address, never yours."
                            else "Sites see a Tor address, never yours.",
                            style = MaterialTheme.typography.bodySmall, color = Orb.Text2,
                        )
                    }
                    else -> Text(
                        "Hides your IP address from the sites you visit, and what you visit from your Wi-Fi or mobile network.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Heading("How it works")
            Column(Modifier.padding(horizontal = 20.dp)) {
                Bullet("Orbit's traffic goes through the Tor network: three volunteer-run relays, each layer encrypted, so no single relay knows both who you are and what you open.")
                Bullet("Free, no account, no servers run by Orbit. The Tor client runs inside the app.")
                Bullet("If the connection drops, pages stop loading instead of going out without it.")
                Bullet("Plain http sites are opened over https where possible, and WebRTC is switched off, as it could reveal your address.")
            }
            Heading("Good to know")
            Column(Modifier.padding(horizontal = 20.dp)) {
                Bullet("Slower than your normal connection: fine for reading and browsing, not for HD video.")
                Bullet("Some sites (Google Search, for example) may ask you to prove you're not a robot, or block Tor.")
                Bullet("Only Orbit is covered, not other apps. Downloads are handed to Android and don't go through it; Orbit asks first.")
                Bullet("Sites can still recognise you if you sign in. Use a ghost tab to start clean.")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun statusLine(): String = when (Vpn.state) {
    Vpn.State.OFF -> if (Vpn.unsupported != null) "Not available" else "Off"
    Vpn.State.CONNECTING -> "Connecting · ${Vpn.progress}%"
    Vpn.State.ON -> "Connected through Tor"
    Vpn.State.FAILED -> "Not connected"
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium, color = Orb.Text2, modifier = Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Orb.Text)
    }
}

@Composable
private fun Meter(value: Float, label: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OrbitSpinner(18.dp)
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Orb.Field)) {
            Box(Modifier.fillMaxWidth(value.coerceIn(0.03f, 1f)).fillMaxHeight().background(Orb.Contrast))
        }
    }
}

/** Covers a page while Orbit VPN connects (or can't), instead of showing it fail to load. */
@Composable
fun VpnVeil(onOpen: () -> Unit) {
    val failed = Vpn.state == Vpn.State.FAILED
    Box(Modifier.fillMaxSize().background(Orb.Bg)) {
        Column(Modifier.align(Alignment.Center).width(260.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.VpnLock, null, tint = if (failed) Orb.Red else Orb.Text2, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(14.dp))
            Text(
                if (failed) "Orbit VPN isn't connected" else "Connecting to Orbit VPN",
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (failed) "Nothing loads without it." else "${Vpn.progress}% · ${Vpn.step.ifBlank { "Starting" }}",
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TNUM), color = Orb.Text2, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            if (failed) PrimaryButton("Open Orbit VPN") { onOpen() } else OrbitSpinner(28.dp)
        }
    }
}
