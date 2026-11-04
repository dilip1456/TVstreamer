package tv.tvstreamer

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * TV: a text field that never opens the on-screen keyboard just because the D-pad moved onto it. Focus lands on
 * an outer frame (gold outline); OK / click starts editing: only then the field itself takes focus and the keyboard
 * opens. Moving off the field ends editing. Phones (and [enabled] = false) get the plain field.
 * [modifier] (size, focus requesters) goes on the outer frame; [field] gets the modifier for the TextField itself.
 */
@Composable
fun KeyboardGate(modifier: Modifier = Modifier, enabled: Boolean = rememberIsTv(), shape: Shape = RoundedCornerShape(8.dp),
                 field: @Composable (Modifier) -> Unit) {
    if (!enabled) { field(modifier); return }
    val kb = LocalSoftwareKeyboardController.current
    val inner = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var outerFocused by remember { mutableStateOf(false) }
    var had by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) { delay(30); runCatching { inner.requestFocus() }; delay(60); kb?.show() }
    }
    Box(
        modifier
            .onFocusChanged { outerFocused = it.isFocused }
            .then(if (outerFocused) Modifier.border(2.dp, Pal.Gold, shape) else Modifier)
            .clickable { editing = true }
    ) {
        field(
            Modifier.focusRequester(inner)
                .focusProperties { canFocus = editing }
                .onFocusChanged {
                    if (it.isFocused) had = true
                    else if (had) { had = false; editing = false; kb?.hide() }
                }
        )
    }
}
