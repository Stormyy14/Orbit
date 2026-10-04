package app.orbit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orbit.core.Extension
import app.orbit.core.Tab
import app.orbit.core.Url

private const val GET_EXTENSIONS = "https://greasyfork.org/"

/**
 * Extensions: user scripts that change how sites look and work. Lists what's installed (and
 * what runs on this page), switches each on or off, and adds new ones from Greasy Fork or by
 * pasting a script or a link to one.
 */
@Composable
fun ExtensionsSheet(tab: Tab?, onDismiss: () -> Unit) {
    val browser = LocalBrowser.current
    val haptics = rememberHaptics()
    val here = browser.extensionsOn(tab).map { it.id }.toSet()
    val site = tab?.takeIf { !it.showHome }?.pageHost?.let(Url::site)
    var open by remember { mutableStateOf<String?>(null) }
    var paste by remember { mutableStateOf("") }

    OrbSheet(onDismiss) {
        Column(Modifier.heightIn(max = 680.dp).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp)) {
                Text("Extensions", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        browser.extensions.isEmpty() -> "Add user scripts to change how sites look and work"
                        tab?.ghost == true -> "Extensions don't run in ghost tabs"
                        site != null && here.isNotEmpty() -> "${here.size} running on $site"
                        else -> "${browser.extensions.size} installed · ${browser.extensions.count { it.enabled }} on"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = Orb.Text2,
                )
            }

            if (browser.extensions.isNotEmpty()) Heading("Installed")
            browser.extensions.forEach { e ->
                ExtensionRow(
                    e,
                    runningHere = e.id in here,
                    expanded = open == e.id,
                    onToggle = { browser.setExtensionEnabled(e.id, it) },
                    onRemove = { haptics.tick(); open = null; browser.removeExtension(e.id) },
                ) { open = if (open == e.id) null else e.id }
            }

            Heading("Add")
            ListRow("Find extensions", "Browse thousands of user scripts on Greasy Fork", icon = Icons.Outlined.Explore) {
                haptics.tick()
                onDismiss()
                browser.newTab(GET_EXTENSIONS)
            }
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().heightIn(min = 48.dp)
                    .clip(R12).background(Orb.Field).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    if (paste.isEmpty()) Text("Paste a script or a link to one", color = Orb.Text3, style = MaterialTheme.typography.bodyLarge)
                    BasicTextField(
                        paste, { paste = it.take(UserScriptPasteLimit) }, maxLines = 4,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Orb.Text),
                        cursorBrush = SolidColor(Orb.Text), modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "Add",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (paste.isBlank()) Orb.Text3 else Orb.Text,
                    modifier = Modifier.tap(R8, enabled = paste.isNotBlank()) {
                        haptics.tick()
                        browser.offerExtensionText(paste)
                        paste = ""
                    }.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            Text(
                "Orbit runs user scripts (the kind made for Tampermonkey and Greasemonkey). Chrome and Firefox " +
                    "extensions can't run in Android's WebView. Extensions don't run in ghost tabs.",
                style = MaterialTheme.typography.bodySmall, color = Orb.Text3,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
            )
        }
    }
}

/** Long enough for big scripts; very long ones are better installed from their link. */
private const val UserScriptPasteLimit = 1_000_000

@Composable
private fun ExtensionRow(
    e: Extension,
    runningHere: Boolean,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    ListRow(
        e.name,
        subtitle = when {
            runningHere -> "Running on this page"
            e.description.isNotBlank() -> e.description
            else -> e.version.takeIf { it.isNotBlank() }?.let { "Version $it" }
        },
        icon = Icons.Outlined.Extension,
        contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        trailing = { OrbSwitch(e.enabled) { haptics.tick(); onToggle(it) } },
        onClick = onClick,
    )
    AnimatedVisibility(expanded) {
        Column(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 8.dp)) {
            if (e.description.isNotBlank()) {
                Text(e.description, style = MaterialTheme.typography.bodySmall, color = Orb.Text2)
                Spacer(Modifier.height(6.dp))
            }
            val by = listOfNotNull(e.version.takeIf { it.isNotBlank() }?.let { "Version $it" }, e.author.takeIf { it.isNotBlank() }?.let { "by $it" })
            if (by.isNotEmpty()) Text(by.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Orb.Text2)
            Text("Runs on " + sitesLabel(e), style = MaterialTheme.typography.bodySmall, color = Orb.Text2, maxLines = 3, overflow = TextOverflow.Ellipsis)
            e.source?.let { Text("From " + Url.pretty(it), style = MaterialTheme.typography.bodySmall, color = Orb.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(Modifier.padding(top = 4.dp)) {
                Row(
                    Modifier.tap(R8) { onRemove() }.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Delete, null, tint = Orb.Red, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Remove", style = MaterialTheme.typography.labelLarge, color = Orb.Red)
                }
            }
        }
    }
}

private fun sitesLabel(e: Extension): String = when {
    e.sites.isEmpty() || e.include == listOf(".*") -> "every site"
    e.sites.size <= 3 -> e.sites.joinToString(", ")
    else -> e.sites.take(3).joinToString(", ") + " and ${e.sites.size - 3} more"
}

/** Asks before installing an extension, saying plainly what it will be able to do. */
@Composable
fun ExtensionInstallSheet(e: Extension, onDismiss: () -> Unit) {
    val browser = LocalBrowser.current
    val installed = browser.extensions.firstOrNull { it.name == e.name && it.namespace == e.namespace }
    OrbSheet(onDismiss) {
        Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(R12).background(Orb.Field).border(1.dp, Orb.Border, R12),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Extension, null, tint = Orb.Text, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val by = listOfNotNull(e.version.takeIf { it.isNotBlank() }?.let { "Version $it" }, e.author.takeIf { it.isNotBlank() }?.let { "by $it" })
                    if (by.isNotEmpty()) Text(by.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Orb.Text2)
                }
            }
            if (e.description.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(e.description, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(14.dp))
            Text("Runs on", style = MaterialTheme.typography.labelMedium, color = Orb.Text2)
            Spacer(Modifier.height(4.dp))
            if (e.sites.isEmpty() || e.include == listOf(".*")) Text("Every site", style = MaterialTheme.typography.bodyMedium)
            e.sites.take(6).forEach { Text(it, style = MonoSmall, color = Orb.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (e.sites.size > 6) Text("and ${e.sites.size - 6} more", style = MaterialTheme.typography.bodySmall, color = Orb.Text2)
            e.source?.let {
                Spacer(Modifier.height(10.dp))
                Text("From " + Url.pretty(it), style = MaterialTheme.typography.bodySmall, color = Orb.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(R8).border(1.dp, Orb.Border, R8).padding(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Outlined.WarningAmber, null, tint = Orb.Text2, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "It can see and change everything on these sites, including what you type there. " +
                        "Only add extensions from people you trust.",
                    style = MaterialTheme.typography.bodySmall, color = Orb.Text2,
                )
            }
            if (installed != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Replaces the version you have" + (installed.version.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ".",
                    style = MaterialTheme.typography.bodySmall, color = Orb.Text2,
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", Modifier.weight(1f)) { onDismiss() }
                PrimaryButton(if (installed != null) "Update" else "Add extension", Modifier.weight(1f)) { browser.installExtension(e) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
