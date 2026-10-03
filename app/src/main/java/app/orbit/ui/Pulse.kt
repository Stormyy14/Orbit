package app.orbit.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NorthWest
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import app.orbit.core.HistoryEntry
import app.orbit.core.Space
import app.orbit.core.Tab
import app.orbit.core.Url
import kotlinx.coroutines.delay

data class Command(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val keywords: String = "",
    val action: () -> Unit,
)

private sealed interface Hit {
    data class Go(val url: String, val label: String, val sub: String, val search: Boolean) : Hit
    data class Suggest(val text: String) : Hit
    data class TabHit(val tab: Tab) : Hit
    data class HistoryHit(val entry: HistoryEntry) : Hit
    data class Cmd(val command: Command) : Hit
    data class SpaceHit(val space: Space?) : Hit
    data class Bang(val key: String, val host: String) : Hit
}

/**
 * Pulse — one field for everything. URLs, searches, !bangs, >commands, @spaces, open tabs and
 * history. Results grow upward from the thumb, so the best match is always closest to it.
 */
@Composable
fun Pulse(tab: Tab?, commands: List<Command>, onClose: () -> Unit) {
    val browser = LocalBrowser.current
    val haptics = rememberHaptics()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val initial = tab?.takeIf { !it.showHome }?.url.orEmpty()
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val q = field.text.trim()
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(q) {
        if (q == initial || q.length < 2) { suggestions = emptyList(); return@LaunchedEffect }
        delay(140)
        suggestions = browser.suggestions(q, ghost = tab?.ghost == true)
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                field = TextFieldValue(it, TextRange(it.length))
            }
        }
    }

    fun go(input: String) {
        if (input.isBlank()) return
        haptics.confirm()
        keyboard?.hide()
        val target = tab ?: browser.newTab()
        browser.navigate(input, target)
        onClose()
    }

    fun run(hit: Hit) {
        when (hit) {
            is Hit.Go -> go(hit.url)
            is Hit.Suggest -> go(hit.text)
            is Hit.TabHit -> { haptics.confirm(); browser.select(hit.tab); onClose() }
            is Hit.HistoryHit -> go(hit.entry.url)
            is Hit.Cmd -> { haptics.confirm(); onClose(); hit.command.action() }
            is Hit.SpaceHit -> {
                haptics.confirm(); onClose()
                if (hit.space == null) browser.newTab(ghost = true) else browser.switchSpace(hit.space.id)
            }
            is Hit.Bang -> field = TextFieldValue("!${hit.key} ", TextRange(hit.key.length + 2))
        }
    }

    val hits: List<Hit> = remember(q, suggestions, browser.history.size, browser.tabs.size) {
        buildList {
            when {
                q.startsWith(">") -> {
                    val c = q.drop(1).trim()
                    commands.filter { c.isEmpty() || matches(it.title + " " + it.keywords, c) }.forEach { add(Hit.Cmd(it)) }
                }
                q.startsWith("@") -> {
                    val c = q.drop(1).trim()
                    browser.spaces.filter { c.isEmpty() || matches(it.name, c) }.forEach { add(Hit.SpaceHit(it)) }
                    if (c.isEmpty() || matches("ghost", c)) add(Hit.SpaceHit(null))
                }
                q.startsWith("!") && !q.contains(' ') -> {
                    val c = q.drop(1)
                    Url.bangList.filter { it.first.startsWith(c) || it.second.contains(c) }.forEach { add(Hit.Bang(it.first, it.second)) }
                }
                q.isEmpty() -> {
                    browser.history.asSequence()
                        .filter { it.spaceId == browser.currentSpaceId }
                        .distinctBy { it.url }.take(6)
                        .forEach { add(Hit.HistoryHit(it)) }
                }
                else -> {
                    val resolved = Url.resolve(q, browser.settings.engine)
                    val bang = Url.bang(q)
                    val isSearch = bang != null || resolved.startsWith(browser.settings.engine.template.substringBefore("%s"))
                    add(
                        Hit.Go(
                            q,
                            when {
                                bang != null -> "“${bang.second}”"
                                isSearch -> "“$q”"
                                else -> Url.pretty(resolved) + resolved.substringAfter(Url.host(resolved) ?: "").take(40)
                            },
                            when {
                                bang != null -> "Search on ${Url.pretty(bang.first.replace("%s", ""))}"
                                isSearch -> "Search with ${browser.settings.engine.label}"
                                else -> "Open site"
                            },
                            isSearch,
                        ),
                    )
                    browser.tabs.filter { it !== tab && !it.showHome && (matches(it.title, q) || it.url.contains(q, true)) }
                        .take(3).forEach { add(Hit.TabHit(it)) }
                    commands.filter { matches(it.title + " " + it.keywords, q) }.take(2).forEach { add(Hit.Cmd(it)) }
                    suggestions.forEach { add(Hit.Suggest(it)) }
                    browser.history.asSequence()
                        .filter { (matches(it.title, q) || it.url.contains(q, true)) }
                        .distinctBy { it.url }.take(5)
                        .forEach { add(Hit.HistoryHit(it)) }
                }
            }
        }
    }

    val mode = when {
        q.startsWith(">") -> Mode(Icons.Outlined.Terminal, "Commands")
        q.startsWith("@") -> Mode(Icons.Outlined.AlternateEmail, "Spaces")
        q.startsWith("!") -> Mode(Icons.Outlined.Bolt, "Bangs")
        else -> Mode(Icons.Outlined.Search, "")
    }

    Box(Modifier.fillMaxSize().background(Orb.Bg).tap(RoundedCornerShape(0.dp)) { keyboard?.hide(); onClose() }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                items(hits.size) { i ->
                    HitRow(hits[i], q, i == 0 && q.isNotEmpty(), onFill = { text ->
                        field = TextFieldValue(text, TextRange(text.length))
                    }) { run(hits[i]) }
                }
                if (q.isEmpty() && hits.isNotEmpty()) item { Heading("Recently visited") }
            }
            Row(
                Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(R12)
                    .background(Orb.Surface)
                    .border(1.dp, Orb.BorderStrong, R12)
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(mode.icon, mode.label, tint = Orb.Text2, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (field.text.isEmpty()) {
                        Text(
                            if (tab?.ghost == true) "Search privately" else "Search or type URL",
                            color = Orb.Text3, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val mono = q.startsWith(">") || q.startsWith("!") || q.startsWith("@") || q.contains("://")
                    BasicTextField(
                        value = field,
                        onValueChange = { field = it },
                        singleLine = true,
                        textStyle = (if (mono) MonoBody.copy(fontSize = 15.sp) else MaterialTheme.typography.bodyLarge).copy(color = Orb.Text),
                        cursorBrush = SolidColor(Orb.Text),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onGo = { hits.firstOrNull()?.let(::run) ?: go(q) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (field.text.isNotEmpty()) {
                    IconButton(Icons.Outlined.Close, "Clear", tint = Orb.Text3, size = 40.dp) { field = TextFieldValue("") }
                } else {
                    IconButton(Icons.Outlined.Mic, "Voice", tint = Orb.Text2, size = 40.dp) {
                        runCatching {
                            voice.launch(
                                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH),
                            )
                        }.onFailure { browser.notify("Voice input isn't available") }
                    }
                }
                Box(
                    Modifier.size(38.dp).clip(R8).background(Orb.Contrast).tap(R8) { hits.firstOrNull()?.let(::run) ?: onClose() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Go", tint = Orb.OnContrast, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

private data class Mode(val icon: ImageVector, val label: String)

@Composable
private fun PrefixKey(prefix: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(R6).border(1.dp, Orb.Border, R6).tap(R6) { onClick() }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(prefix, style = MonoBody, color = Orb.Text)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Orb.Text2)
    }
}

@Composable
private fun HitRow(hit: Hit, q: String, primary: Boolean, onFill: (String) -> Unit, onClick: () -> Unit) {
    val browser = LocalBrowser.current
    val (title, sub, leading, fill) = when (hit) {
        is Hit.Go -> Quad(AnnotatedString(hit.label), hit.sub, Lead.Icon(if (hit.search) Icons.Outlined.Search else Icons.Outlined.Language), null)
        is Hit.Suggest -> Quad(highlight(hit.text, q), "Suggestion", Lead.Icon(Icons.Outlined.Search), hit.text)
        is Hit.TabHit -> Quad(highlight(hit.tab.displayTitle, q), Url.pretty(hit.tab.url), Lead.Site(hit.tab.url), null)
        is Hit.HistoryHit -> Quad(
            highlight(hit.entry.title.ifBlank { Url.pretty(hit.entry.url) }, q),
            Url.pretty(hit.entry.url),
            Lead.Site(hit.entry.url),
            hit.entry.url,
        )
        is Hit.Cmd -> Quad(highlight(hit.command.title, q.removePrefix(">").trim()), hit.command.subtitle, Lead.Icon(hit.command.icon), null)
        is Hit.SpaceHit -> Quad(
            AnnotatedString(hit.space?.name ?: "Ghost tab"),
            if (hit.space == null) "New private tab" else "${browser.tabsIn(hit.space.id).size} tabs",
            if (hit.space == null) Lead.Icon(Icons.Outlined.VisibilityOff) else Lead.Icon(spaceIcon(hit.space.icon)),
            null,
        )
        is Hit.Bang -> Quad(AnnotatedString("!${hit.key}"), hit.host, Lead.Icon(Icons.Outlined.Bolt), null)
    }
    val monoSub = hit is Hit.Bang
    val meta = when (hit) {
        is Hit.TabHit -> "Switch"
        is Hit.HistoryHit -> ago(browser.now - hit.entry.time)
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(R8)
            .background(if (primary) Orb.Field else Color.Transparent)
            .tap(R8) { onClick() }
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (leading) {
                is Lead.Icon -> Icon(leading.icon, null, tint = Orb.Text2, modifier = Modifier.size(20.dp))
                is Lead.Site -> SiteIcon(leading.url, 24.dp)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (hit is Hit.Bang) MonoBody.copy(fontSize = 15.sp) else MaterialTheme.typography.titleSmall,
                color = Orb.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(sub, style = if (monoSub) MonoSmall else MaterialTheme.typography.bodySmall, color = Orb.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (primary) {
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = Orb.Text3, modifier = Modifier.padding(start = 8.dp).size(16.dp))
        } else if (meta != null) {
            Text(meta, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TNUM), color = Orb.Text3, modifier = Modifier.padding(start = 8.dp))
        }
        if (fill != null) {
            IconButton(Icons.Outlined.NorthWest, "Edit", tint = Orb.Text3, size = 36.dp) { onFill(fill) }
        }
    }
}

private sealed interface Lead {
    data class Icon(val icon: ImageVector) : Lead
    data class Site(val url: String) : Lead
}

private data class Quad(val title: AnnotatedString, val sub: String, val lead: Lead, val fill: String?)

private fun matches(text: String, q: String): Boolean {
    if (q.isEmpty()) return true
    if (text.contains(q, ignoreCase = true)) return true
    // subsequence fuzzy match ("rdr" -> "Reader")
    var i = 0
    val t = text.lowercase()
    val s = q.lowercase()
    for (ch in t) if (i < s.length && ch == s[i]) i++
    return i == s.length && s.length >= 3
}

private fun highlight(text: String, q: String): AnnotatedString = buildAnnotatedString {
    val idx = if (q.isBlank()) -1 else text.indexOf(q, ignoreCase = true)
    if (idx < 0) {
        append(text); return@buildAnnotatedString
    }
    append(text.substring(0, idx))
    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
    append(text.substring(idx, idx + q.length))
    pop()
    append(text.substring(idx + q.length))
}
