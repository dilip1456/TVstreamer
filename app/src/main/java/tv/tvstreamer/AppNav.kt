package tv.tvstreamer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.ui.composed
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
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

/** Bluey mark only (no wordmark): the top bar on Home / Movies / Series / Live TV. Not focusable. */
@Composable
fun BlueyMark(modifier: Modifier = Modifier, size: Dp = Layout.LOGO_TV) {
    Image(
        painterResource(R.drawable.ic_bluey),
        contentDescription = stringResource(R.string.app_name),
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit
    )
}

/**
 * One spacing / layout scale (1.99). TV keeps content inside the title-safe area (about 5 % of a 1080p screen:
 * 48 dp at the sides, 27 dp at the top); phones use tighter margins.
 */
object Layout {
    val TV_SIDE = 48.dp
    val TV_TOP = 27.dp
    val PHONE_SIDE = 14.dp
    val PHONE_TOP = 10.dp
    /** Compact logo in the top-left corner. */
    val LOGO_TV = 26.dp
    val LOGO_PHONE = 24.dp
    /** Gaps: XS 4, S 8, M 12, L 16, XL 24, XXL 32. */
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 24.dp
    val XXL = 32.dp
    fun side(tv: Boolean) = if (tv) TV_SIDE else PHONE_SIDE
}

/** One entry of the TV top menu. [icon]: drawn instead of the label (the label is still read out). */
class TopTab(val key: String, val label: String, val icon: ImageVector? = null, val onClick: () -> Unit)

/** The TV top menu entries, the same on every screen that shows the menu. */
fun topTabs(go: (Screen) -> Unit, onSearch: () -> Unit): List<TopTab> = listOf(
    TopTab("search", "Search", RailIcons.Search, onSearch),
    TopTab("home", "Home") { go(Screen.Home) },
    TopTab("movies", "Movies") { go(Screen.MoviesTab) },
    TopTab("series", "TV Shows") { go(Screen.SeriesTab) },
    TopTab("live", "Live TV") { go(Screen.Live) }
)

/**
 * TV / tablet top menu (Netflix style), shared by Home, Movies, TV Shows and Live TV: compact logo top-left, tabs
 * centred, profile on the right. Left / Right stay inside the bar; Left on the first tab opens the side menu [rail]
 * only when the rail is available (the page has been scrolled and the top bar is hidden). Down goes to the page.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TopNavBar(current: String, tabs: List<TopTab>, rail: RailFocus?, onProfile: () -> Unit, modifier: Modifier = Modifier,
              tabModifier: (String) -> Modifier = { Modifier }) {
    val count = tabs.size + 1
    val frs = remember(count) { List(count) { FocusRequester() } }
    var ix by remember { mutableIntStateOf(-1) }
    Box(
        modifier.fillMaxWidth().heightIn(min = 44.dp)
            .menuRow({ ix }, { count }, onLeftEdge = rail?.let { r -> { r.content = frs[0]; r.enter() } }) { n ->
                ix = n
                runCatching { frs.getOrNull(n)?.requestFocus() }
            }
            .onFocusChanged { if (!it.hasFocus) ix = -1 }
            .focusRestorer(),
        contentAlignment = Alignment.Center
    ) {
        BlueyMark(Modifier.align(Alignment.CenterStart), size = Layout.LOGO_TV)
        Row(horizontalArrangement = Arrangement.spacedBy(Layout.XS), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { i, t ->
                NavTab(t.label, t.onClick,
                    Modifier.focusRequester(frs[i]).onFocusChanged { if (it.isFocused) ix = i }.then(tabModifier(t.key)),
                    selected = t.key == current, icon = t.icon)
            }
        }
        Box(Modifier.align(Alignment.CenterEnd).focusRequester(frs[tabs.size])
            .onFocusChanged { if (it.hasFocus) ix = tabs.size }) { AvatarButton(onProfile) }
    }
}

/** Asks "Exit Bluey?" (account menu, Options, and the TV side menu all use the same confirm). */
fun exitApp(ctx: Context) {
    AppExit.show()
}

/** Really leaves the app. Only the Exit button on the confirm dialog calls this. */
fun finishApp(ctx: Context) {
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
    val fg = when { f -> Pal.OnFocusPill; selected -> Pal.PrimaryText; else -> Color(0xFFB4C0CE) }
    Row(
        modifier.then(if (open) Modifier.width(212.dp) else Modifier)
            .onFocusChanged { f = it.isFocused }
            .clip(shape)
            .background(if (f) Pal.FocusPill else Color.Transparent)
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
        else if (selected) Box(Modifier.size(4.dp).clip(RoundedCornerShape(50)).background(Pal.Primary))
    }
}

/**
 * Apple TV / Netflix-style focus pivot (1.99.3). The focused card stays at a fixed offset from the
 * leading edge (about 12 %, clamped to 10–15 %), and the row slides smoothly under it.
 * Near the list's ends scrolling simply stops (the list cannot go past its start or end).
 */
object FocusPivot {
    const val RATIO = 0.12f
    const val MIN_RATIO = 0.10f
    const val MAX_RATIO = 0.15f
    const val SCROLL_MS = Motion.SCROLL_MS
    /** Focused poster / channel-tile scale on TV rows. */
    const val CARD_SCALE = 1.08f

    fun pivotPx(viewport: Float, ratio: Float = RATIO): Float {
        if (viewport <= 0f) return 0f
        return viewport * ratio.coerceIn(MIN_RATIO, MAX_RATIO)
    }

    /** Rect (along one axis) the parent list should bring into view: [start] - [pivot] … that + [viewport]. */
    fun span(start: Float, pivot: Float, viewport: Float): Pair<Float, Float> = (start - pivot) to (start - pivot + viewport)

    /** Pixels the list should scroll so the item whose viewport offset is [itemOffset] lands at [pivot]. */
    fun scrollBy(itemOffset: Float, pivot: Float): Float = itemOffset - pivot

    fun offsetOf(state: LazyListState, index: Int): Float? =
        state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset?.toFloat()

    fun offsetOfKey(state: LazyListState, key: Any): Float? =
        state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset?.toFloat()
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.pivotInList(
    vertical: Boolean,
    state: LazyListState,
    index: () -> Int = { -1 },
    key: Any? = null,
    ratio: Float = FocusPivot.RATIO,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val job = remember(state) { arrayOfNulls<Job>(1) }
    fun viewport(): Int {
        val s = state.layoutInfo.viewportSize
        return if (vertical) s.height else s.width
    }
    fun itemOffset(): Float? = if (key != null) FocusPivot.offsetOfKey(state, key) else FocusPivot.offsetOf(state, index())
    fun runScroll() {
        val vp = viewport()
        val src = itemOffset() ?: return
        val d = FocusPivot.scrollBy(src, FocusPivot.pivotPx(vp.toFloat(), ratio))
        if (kotlin.math.abs(d) < 1f) return
        job[0]?.cancel()
        job[0] = scope.launch {
            runCatching { state.animateScrollBy(d, Motion.scrollSpec()) }
        }
    }
    val responder = remember(vertical, state, ratio, key) {
        object : androidx.compose.foundation.relocation.BringIntoViewResponder {
            override fun calculateRectForParent(localRect: androidx.compose.ui.geometry.Rect): androidx.compose.ui.geometry.Rect {
                val vp = viewport().toFloat()
                if (vp <= 0f) return localRect
                val pivot = FocusPivot.pivotPx(vp, ratio)
                val (a, b) = FocusPivot.span(0f, pivot, vp)
                return if (vertical) androidx.compose.ui.geometry.Rect(localRect.left, a, localRect.right, b)
                else androidx.compose.ui.geometry.Rect(a, localRect.top, b, localRect.bottom)
            }
            override suspend fun bringChildIntoView(localRect: () -> androidx.compose.ui.geometry.Rect?) {
                val vp = viewport()
                val src = itemOffset() ?: return
                val d = FocusPivot.scrollBy(src, FocusPivot.pivotPx(vp.toFloat(), ratio))
                if (kotlin.math.abs(d) < 1f) return
                job[0]?.cancel()
                // Same ScrollableState animation: a new focus (held key at 110 ms) cancels this and starts again,
                // so steps never queue up. After we settle at the pivot the parent list has nothing left to jump.
                try { state.animateScrollBy(d, Motion.scrollSpec()) }
                catch (e: CancellationException) { throw e }
            }
        }
    }
    this
        .onFocusChanged { f -> if (f.hasFocus || f.isFocused) runScroll() }
        .bringIntoViewResponder(responder)
}

/** The open Home / Movies / TV / Live screen reports its chrome so App-level Back can act on it. */
@Composable
fun MainBackSync(
    isTv: Boolean,
    railFocused: Boolean,
    topMenuFocused: Boolean,
    listState: LazyListState,
    closeRail: () -> Unit,
    focusTop: () -> Unit,
) {
    val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 0
    SideEffect {
        MainBack.isTv = isTv
        MainBack.railFocused = railFocused
        MainBack.topMenuFocused = topMenuFocused
        MainBack.atTop = atTop
        MainBack.closeRail = closeRail
        MainBack.focusTop = focusTop
    }
    DisposableEffect(Unit) { onDispose { MainBack.reset() } }
}
