package tv.tvstreamer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One player option (1.68): icon + label; goes under the ⋮ button when the line has no room for it. */
class BarAction(
    val key: String,
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val fr: FocusRequester? = null,
    val active: Boolean = false,
)

/** Single-line fitting rule for the option bar (pure, unit tested). */
object BarFit {
    /**
     * How many of [widths] (in order) fit on one line of [max] px with [gap] between items, keeping room for the
     * ⋮ button ([more] px, always shown last). Items that do not fit move to the ⋮ menu, never to a second line.
     */
    fun count(widths: List<Int>, gap: Int, more: Int, max: Int): Int {
        if (max <= 0) return widths.size
        var x = more
        var n = 0
        for (w in widths) {
            val next = x + gap + w
            if (next > max) break
            x = next; n++
        }
        return n
    }

    /** Options bar position: bottom by default; left / right only when the user moved it (remembered). */
    fun pos(saved: String?): String = when (saved) { "left", "right" -> saved; else -> "bottom" }

    /** Next position when the user moves the bar: bottom → left → right → bottom. */
    fun nextPos(cur: String): String = when (pos(cur)) { "bottom" -> "left"; "left" -> "right"; else -> "bottom" }
}

/**
 * Netflix-style player button: white icon and label on a clear background; on focus (D-pad) or press it fills white
 * with black content and grows a little. [labelShown] false = icon only (label stays the accessibility name).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NetflixButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                  active: Boolean = false, large: Boolean = false, labelShown: Boolean = true, stacked: Boolean = false,
                  interactive: Boolean = true) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.08f else 1f, Motion.spec(), label = "nfScale")
    val fg = if (f) Color.Black else Color.White
    val bg = when { f -> Color.White; active -> Color(0x33FFFFFF); else -> Color(0x14FFFFFF) }
    val shape = RoundedCornerShape(if (large) 8.dp else 6.dp)
    val iconSz = if (large) 26.dp else 22.dp
    // interactive = false: a measuring copy (OptionsBar's probe pass), never focusable or clickable
    val m = (if (interactive) modifier.popupOpener().onFocusChanged { f = it.isFocused } else modifier)
        .graphicsLayer { scaleX = s; scaleY = s }
        .clip(shape)
        .background(bg)
        .then(if (interactive) Modifier.combinedClickable(onClick = onClick).semantics { contentDescription = label } else Modifier)
    if (stacked && labelShown) {
        Column(m.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(iconSz))
            Text(label, color = fg, fontSize = Ts.CAPTION.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Clip)
        }
        return
    }
    Row(m.padding(horizontal = if (labelShown) (if (large) 16.dp else 12.dp) else 9.dp, vertical = if (large) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(iconSz))
        if (labelShown) Text(label, color = fg, fontSize = (if (large) Ts.ROW else Ts.HEAD).sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/**
 * The player's option buttons on ONE line (1.68): as many as fit, in order, then the vertical ⋮ button (always last,
 * opens the options menu). Whatever does not fit is reported through [onOverflow] so the ⋮ menu lists it. There is no
 * "More" button and the line never wraps. [vertical] lays the same buttons out as one column (bar moved left / right).
 */
@Composable
fun OptionsBar(actions: List<BarAction>, onMore: () -> Unit, onOverflow: (List<BarAction>) -> Unit, modifier: Modifier = Modifier,
               large: Boolean = false, stacked: Boolean = false, vertical: Boolean = false, moreFr: FocusRequester? = null) {
    val overflowCb by rememberUpdatedState(onOverflow)
    var shownCount by remember { mutableIntStateOf(actions.size) }
    val moreBtn = @Composable {
        NetflixButton(Ic.More, "More options", onMore, if (moreFr != null) Modifier.focusRequester(moreFr) else Modifier,
            large = large, labelShown = false)
    }
    @Composable fun btn(a: BarAction, probe: Boolean) {
        NetflixButton(a.icon, a.label, a.onClick,
            if (!probe && a.fr != null) Modifier.focusRequester(a.fr) else Modifier,
            active = a.active, large = large, stacked = stacked, interactive = !probe)
    }
    LaunchedEffect(shownCount, actions.map { it.key + it.label }) { overflowCb(actions.drop(shownCount)) }
    SubcomposeLayout(modifier) { c ->
        val gap = (if (large) 12 else 8).dp.roundToPx()
        val loose = Constraints(maxWidth = Constraints.Infinity, maxHeight = c.maxHeight)
        if (vertical) {
            // Column (bar moved to a side): every option, then ⋮; tall screens have room for all of them
            val ps = subcompose("v") { actions.forEach { btn(it, false) }; moreBtn() }.map { it.measure(loose) }
            val w = ps.maxOfOrNull { it.width } ?: 0
            val h = ps.sumOf { it.height } + gap * (ps.size - 1).coerceAtLeast(0)
            if (shownCount != actions.size) shownCount = actions.size
            return@SubcomposeLayout layout(w, h) {
                var y = 0
                ps.forEach { p -> p.place((w - p.width) / 2, y); y += p.height + gap }
            }
        }
        // Probe pass (not focusable, not placed): widths of every button and of ⋮
        val probes = subcompose("probe") { actions.forEach { btn(it, true) } }.map { it.measure(loose) }
        val moreProbe = subcompose("probeMore") { NetflixButton(Ic.More, "More options", {}, large = large, labelShown = false, interactive = false) }
            .first().measure(loose)
        val max = if (c.hasBoundedWidth) c.maxWidth else Int.MAX_VALUE
        val n = BarFit.count(probes.map { it.width }, gap, moreProbe.width, max)
        if (n != shownCount) shownCount = n
        val real = subcompose("real") { actions.take(n).forEach { btn(it, false) }; moreBtn() }.map { it.measure(loose) }
        val h = real.maxOfOrNull { it.height } ?: 0
        val w = (real.sumOf { it.width } + gap * (real.size - 1).coerceAtLeast(0)).coerceAtMost(max)
        layout(w, h) {
            var x = 0
            real.forEach { p -> p.place(x, (h - p.height) / 2); x += p.width + gap }
        }
    }
}
