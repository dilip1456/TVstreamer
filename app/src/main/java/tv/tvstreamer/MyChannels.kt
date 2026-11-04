package tv.tvstreamer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Keys of the channels picked in selection mode (null = not selecting). Cards show a tick for them. */
val LocalPicked = compositionLocalOf<Set<String>?> { null }

/** The channel as a card shows it: with a tick in selection mode. */
@Composable
fun pickShown(ch: Item): Item {
    val sel = LocalPicked.current ?: return ch
    return if (ch.key in sel) ch.copy(name = "✓ " + ch.name) else ch
}

/** Text entry dialog (new folder, rename): Done on the keyboard or the button saves; Back cancels. */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDone: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    PopupFrame(onClose) {
        Column(
            Modifier.widthIn(min = 300.dp, max = 480.dp).background(Pal.Surface.copy(alpha = 0.98f), RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Label(title, 20)
            Box(Modifier.focusRequester(fr)) { SettingField("Name", text) { text = it } }
            PillButton(confirm, { if (text.isNotBlank()) onDone(text) }, primary = true, size = 15)
        }
    }
}

