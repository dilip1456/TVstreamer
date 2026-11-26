package tv.tvstreamer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/** Phoenix mark only (no wordmark): the top bar on Home / Movies / Series / Live TV. Not focusable. */
@Composable
fun PhoenixMark(modifier: Modifier = Modifier, size: Dp = 34.dp) {
    Image(
        painterResource(R.drawable.ic_phoenix),
        contentDescription = stringResource(R.string.app_name),
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit
    )
}

/** Closes the app (Exit in the account / options menus and the TV side menu). */
fun exitApp(ctx: Context) {
    var c: Context? = ctx
    while (c is ContextWrapper) {
        if (c is Activity) { c.finishAndRemoveTask(); return }
        c = c.baseContext
    }
}

/**
 * D-pad rules inside one home row (1.91). Plain functions so they can be unit-tested.
 * Left on the first card goes to the side menu (TV) and Right on the last card (the "See all" tile when there is one)
 * stays put: focus never jumps to another row at a row's ends. Up / Down are left to the page.
 */
object RowNav {
    enum class Action { NONE, STAY, TO_MENU }

    fun onKey(index: Int, count: Int, dir: TabNav.Dir, hasMenu: Boolean): Action {
        if (index < 0 || count <= 0) return Action.NONE
        return when (dir) {
            TabNav.Dir.LEFT -> if (index == 0) (if (hasMenu) Action.TO_MENU else Action.STAY) else Action.NONE
            TabNav.Dir.RIGHT -> if (index >= count - 1) Action.STAY else Action.NONE
            else -> Action.NONE
        }
    }
}

/** Entries of the TV side menu, top to bottom. */
enum class RailEntry(val label: String) { SEARCH("Search"), HOME("Home"), MOVIES("Movies"), SERIES("TV Shows"), LIVE("Live TV"), EXIT("Exit") }

object RailIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.White)).build()
    val Search = icon("search", "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z")
    val Exit = icon("exit", "M10.09,15.59L11.5,17l5,-5 -5,-5 -1.41,1.41L12.67,11H3v2h9.67l-2.58,2.59zM19,3H5c-1.11,0 -2,0.9 -2,2v4h2V5h14v14H5v-4H3v4c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2z")
    fun of(e: RailEntry): ImageVector = when (e) {
        RailEntry.SEARCH -> Search
        RailEntry.HOME -> NavIcons.Home
        RailEntry.MOVIES -> NavIcons.Movie
        RailEntry.SERIES -> NavIcons.Series
        RailEntry.LIVE -> NavIcons.Live
        RailEntry.EXIT -> Exit
    }
}

/** Collapsed width of the TV side menu; page content starts to the right of it. */
val RAIL_W = 64.dp

/** Moves focus between the side menu and the page. Set by the screen that shows the menu. */
class RailFocus {
    val reqs: Map<RailEntry, FocusRequester> = RailEntry.entries.associateWith { FocusRequester() }
    /** Entry focused when the menu is entered (the current screen's). */
    var current: RailEntry = RailEntry.HOME
    /** Last focused card / button on the page (Right from the menu goes back to it). */
    var content: FocusRequester? = null
    var fallback: FocusRequester? = null
    fun enter() { runCatching { reqs.getValue(current).requestFocus() } }
    fun leave(): Boolean {
        content?.let { if (runCatching { it.requestFocus() }.isSuccess) return true }
        fallback?.let { if (runCatching { it.requestFocus() }.isSuccess) return true }
        return false
    }
}

/**
 * Netflix-style TV side menu: icons on the left edge; while it has focus it widens over the page with labels
 * on a dark wash. Up / Down move inside it (never out), Right goes back to the page, Back is left to the screen.
 */
@Composable
fun SideRail(current: RailEntry?, focus: RailFocus, onPick: (RailEntry) -> Unit, modifier: Modifier = Modifier) {
    val entries = RailEntry.entries
    val frs = remember(focus) { entries.map { focus.reqs.getValue(it) } }
    SideEffect { focus.current = current ?: RailEntry.HOME }
    var open by remember { mutableStateOf(false) }
    var ix by remember { mutableIntStateOf(-1) }
    val w by animateDpAsState(if (open) 240.dp else RAIL_W, tween(170), label = "rail")
    Box(
        modifier.zIndex(2f).fillMaxHeight().width(w)
            .background(
                if (open) Brush.horizontalGradient(listOf(Color(0xF7000000), Color(0xE6000000), Color(0x00000000)))
                else Brush.horizontalGradient(listOf(Color(0xB3000000), Color(0x00000000)))
            )
            .onFocusChanged { open = it.hasFocus; if (!it.hasFocus) ix = -1 }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || ix < 0) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> { if (ix > 0) runCatching { frs[ix - 1].requestFocus() }; true }
                    Key.DirectionDown -> { if (ix < entries.size - 1) runCatching { frs[ix + 1].requestFocus() }; true }
                    Key.DirectionLeft -> true
                    Key.DirectionRight -> { focus.leave(); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Column(Modifier.padding(start = 10.dp, end = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            entries.forEachIndexed { i, e ->
                if (e == RailEntry.EXIT) Spacer(Modifier.height(18.dp))
                RailItem(e, open, e == current, Modifier.focusRequester(frs[i]).onFocusChanged { if (it.isFocused) ix = i }) { onPick(e) }
            }
        }
    }
}

@Composable
private fun RailItem(e: RailEntry, open: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val fg = when { f -> Color(0xFF141414); selected -> Pal.Gold; else -> Color(0xFFB8B8B8) }
    Row(
        modifier.then(if (open) Modifier.width(212.dp) else Modifier)
            .onFocusChanged { f = it.isFocused }
            .clip(shape)
            .background(if (f) Color(0xF2F5F0E6) else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = e.label }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(RailIcons.of(e), null, tint = fg, modifier = Modifier.size(24.dp))
        }
        if (open) Text(e.label, color = fg, fontSize = (Ts.ROW + 2).sp, maxLines = 1,
            fontWeight = if (selected || f) FontWeight.Bold else FontWeight.Medium)
        else if (selected) Box(Modifier.size(4.dp).clip(RoundedCornerShape(50)).background(Pal.Gold))
    }
}
