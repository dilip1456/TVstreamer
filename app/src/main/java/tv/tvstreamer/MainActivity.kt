package tv.tvstreamer

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

sealed interface Screen {
    object Home : Screen
    object MoviesTab : Screen
    object SeriesTab : Screen
    object Live : Screen
    object Search : Screen
    object Settings : Screen
    object Categories : Screen
    object Remote : Screen
    object Account : Screen
    object Profile : Screen
    /** Language checklist for a tab ("movies" / "series"). */
    class Languages(val tab: String, val back: Screen) : Screen
    /** focusEp: episode key to open on (its season, focused) — from Continue watching. */
    class Detail(val item: Item, val back: Screen, val focusEp: String? = null) : Screen
    class Grid(val cat: Cat, val back: Screen) : Screen
    /** See all for a language / genre row. */
    class Pool(val tab: String, val lang: String, val genre: String, val title: String, val sort: String, val back: Screen) : Screen
    class Play(val list: List<Item>, val index: Int, val back: Screen) : Screen
}

class MainActivity : ComponentActivity() {
    private lateinit var repo: Repo
    private var replaying = false
    private var downCode = -1
    private var downAt = 0L
    private var longFired = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repo(applicationContext)
        Keys.init(repo.store)
        Cloud.init(applicationContext, repo)
        Theme.mode = repo.store.theme
        AppUi.phoneNav = !isLeanback(this)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MaterialTheme(
                colorScheme = if (Theme.dark) darkColorScheme(primary = Pal.Accent, background = Pal.Bg, surface = Pal.Surface, onPrimary = Color.White)
                    else lightColorScheme(primary = Pal.Accent, background = Pal.Bg, surface = Pal.Surface, onPrimary = Color.White)
            ) {
                Theme.systemDark = androidx.compose.foundation.isSystemInDarkTheme()
                App(repo)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Voice / search intents (Fire TV Alexa or Google Assistant "search for X in TVstreamer", the remote's search):
     * a query lands in the app's search bar; an assist request without one opens search and starts listening.
     */
    private fun handleIntent(i: android.content.Intent?) {
        val a = i?.action ?: return
        val searchy = a == android.content.Intent.ACTION_SEARCH || a == "com.google.android.gms.actions.SEARCH_ACTION" ||
            a == android.content.Intent.ACTION_ASSIST || a == android.content.Intent.ACTION_VOICE_COMMAND
        if (!searchy) return
        val q = (i.getStringExtra(android.app.SearchManager.QUERY) ?: i.getStringExtra("query")
            ?: i.getStringExtra(android.app.SearchManager.USER_QUERY))?.trim()
        if (!q.isNullOrEmpty()) VoiceBus.query = q
        else VoiceBus.request++
    }

    override fun onStart() { super.onStart(); Cloud.syncSoon() }
    override fun onStop() { Cloud.flush(); super.onStop() }

    /** Re-send a key to the normal (Compose) handling, as a fresh down + up. */
    private fun replay(up: KeyEvent) {
        replaying = true
        try {
            super.dispatchKeyEvent(KeyEvent.changeAction(up, KeyEvent.ACTION_DOWN))
            super.dispatchKeyEvent(up)
        } finally { replaying = false }
    }

    /**
     * Central key handler: learning mode, then custom short / long press bindings (Keys), else normal handling.
     * Keys with a long-press action wait for release (or [Keys.LONG_MS]) to tell short from long.
     */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (replaying) return super.dispatchKeyEvent(e)
        val code = e.keyCode
        Keys.learn?.let { learn ->
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) learn(if (code == KeyEvent.KEYCODE_BACK) -1 else code)
            return true
        }
        // Remote mic / search key (Fire TV Alexa remote, Android TV remotes): open search and listen.
        // KEYCODE_SEARCH only while it keeps its default mapping, so a custom assignment still wins.
        val voiceKey = code == KeyEvent.KEYCODE_VOICE_ASSIST || code == KeyEvent.KEYCODE_ASSIST ||
            (code == KeyEvent.KEYCODE_SEARCH && Keys.shortOf(code) == Act.SEARCH && Keys.longOf(code) == Act.DEFAULT)
        if (voiceKey) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) VoiceBus.request++
            return true
        }
        if (code in Keys.reserved) return super.dispatchKeyEvent(e)
        val s = Keys.shortOf(code)
        val l = Keys.longOf(code)
        if (s == Act.DEFAULT && l == Act.DEFAULT) return super.dispatchKeyEvent(e)
        if (l != Act.DEFAULT) {
            when (e.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (e.repeatCount == 0) { downCode = code; downAt = e.eventTime; longFired = false }
                    else if (code == downCode && !longFired && (e.isLongPress || e.eventTime - downAt >= Keys.LONG_MS)) {
                        longFired = true
                        Keys.run(l)
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    val same = code == downCode
                    val wasLong = longFired && same
                    downCode = -1; longFired = false
                    if (wasLong) return true
                    // Remotes that send no key repeats: decide by how long the button was held
                    if (same && e.eventTime - downAt >= Keys.LONG_MS) { Keys.run(l); return true }
                    if (s == Act.DEFAULT || !Keys.run(s)) replay(e)
                    return true
                }
            }
            return true
        }
        // Only a short-press action: act on the first down
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount == 0 && !Keys.run(s)) return super.dispatchKeyEvent(e)
            return true
        }
        return true
    }
}

fun toast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

@Composable
fun App(repo: Repo) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var tick by remember { mutableIntStateOf(0) }
    val search = remember { SearchState() }
    // Detail screen opened from a search result: Back from it brings the search overlay back
    var fromSearch by remember { mutableStateOf<Screen?>(null) }
    LaunchedEffect(Unit) {
        repo.load()
        val code = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode } catch (e: Exception) { 1 }
        val msg = Updater.checkAndUpdate(ctx, code, interactive = false)
        if (msg.isNotEmpty() && !msg.startsWith("Up to date")) repo.status = msg
    }

    fun back() {
        val s = screen
        screen = when (s) {
            is Screen.Play -> s.back
            is Screen.Detail -> s.back
            is Screen.Grid -> s.back
            is Screen.Pool -> s.back
            is Screen.Languages -> s.back
            Screen.Remote -> if (AppUi.phoneNav) Screen.Profile else Screen.Settings
            Screen.Account -> if (AppUi.phoneNav) Screen.Profile else Screen.Settings
            Screen.Categories -> if (AppUi.phoneNav) Screen.Profile else Screen.Home
            else -> Screen.Home
        }
        if (s === fromSearch) { search.open = true; fromSearch = null }
        tick++
    }
    BackHandler(screen != Screen.Home) { back() }
    LaunchedEffect(screen) { Keys.focused = null }

    val play: (List<Item>, Int) -> Unit = { l, i -> if (l.isNotEmpty()) screen = Screen.Play(l, i, screen) }
    val open: (Item) -> Unit = { item ->
        if (item.kind == "vod" && !item.isEpisode()) screen = Screen.Detail(item, screen)
        else play(listOf(item), 0)
    }
    // Grid / list screens remember where they were opened from
    val go: (Screen) -> Unit = go@{ to ->
        if (to == Screen.Search) { search.open = true; return@go }
        val cur = screen
        screen = when {
            to is Screen.Grid && cur !is Screen.Grid -> Screen.Grid(to.cat, cur)
            to is Screen.Pool -> Screen.Pool(to.tab, to.lang, to.genre, to.title, to.sort, cur)
            to is Screen.Languages -> Screen.Languages(to.tab, cur)
            to is Screen.Detail -> Screen.Detail(to.item, cur, to.focusEp)
            else -> to
        }
    }

    // Remote mic key / assist intent: open search and start voice capture (not over the player)
    LaunchedEffect(VoiceBus.request) {
        if (VoiceBus.request > VoiceBus.handled) {
            VoiceBus.handled = VoiceBus.request
            if (screen !is Screen.Play && screen != Screen.Remote) { search.open = true; search.voiceReq++ }
        }
    }
    // Search intent with a query (e.g. "Alexa, search for Inception" / android.intent.action.SEARCH)
    LaunchedEffect(VoiceBus.query) {
        val q = VoiceBus.query ?: return@LaunchedEffect
        VoiceBus.query = null
        if (screen is Screen.Play || screen == Screen.Remote) screen = Screen.Home
        search.aiItems = emptyList(); search.aiMsg = ""
        search.query = q; search.open = true
    }

    // Remote button actions outside the player
    DisposableEffect(Unit) {
        Keys.app = { a ->
            when (a) {
                Act.HOME -> { screen = Screen.Home; true }
                Act.MOVIES -> { screen = Screen.MoviesTab; true }
                Act.SERIES -> { screen = Screen.SeriesTab; true }
                Act.LIVE -> { screen = Screen.Live; true }
                Act.SEARCH -> { if (screen !is Screen.Play) search.open = !search.open; true }
                Act.SETTINGS -> { screen = Screen.Settings; true }
                Act.BACK -> { if (screen != Screen.Home) back(); true }
                Act.FAV -> {
                    val f = Keys.focused
                    if (f != null && !f.isEpisode()) {
                        repo.toggleFav(f)
                        toast(ctx, (if (repo.isFav(f)) "Added to My List: " else "Removed from My List: ") + Names.title(f.name))
                        tick++
                    } else toast(ctx, "Focus a title or channel first")
                    true
                }
                else -> false
            }
        }
        onDispose { Keys.app = null }
    }

    val phone = AppUi.phoneNav
    val cur = screen
    val showBar = phone && cur !is Screen.Play && cur != Screen.Remote
    val barSpace = if (showBar) 84.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp
    Surface(Modifier.fillMaxSize().background(Pal.Bg), color = Pal.Bg) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(bottom = barSpace)) {
                when (val s = screen) {
                    Screen.Home -> Catalog(repo, "home", go, open, play, tick)
                    Screen.MoviesTab -> Catalog(repo, "movies", go, open, play, tick)
                    Screen.SeriesTab -> Catalog(repo, "series", go, open, play, tick)
                    Screen.Live -> Live(repo, play)
                    Screen.Search -> Catalog(repo, "home", go, open, play, tick)
                    Screen.Settings -> SettingsScreen(repo, go)
                    Screen.Categories -> CategoriesScreen(repo, go)
                    Screen.Remote -> RemoteScreen { back() }
                    Screen.Account -> AccountScreen(repo) { screen = Screen.Home; tick++ }
                    Screen.Profile -> ProfileScreen(repo, go)
                    is Screen.Languages -> LanguagesScreen(repo, s.tab) { back() }
                    is Screen.Detail -> DetailScreen(repo, s.item, play, { back() }, tick, s.focusEp, go)
                    is Screen.Grid -> GridScreen(repo, s.cat, open, play)
                    is Screen.Pool -> PoolScreen(repo, s.tab, s.lang, s.genre, s.title, s.sort, open)
                    is Screen.Play -> PlayerScreen(repo, s.list, s.index) { back() }
                }
                // Floating search: on every screen except the full-screen player and the remote-learning screen
                if (cur !is Screen.Play && cur != Screen.Remote) {
                    if (search.open) {
                        BackHandler { search.open = false }
                        SearchOverlay(repo, search, { item ->
                            search.open = false
                            open(item)
                            fromSearch = screen.takeIf { it is Screen.Detail }
                        }, { l, i -> search.open = false; play(l, i) })
                    } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                        SearchFab(Modifier.padding(if (rememberIsTv() && !phone) 28.dp else 18.dp)) { search.open = true }
                    }
                }
            }
            if (showBar) BottomBar(tabOf(cur), { t ->
                search.open = false
                when (t) {
                    NavTab.HOME -> screen = Screen.Home
                    NavTab.MOVIES -> screen = Screen.MoviesTab
                    NavTab.SERIES -> screen = Screen.SeriesTab
                    NavTab.LIVE -> screen = Screen.Live
                    NavTab.PROFILE -> AppUi.accountMenu = true
                }
                tick++
            }, Modifier.align(Alignment.BottomCenter))
            if (AppUi.accountMenu && cur !is Screen.Play) AccountMenu(repo.store, go) { AppUi.accountMenu = false }
        }
    }
}
