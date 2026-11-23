package app.cardkeep.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment

/** Title and buttons a tab page puts in the shared top bar. */
class PageHeader(val title: State<String>, val actions: State<@Composable () -> Unit>)

/**
 * One top bar over the swipeable tab pages. Each page hands its title and buttons up here (keeping its
 * own state and logic); the bar shows the current page's and cross-fades when the page changes, so
 * only the content below swipes.
 */
class TabHeaders {
    internal val pages = mutableStateMapOf<Int, PageHeader>()
}

val LocalTabHeaders = compositionLocalOf<TabHeaders?> { null }
val LocalTabPage = compositionLocalOf { 0 }

/** Whether this tab page is the one showing (both pages stay composed; only the shown one handles Back). */
val LocalTabSelected = compositionLocalOf { true }

/**
 * Registers this page's title and buttons with the shared bar. Returns true when hosted, in which case
 * the screen shouldn't draw its own top bar.
 */
@Composable
fun hostedHeader(title: String, actions: @Composable () -> Unit): Boolean {
    val host = LocalTabHeaders.current ?: return false
    val page = LocalTabPage.current
    val t = rememberUpdatedState(title)
    val a = rememberUpdatedState(actions)
    DisposableEffect(host, page) {
        host.pages[page] = PageHeader(t, a)
        onDispose { host.pages.remove(page) }
    }
    return true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedTopBar(headers: TabHeaders, page: Int) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        title = {
            AnimatedContent(page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tabTitle") { p ->
                Text(headers.pages[p]?.title?.value.orEmpty(), style = MaterialTheme.typography.titleLarge)
            }
        },
        actions = {
            AnimatedContent(page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tabActions") { p ->
                Row(verticalAlignment = Alignment.CenterVertically) { headers.pages[p]?.actions?.value?.invoke() }
            }
        },
    )
}
