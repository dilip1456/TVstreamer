@file:OptIn(ExperimentalComposeUiApi::class)

package tv.tvstreamer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The TV top menu, the same on Home, Movies, Series and Live TV (1.75), laid out like Netflix: the name on the left,
 * the tabs next to it, Search and the profile on the right. D-pad order follows the screen: Home, Movies, Series,
 * Live TV, Search, Profile. Left / Right stay in the bar, Down leaves it (see [menuRow]).
 * [current] is the tab key of this screen ("home", "movies", "series", "live"). [extra] adds a caller's own focus
 * handling to one key ("home" ... "live", "search", "profile"); [onFocusState] reports whether the bar has focus.
 */
@Composable
fun TvTopBar(
    current: String,
    onGo: (String) -> Unit,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier,
    extra: (String) -> Modifier = { Modifier },
    onFocusState: (Boolean) -> Unit = {}
) {
    val tabs = listOf("home" to "Home", "movies" to "Movies", "series" to "Series", "live" to "Live TV")
    val count = tabs.size + 2                       // + Search + Profile
    val frs = remember { List(count) { FocusRequester() } }
    var ix by remember { mutableIntStateOf(-1) }
    Row(
        modifier.fillMaxWidth()
            .menuRow({ ix }, { count }) { n -> ix = n; runCatching { frs[n].requestFocus() } }
            .onFocusChanged { onFocusState(it.hasFocus); if (!it.hasFocus) ix = -1 }
            .focusRestorer(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("TVSTREAMER", color = Pal.GoldText, fontSize = Ts.TITLE.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
        Spacer(Modifier.width(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { i, (k, label) ->
                NavTab(label, { onGo(k) }, Modifier.focusRequester(frs[i]).onFocusChanged { if (it.isFocused) ix = i }.then(extra(k)), selected = k == current)
            }
        }
        Spacer(Modifier.weight(1f))
        val si = tabs.size
        NavTab("Search", onSearch, Modifier.focusRequester(frs[si]).onFocusChanged { if (it.isFocused) ix = si }.then(extra("search")), icon = NavIcons.Search)
        Spacer(Modifier.width(10.dp))
        val pi = tabs.size + 1
        Box(Modifier.focusRequester(frs[pi]).onFocusChanged { if (it.hasFocus) ix = pi }.then(extra("profile"))) { AvatarButton(onProfile) }
    }
}
