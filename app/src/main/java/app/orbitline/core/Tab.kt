package app.orbitline.core

import android.os.Bundle
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import java.util.UUID

class Tab(
    val id: String = UUID.randomUUID().toString(),
    spaceId: String,
    val ghost: Boolean = false,
    url: String = "",
    title: String = "",
    val parentId: String? = null,
) {
    var spaceId by mutableStateOf(spaceId)
    var url by mutableStateOf(url)
    var title by mutableStateOf(title)
    var progress by mutableIntStateOf(100)
    var loading by mutableStateOf(false)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var themeColor by mutableStateOf<Color?>(null)
    var favicon by mutableStateOf<ImageBitmap?>(null)
    var thumbnail by mutableStateOf<ImageBitmap?>(null)
    var blockedCount by mutableIntStateOf(0)
    var desktop by mutableStateOf(false)
    var zapMode by mutableStateOf(false)
    /** URL that Flow mode stopped us from opening. */
    var flowBlocked by mutableStateOf<String?>(null)
    /** True while the native start page is shown instead of the WebView. */
    var showHome by mutableStateOf(url.isEmpty())
    /** The tab began on the start page, so "back" from the first page returns there. */
    var startedFromHome = url.isEmpty()
    /** False until this tab's WebView has painted its first frame. */
    var painted by mutableStateOf(false)
    var lastActive by mutableLongStateOf(System.currentTimeMillis())

    var webView by mutableStateOf<WebView?>(null)
    /** WebView state kept while the tab is hibernated to save memory. */
    var savedState: Bundle? = null

    @Volatile
    var pageHost: String? = Url.host(url)

    val displayTitle: String
        get() = when {
            showHome -> if (ghost) "Ghost tab" else "New tab"
            title.isNotBlank() -> title
            else -> Url.pretty(url)
        }
}
