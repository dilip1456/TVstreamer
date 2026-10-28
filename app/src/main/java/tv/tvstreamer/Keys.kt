package tv.tvstreamer

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Things a remote button can do. DEFAULT = whatever the app normally does with that key. */
enum class Act(val label: String) {
    DEFAULT("App default"),
    NONE("Do nothing"),
    MENU("Open options menu"),
    FAV("Favorite / Unfavorite"),
    PLAY_PAUSE("Play / Pause"),
    SEEK_BACK("Seek back 10 s"),
    SEEK_FWD("Seek forward 10 s"),
    NEXT("Next (channel / episode)"),
    PREV("Previous (channel / episode)"),
    CH_UP("Channel up"),
    CH_DOWN("Channel down"),
    LAST_CH("Back to previous channel"),
    CONTROLS("Show player controls"),
    ASPECT("Toggle aspect ratio (Fit / Fill / Zoom)"),
    SUBS("Subtitles menu"),
    AUDIO("Audio track menu"),
    HOME("Open Home"),
    MOVIES("Open Movies"),
    SERIES("Open Series"),
    LIVE("Open Live TV"),
    SEARCH("Open Search"),
    SETTINGS("Open Settings"),
    BACK("Go back");

    companion object { fun of(s: String?) = values().firstOrNull { it.name == s } }
}

/**
 * Central remote-key mapping. MainActivity.dispatchKeyEvent sends every key here first; keys with a
 * custom short or long press are handled here, everything else goes to the screens as usual.
 * Short and long press are separate per key. Saved in Store ("keymap").
 */
object Keys {
    const val LONG_MS = 500L

    /** Navigation keys stay as they are (remapping them would make the app unusable). */
    val reserved = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST
    )

    /** Defaults: hold Menu = favorite. Extra buttons some remotes send while the app is open. */
    val defaultShort: Map<Int, Act> = mapOf(
        KeyEvent.KEYCODE_CAPTIONS to Act.SUBS,
        KeyEvent.KEYCODE_GUIDE to Act.LIVE,
        KeyEvent.KEYCODE_LAST_CHANNEL to Act.LAST_CH,
        KeyEvent.KEYCODE_PROG_RED to Act.FAV,
        KeyEvent.KEYCODE_BOOKMARK to Act.FAV,
        KeyEvent.KEYCODE_TV to Act.LIVE,
        KeyEvent.KEYCODE_SETTINGS to Act.SETTINGS
    )
    val defaultLong: Map<Int, Act> = mapOf(KeyEvent.KEYCODE_MENU to Act.FAV)

    var short by mutableStateOf(defaultShort)
        private set
    var long by mutableStateOf(defaultLong)
        private set
    private var store: Store? = null

    // Runtime hooks set by screens
    /** Player: handles an action; false = not for the player. */
    var player: ((Act) -> Boolean)? = null
    /** App: navigation, favorite of the focused card. */
    var app: ((Act) -> Boolean)? = null
    /** Card / row with focus (for Favorite). */
    var focused: Item? = null
    /** "Press a button" mode in Settings > Remote buttons. */
    var learn: ((Int) -> Unit)? = null

    fun init(s: Store) {
        store = s
        val raw = s.keymap
        if (raw.isEmpty()) return
        runCatching {
            val o = JSONObject(raw)
            fun read(k: String) = o.optJSONObject(k)?.let { m -> m.keys().asSequence().mapNotNull { c -> c.toIntOrNull()?.let { it to Act.of(m.optString(c)) } }
                .filter { it.second != null }.associate { it.first to it.second!! } } ?: emptyMap()
            short = read("s"); long = read("l")
        }
    }

    private fun save() {
        val o = JSONObject()
            .put("s", JSONObject(short.mapKeys { it.key.toString() }.mapValues { it.value.name }))
            .put("l", JSONObject(long.mapKeys { it.key.toString() }.mapValues { it.value.name }))
        store?.keymap = o.toString()
    }

    fun set(code: Int, isLong: Boolean, a: Act) {
        if (isLong) long = if (a == Act.DEFAULT) long - code else long + (code to a)
        else short = if (a == Act.DEFAULT) short - code else short + (code to a)
        save()
    }

    fun remove(code: Int) { short = short - code; long = long - code; save() }
    fun reset() { short = defaultShort; long = defaultLong; save() }

    fun shortOf(code: Int) = short[code] ?: Act.DEFAULT
    fun longOf(code: Int) = long[code] ?: Act.DEFAULT

    fun name(code: Int): String = when (code) {
        KeyEvent.KEYCODE_MENU -> "Menu (☰)"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play / Pause"
        KeyEvent.KEYCODE_MEDIA_REWIND -> "Rewind"
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "Fast forward"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() }
    } + "  (code $code)"

    /** Run an action: the player first (when playing), then the app. */
    fun run(a: Act): Boolean = when (a) {
        Act.DEFAULT -> false
        Act.NONE -> true
        else -> player?.invoke(a) == true || app?.invoke(a) == true
    }
}

/** Settings > Remote buttons: press-to-learn, separate short / long press, reset, Fire TV note. */
@Composable
fun RemoteScreen(onBack: () -> Unit) {
    val isTv = rememberIsTv()
    var learning by remember { mutableStateOf(false) }
    var learnMsg by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Int?>(null) }          // key whose options are open
    var choose by remember { mutableStateOf<Pair<Int, Boolean>?>(null) } // (key, long) action picker
    val firstFr = remember { FocusRequester() }

    DisposableEffect(learning) {
        if (learning) Keys.learn = { code ->
            learning = false
            when {
                code < 0 -> learnMsg = "Cancelled"
                code in Keys.reserved -> learnMsg = "${Keys.name(code)} is used for moving around the app (or by the system) and can't be changed."
                else -> { learnMsg = "Got ${Keys.name(code)}"; picked = code }
            }
        }
        onDispose { Keys.learn = null }
    }
    LaunchedEffect(learning) {
        if (learning) { delay(10_000); if (learning) { learning = false; learnMsg = "No button press reached the app. That button is probably handled by the system (see the note below)." } }
    }
    LaunchedEffect(Unit) { delay(120); runCatching { firstFr.requestFocus() } }

    val codes = (Keys.short.keys + Keys.long.keys).sorted()
    val hPad = if (isTv) 48.dp else 16.dp
    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = hPad, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Remote buttons", color = Color.White, fontSize = if (isTv) 28.sp else 22.sp, fontWeight = FontWeight.Bold)
                    Text("Give a remote button its own action, separately for a short press and for holding it. " +
                        "Holding Menu adds or removes the focused / playing title from favorites; a short Menu press still opens the options menu.",
                        color = Pal.Dim, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(if (learning) "Press a button on the remote…" else "＋  Learn a button", { learnMsg = ""; learning = true }, Modifier.focusRequester(firstFr), primary = true)
                        PillButton("Reset to defaults", { Keys.reset(); learnMsg = "Defaults restored" })
                        PillButton("Back", onBack)
                    }
                    if (learnMsg.isNotEmpty()) Text(learnMsg, color = Pal.Warn, fontSize = 14.sp)
                }
            }
            items(codes, key = { it }) { c ->
                Row(Modifier.fillMaxWidth().focusCard({ picked = c }, scale = 1.02f).background(Color(0x22FFFFFF)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(Keys.name(c), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("Press: ${Keys.shortOf(c).label}    Hold: ${Keys.longOf(c).label}", color = Pal.Dim, fontSize = 13.sp)
                }
            }
            item {
                Column(Modifier.padding(top = 16.dp).background(Color(0xFF1A1A22), RoundedCornerShape(8.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("About the app buttons (Netflix, Prime Video, Hulu …)", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Those shortcut buttons are handled by Fire TV itself, so apps usually never see them and they can't be learned here. " +
                        "If you press one while TVStreamer is open and nothing shows up above, that's why. " +
                        "To make a remote shortcut button open TVStreamer, use Fire TV's own setting on supported remotes: " +
                        "Settings > Controllers & Bluetooth Devices > Amazon Fire TV Remotes > (your remote) > Remote button shortcuts / customize. " +
                        "Availability varies by remote and Fire TV software version.",
                        color = Pal.Dim, fontSize = 13.sp)
                    Text("Buttons that do reach the app (for example Menu, Play/Pause, Rewind/Fast forward, and on some remotes Guide, Captions, " +
                        "the coloured buttons or Channel up/down) can be learned.", color = Pal.Dim, fontSize = 13.sp)
                }
            }
        }

        picked?.let { c ->
            ChoiceDialog(Keys.name(c), focusKey = "k$c", opts = listOf(
                "Short press: ${Keys.shortOf(c).label}" to { choose = c to false; picked = null },
                "Hold: ${Keys.longOf(c).label}" to { choose = c to true; picked = null },
                "Remove custom actions" to { Keys.remove(c); picked = null }
            ), onClose = { picked = null })
        }
        choose?.let { (c, isLong) ->
            ChoiceDialog((if (isLong) "Hold " else "Press ") + Keys.name(c), focusKey = "a$c$isLong",
                opts = Act.values().map { a -> ((if ((if (isLong) Keys.longOf(c) else Keys.shortOf(c)) == a) "✓  " else "") + a.label) to { Keys.set(c, isLong, a); choose = null } },
                onClose = { choose = null })
        }
    }
}
