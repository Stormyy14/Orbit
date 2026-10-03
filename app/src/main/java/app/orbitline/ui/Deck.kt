package app.orbitline.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orbitline.core.Tab
import app.orbitline.core.Url
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * The Deck: one page per space, ghost tabs at the far end. Cards swipe sideways to close,
 * long-press to move between spaces.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Deck(onClose: () -> Unit, onSpaces: () -> Unit) {
    val browser = LocalBrowser.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val spaces = browser.spaces
    val ghostPage = spaces.size
    val startPage = if (browser.current?.ghost == true) ghostPage else spaces.indexOfFirst { it.id == browser.currentSpaceId }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = startPage) { spaces.size + 1 }

    LaunchedEffect(Unit) { browser.current?.let(browser::captureThumbnail) }

    Column(Modifier.fillMaxSize().background(Orb.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tabs", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(Icons.Outlined.Tune, "Manage spaces", tint = Orb.Text2) { onSpaces() }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(spaces) { i, s ->
                SpaceTab(s.name, browser.tabsIn(s.id).size, pager.currentPage == i, { SpaceGlyph(s, 16.dp, tint = if (pager.currentPage == i) Orb.Text else Orb.Text2) }) {
                    haptics.tick(); scope.launch { pager.animateScrollToPage(i, animationSpec = tween(200)) }
                }
            }
            item {
                SpaceTab("Ghost", browser.ghostTabs.size, pager.currentPage == ghostPage, {
                    Icon(Icons.Outlined.VisibilityOff, null, tint = Orb.Ghost, modifier = Modifier.size(14.dp))
                }) { haptics.tick(); scope.launch { pager.animateScrollToPage(ghostPage, animationSpec = tween(200)) } }
            }
        }
        Hairline()
        // Cards own horizontal swipes (to close), so spaces switch via the tabs above.
        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1, userScrollEnabled = false) { page ->
            val ghost = page == ghostPage
            val list = if (ghost) browser.ghostTabs else browser.tabsIn(spaces[page].id)
            if (list.isEmpty()) {
                EmptyDeck(ghost)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list, key = { it.id }) { t ->
                        TabCard(
                            t,
                            current = t.id == browser.currentId,
                            modifier = Modifier.animateItem(),
                            onOpen = { haptics.confirm(); browser.select(t); onClose() },
                            onClose = { haptics.tick(); browser.closeTab(t) },
                        )
                    }
                }
            }
        }
        Hairline()
        val ghost = pager.currentPage == ghostPage
        Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp)) {
            Text(
                "Close all",
                style = MaterialTheme.typography.labelLarge,
                color = if (ghost) Orb.Red else Orb.Text,
                modifier = Modifier.align(Alignment.CenterStart).tap(R8) {
                    haptics.heavy()
                    if (ghost) browser.burnGhosts() else browser.closeAll(spaces.getOrNull(pager.currentPage)?.id, false)
                }.padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Box(
                Modifier.align(Alignment.Center).size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Orb.Contrast)
                    .tap(androidx.compose.foundation.shape.CircleShape) {
                        haptics.confirm()
                        if (ghost) browser.newTab(ghost = true) else browser.newTab(spaceId = spaces[pager.currentPage].id)
                        onClose()
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Add, if (ghost) "New ghost tab" else "New tab", tint = Orb.OnContrast, modifier = Modifier.size(22.dp)) }
            Text(
                "Done",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterEnd).tap(R8) { onClose() }.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun SpaceTab(name: String, count: Int, selected: Boolean, leading: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .height(34.dp)
            .clip(R8)
            .background(if (selected) Orb.Field else Color.Transparent)
            .tap(R8) { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.labelLarge, color = if (selected) Orb.Text else Orb.Text2)
        Spacer(Modifier.width(6.dp))
        Text(count.toString(), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TNUM), color = Orb.Text3)
    }
}

@Composable
private fun EmptyDeck(ghost: Boolean) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 32.dp)) {
        Text(if (ghost) "No ghost tabs" else "No open tabs", style = MaterialTheme.typography.bodyLarge, color = Orb.Text2)
    }
}

@Composable
private fun TabCard(tab: Tab, current: Boolean, modifier: Modifier, onOpen: () -> Unit, onClose: () -> Unit) {
    val browser = LocalBrowser.current
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    val dismissPx = with(density) { 110.dp.toPx() }
    var menu by remember { mutableStateOf(false) }
    val shape = R12
    val ghostEdge = Orb.Ghost
    val dash = with(density) { 4.dp.toPx() }

    Box(
        modifier
            .graphicsLayer {
                translationX = dx.value
                alpha = 1f - (abs(dx.value) / (dismissPx * 2.2f)).coerceIn(0f, 0.8f)
            }
            .pointerInput(tab.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (abs(dx.value) > dismissPx) {
                                dx.animateTo(if (dx.value > 0) dismissPx * 4 else -dismissPx * 4, tween(160))
                                onClose()
                            } else dx.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy))
                        }
                    },
                    onDragCancel = { scope.launch { dx.animateTo(0f) } },
                ) { change, amount ->
                    change.consume()
                    scope.launch { dx.snapTo(dx.value + amount) }
                }
            },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(shape)
                .background(Orb.Surface)
                .then(
                    when {
                        current -> Modifier.border(1.5.dp, Orb.Text, shape)
                        tab.ghost -> Modifier.drawBehind {
                            drawRoundRect(
                                ghostEdge, cornerRadius = CornerRadius(12.dp.toPx()),
                                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                            )
                        }
                        else -> Modifier.border(1.dp, Orb.Border, shape)
                    },
                )
                .tap(shape, onLongClick = { haptics.heavy(); menu = true }) { onOpen() },
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (tab.showHome) {
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        if (tab.ghost) Icon(Icons.Outlined.VisibilityOff, null, tint = Orb.Ghost, modifier = Modifier.size(14.dp))
                        else SpaceGlyph(browser.spaces.firstOrNull { it.id == tab.spaceId }, 15.dp, tint = Orb.Text2)
                    }
                } else SiteIcon(tab.url, 18.dp, tab.favicon)
                Spacer(Modifier.width(8.dp))
                Text(
                    tab.displayTitle, style = MaterialTheme.typography.labelMedium, color = Orb.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                IconButton(Icons.Outlined.Close, "Close tab", tint = Orb.Text2, size = 32.dp) { onClose() }
            }
            Hairline()
            Box(Modifier.fillMaxSize().background(Orb.Bg)) {
                val thumb = tab.thumbnail
                when {
                    tab.showHome -> Unit
                    thumb != null -> Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                    else -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        SiteIcon(tab.url, 32.dp, tab.favicon)
                        Spacer(Modifier.height(8.dp))
                        Text(Url.pretty(tab.url), style = MaterialTheme.typography.bodySmall, color = Orb.Text2, maxLines = 1)
                    }
                }
                if (tab.ghost) GhostCountdown(tab, Modifier.align(Alignment.BottomEnd).padding(8.dp))
                if (tab.loading) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(8.dp).size(24.dp).clip(CircleShape).background(Orb.Surface),
                        contentAlignment = Alignment.Center,
                    ) { OrbitSpinner(18.dp) }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Orb.Surface) {
            if (!tab.ghost) {
                browser.spaces.filter { it.id != tab.spaceId }.forEach { s ->
                    DropdownMenuItem(
                        leadingIcon = { SpaceGlyph(s, 18.dp, tint = Orb.Text2) },
                        text = { Text("Move to ${s.name}") },
                        onClick = { menu = false; browser.moveTab(tab, s.id) },
                    )
                }
            }
            if (!tab.showHome) {
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                    menu = false; browser.newTab(tab.url, spaceId = tab.spaceId, ghost = tab.ghost, select = false)
                })
                DropdownMenuItem(text = { Text("Copy link") }, onClick = { menu = false; browser.copy(tab.url) })
            }
            DropdownMenuItem(text = { Text("Close", color = Orb.Red) }, onClick = { menu = false; onClose() })
        }
    }
}

/** Time left before a ghost tab closes itself. */
@Composable
private fun GhostCountdown(tab: Tab, modifier: Modifier) {
    val browser = LocalBrowser.current
    val ttl = browser.settings.ghostMinutes * 60_000L
    val left = if (tab.id == browser.currentId) ttl else (ttl - (browser.now - tab.lastActive)).coerceAtLeast(0)
    Text(
        formatDuration(left),
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TNUM),
        color = Orb.Ghost,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Orb.Surface)
            .border(1.dp, Orb.Border, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
