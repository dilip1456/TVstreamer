package tv.tvstreamer

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** D-pad wrap in poster grids: Right on a row's last card goes to the next row's first; Left on a row's first card
 *  goes to the menu on the left when there is one, else to the previous row's last card. */
object GridNav {
    /** Card to focus for Right at [index] in column [col] of [cols]; null = let the default handle it. */
    fun right(index: Int, col: Int, cols: Int, total: Int): Int? =
        if (cols > 0 && col == cols - 1 && index + 1 < total) index + 1 else null

    /** Card to focus for Left at [index] in column [col] when nothing is on the left ([navTookIt] = false). */
    fun left(index: Int, col: Int, navTookIt: Boolean): Int? =
        if (col == 0 && !navTookIt && index > 0) index - 1 else null
}

/** Focus requesters of a grid's cards by index (registered while composed). */
class GridFocus { val reqs = HashMap<Int, FocusRequester>() }

/**
 * Wrap handling for one card at [index] of [total] (indexes are the cards', the grid's own item index is
 * [index] + [offset], e.g. 1 when a header item comes first).
 */
@Composable
fun Modifier.gridWrap(index: Int, total: Int, state: LazyGridState, focus: GridFocus, offset: Int = 1): Modifier {
    val fm = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val fr = remember(index) { FocusRequester() }
    androidx.compose.runtime.DisposableEffect(index) { focus.reqs[index] = fr; onDispose { if (focus.reqs[index] === fr) focus.reqs.remove(index) } }
    fun go(i: Int) {
        val r = focus.reqs[i]
        if (r != null && runCatching { r.requestFocus() }.isSuccess) return
        scope.launch { runCatching { state.scrollToItem(i + offset) }; delay(60); runCatching { focus.reqs[i]?.requestFocus() } }
    }
    return this.focusRequester(fr).onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val info = state.layoutInfo
        val me = info.visibleItemsInfo.firstOrNull { it.index == index + offset } ?: return@onPreviewKeyEvent false
        val cols = info.visibleItemsInfo.filter { it.index >= offset }.maxOfOrNull { it.column + 1 } ?: 0
        when (e.key) {
            Key.DirectionRight -> GridNav.right(index, me.column, cols, total)?.let { go(it); true } ?: false
            Key.DirectionLeft -> {
                if (me.column != 0) false
                else {
                    val moved = fm.moveFocus(FocusDirection.Left)
                    GridNav.left(index, me.column, moved)?.let { go(it) }
                    true
                }
            }
            else -> false
        }
    }
}
