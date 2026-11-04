package tv.tvstreamer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Every popup (menus, dialogs, pickers, panels) goes through [PopupFrame]: one Back closes the topmost popup only and
 * focus returns to the item that opened it.
 *
 * Why Back used to need two presses: popups registered an Activity BackHandler, but screen-level BackHandlers that were
 * (re)registered later (Live / Home "back to the top menu", search) sat above them in the dispatcher, so the first Back
 * went to the screen behind the popup. Now the popup also takes Back at the key level (focus is inside it, so it sees
 * the key first) and screen handlers are disabled while [Popups.any] is true.
 */
object Popups {
    private val stack = PopupStack<FocusRequester>()
    private var count by mutableIntStateOf(0)
    @Volatile var lastFocused: FocusRequester? = null
        private set

    /** True while any popup is open (screen Back handlers check this). */
    val any: Boolean get() = count > 0

    fun open(): Long = stack.open(lastFocused).also { count = stack.size }
    fun isTop(id: Long) = stack.isTop(id)
    fun focused(fr: FocusRequester) { lastFocused = fr; stack.focused(fr) }
    fun close(id: Long) {
        val back = stack.close(id)
        count = stack.size
        if (back != null) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { back.requestFocus() }
        }, 60)
    }
}

/** Remembers this focusable as "what had focus" so a popup it opens can return focus to it. */
fun Modifier.popupOpener(): Modifier = composed {
    val fr = remember { FocusRequester() }
    this.focusRequester(fr).onFocusChanged { if (it.isFocused) Popups.focused(fr) }
}

/**
 * Popup container: dimmed backdrop (tap outside closes), Back / Escape closes this popup when it is the top one,
 * focus goes back to the opener when it closes.
 */
@Composable
fun PopupFrame(onClose: () -> Unit, alignment: Alignment = Alignment.Center, scrim: Color = Color(0xB3000000),
               content: @Composable () -> Unit) {
    val id = remember { Popups.open() }
    DisposableEffect(Unit) { onDispose { Popups.close(id) } }
    val close by rememberUpdatedState(onClose)
    androidx.activity.compose.BackHandler { if (Popups.isTop(id)) close() }
    // Fade + slight slide in (~180 ms), cheap: one graphicsLayer, no blur
    val shown = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, Motion.spec()) }
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = shown.value }
            .background(scrim)
            .pointerInput(Unit) { detectTapGestures { if (Popups.isTop(id)) close() } }
            .onPreviewKeyEvent { e ->
                if (e.key != Key.Back && e.key != Key.Escape) return@onPreviewKeyEvent false
                if (!Popups.isTop(id)) return@onPreviewKeyEvent false   // a popup on top of this one takes it
                if (e.type == KeyEventType.KeyUp) close()
                true
            },
        contentAlignment = alignment
    ) {
        Box(Modifier.graphicsLayer { translationY = (1f - shown.value) * 24f }.pointerInput(Unit) { detectTapGestures { } }) { content() }
    }
}
