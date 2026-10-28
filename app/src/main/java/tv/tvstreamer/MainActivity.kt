package tv.tvstreamer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
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
    class Detail(val item: Item, val back: Screen) : Screen
    class Grid(val cat: Cat, val back: Screen) : Screen
    class Play(val list: List<Item>, val index: Int, val back: Screen) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = Repo(applicationContext)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(primary = Pal.Accent, background = Pal.Bg, surface = Pal.Surface, onPrimary = Color.White)
            ) { App(repo) }
        }
    }
}

@Composable
fun App(repo: Repo) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var tick by remember { mutableIntStateOf(0) }
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
            else -> Screen.Home
        }
        tick++
    }
    BackHandler(screen != Screen.Home) { back() }

    val play: (List<Item>, Int) -> Unit = { l, i -> if (l.isNotEmpty()) screen = Screen.Play(l, i, screen) }
    val open: (Item) -> Unit = { item ->
        if (item.kind == "vod" && !item.isEpisode()) screen = Screen.Detail(item, screen)
        else play(listOf(item), 0)
    }
    // Grid screens remember where they were opened from
    val go: (Screen) -> Unit = { to ->
        val cur = screen
        screen = if (to is Screen.Grid && cur !is Screen.Grid) Screen.Grid(to.cat, cur) else to
    }

    Surface(Modifier.fillMaxSize().background(Pal.Bg), color = Pal.Bg) {
        when (val s = screen) {
            Screen.Home -> Catalog(repo, "home", go, open, play, tick)
            Screen.MoviesTab -> Catalog(repo, "movies", go, open, play, tick)
            Screen.SeriesTab -> Catalog(repo, "series", go, open, play, tick)
            Screen.Live -> Live(repo, play)
            Screen.Search -> Search(repo, play, open)
            Screen.Settings -> SettingsScreen(repo, go)
            Screen.Categories -> CategoriesScreen(repo, go)
            is Screen.Detail -> DetailScreen(repo, s.item, play, { back() }, tick)
            is Screen.Grid -> GridScreen(repo, s.cat, open, play)
            is Screen.Play -> PlayerScreen(repo, s.list, s.index) { back() }
        }
    }
}
