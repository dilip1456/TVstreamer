package tv.tvstreamer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

/**
 * Single owner of the TV side menu (1.99.5). Exactly two states: fully hidden (not composed,
 * zero width, not focusable) and fully open with labels, overlaying the page and a scrim.
 * Open/close is an explicit boolean, never derived from hasFocus (focus moving between
 * entries used to collapse the old peeking rail mid-animation).
 */
object SideMenu {
    const val PANEL_W = 280
    const val ANIM_MS = 200

    var open by mutableStateOf(false)
        private set
    var content: FocusRequester? = null
    var fallback: FocusRequester? = null
    val reqs: Map<RailEntry, FocusRequester> = RailEntry.entries.associateWith { FocusRequester() }

    fun rememberContent(fr: FocusRequester?) { if (fr != null) content = fr }

    fun open(from: FocusRequester? = null) {
        if (from != null) content = from
        open = true
    }

    fun close(restore: Boolean = true): Boolean {
        val was = open
        open = false
        if (!was || !restore) return was
        content?.let { if (runCatching { it.requestFocus() }.isSuccess) return true }
        fallback?.let { if (runCatching { it.requestFocus() }.isSuccess) return true }
        return was
    }
}

/** Bumped when something should land on the current tab of the top menu (Go to Home, long-press Menu). */
object AppChrome {
    var topFocusTick by mutableIntStateOf(0)
        private set
    fun requestTop() { topFocusTick++ }

    const val GO_HOME = "Go to Home"

    /** Set by App so any Options / context menu can jump to Home. */
    var navigate: ((Screen) -> Unit)? = null

    fun goHome() {
        val dest = navigate ?: return
        SideMenu.close(restore = false)
        requestTop()
        dest(Screen.Home)
    }
}

/** Pure focus-graph for TV edges: no dead ends that should reach a menu. */
object FocusGraph {
    enum class At {
        TOP_FIRST, TOP_OTHER, HERO, HERO_PLAY, HERO_OTHER_BTN,
        ROW_FIRST, ROW_OTHER, GRID_FIRST_COL, GRID_OTHER, SIDE_MENU, PAGE
    }
    enum class Go { SIDE_MENU, TOP_MENU, HERO_PLAY, STAY, DEFAULT, CLOSE_MENU }

    fun dest(at: At, dir: TabNav.Dir, tv: Boolean, sideOpen: Boolean = false): Go {
        if (!tv) return Go.DEFAULT
        if (sideOpen) return when (dir) {
            TabNav.Dir.RIGHT, TabNav.Dir.OTHER -> Go.CLOSE_MENU
            TabNav.Dir.LEFT, TabNav.Dir.UP, TabNav.Dir.DOWN -> Go.STAY
        }
        return when (dir) {
            TabNav.Dir.LEFT -> when (at) {
                At.TOP_FIRST, At.HERO, At.HERO_PLAY, At.ROW_FIRST, At.GRID_FIRST_COL, At.PAGE -> Go.SIDE_MENU
                At.TOP_OTHER, At.HERO_OTHER_BTN, At.ROW_OTHER, At.GRID_OTHER -> Go.DEFAULT
                At.SIDE_MENU -> Go.STAY
            }
            TabNav.Dir.UP -> when (at) {
                At.HERO, At.HERO_PLAY, At.HERO_OTHER_BTN -> Go.TOP_MENU
                At.SIDE_MENU -> Go.STAY
                else -> Go.DEFAULT
            }
            TabNav.Dir.DOWN -> when (at) {
                At.HERO -> Go.HERO_PLAY
                At.TOP_FIRST, At.TOP_OTHER -> Go.DEFAULT
                At.SIDE_MENU -> Go.STAY
                else -> Go.DEFAULT
            }
            TabNav.Dir.RIGHT -> if (at == At.SIDE_MENU) Go.CLOSE_MENU else Go.DEFAULT
            TabNav.Dir.OTHER -> Go.DEFAULT
        }
    }
}

/**
 * Full-screen overlay. Hidden = nothing in the tree. Open = a labelled panel plus scrim.
 * Animation targets only those two states (no collapsed icon-rail width).
 */
@Composable
fun SideMenuPanel(current: RailEntry?, onPick: (RailEntry) -> Unit) {
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = SideMenu.open
    if (!visible.currentState && !visible.targetState) return
    val entries = RailEntry.entries
    val frs = remember { entries.map { SideMenu.reqs.getValue(it) } }
    var ix by remember { mutableIntStateOf(entries.indexOf(current ?: RailEntry.HOME).coerceAtLeast(0)) }
    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(tween(SideMenu.ANIM_MS)) + slideInHorizontally(tween(SideMenu.ANIM_MS)) { -it },
        exit = fadeOut(tween(SideMenu.ANIM_MS)) + slideOutHorizontally(tween(SideMenu.ANIM_MS)) { -it },
        modifier = Modifier.fillMaxSize().zIndex(8f)
    ) {
        Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionUp -> { if (ix > 0) runCatching { frs[ix - 1].requestFocus() }; true }
                Key.DirectionDown -> { if (ix < entries.size - 1) runCatching { frs[ix + 1].requestFocus() }; true }
                Key.DirectionLeft -> true
                Key.DirectionRight -> { SideMenu.close(); true }
                Key.Back, Key.Escape -> { SideMenu.close(); true }
                else -> false
            }
        }) {
            Box(Modifier.fillMaxSize().background(Pal.Scrim).pointerInput(Unit) { detectTapGestures { SideMenu.close() } })
            Column(
                Modifier.fillMaxHeight().width(SideMenu.PANEL_W.dp)
                    .background(Color(0xF20A121C))
                    .padding(start = 16.dp, end = 20.dp, top = 36.dp, bottom = 28.dp)
                    .pointerInput(Unit) { detectTapGestures { } },
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                entries.forEachIndexed { i, e ->
                    if (e == RailEntry.EXIT) Spacer(Modifier.height(18.dp))
                    SideMenuItem(e, e == current, Modifier.focusRequester(frs[i]).onFocusChanged { if (it.isFocused) ix = i }) {
                        onPick(e)
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            delay(32)
            val want = current ?: RailEntry.HOME
            ix = entries.indexOf(want).coerceAtLeast(0)
            runCatching { SideMenu.reqs.getValue(want).requestFocus() }
        }
    }
}

@Composable
private fun SideMenuItem(e: RailEntry, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val fg = when { f -> Pal.OnFocusPill; selected -> Pal.PrimaryText; else -> Color(0xFFB4C0CE) }
    Row(
        modifier.width(244.dp).onFocusChanged { f = it.isFocused }.clip(shape)
            .background(if (f) Pal.FocusPill else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = e.label }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(RailIcons.of(e), null, tint = fg, modifier = Modifier.size(24.dp))
        Text(e.label, color = fg, fontSize = (Ts.ROW + 2).sp, maxLines = 1,
            fontWeight = if (selected || f) FontWeight.Bold else FontWeight.Medium)
    }
}
