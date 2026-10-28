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
    object Live : Screen
    object Movies : Screen
    object Search : Screen
    object Settings : Screen
    class Play(val list: List<Item>, val index: Int, val back: Screen) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = Repo(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { App(repo) }
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

    BackHandler(screen != Screen.Home) {
        val s = screen
        screen = if (s is Screen.Play) s.back else Screen.Home
        tick++
    }

    val play: (List<Item>, Int) -> Unit = { l, i -> screen = Screen.Play(l, i, screen) }

    Surface(Modifier.fillMaxSize().background(Color(0xFF101014)), color = Color(0xFF101014)) {
        when (val s = screen) {
            Screen.Home -> Home(repo, { screen = it }, play, tick)
            Screen.Live -> Live(repo, play)
            Screen.Movies -> Movies(repo, play)
            Screen.Search -> Search(repo, play)
            Screen.Settings -> SettingsScreen(repo)
            is Screen.Play -> PlayerScreen(repo, s.list, s.index) { screen = s.back; tick++ }
        }
    }
}
