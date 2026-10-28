package tv.tvstreamer

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * True on real TV devices (Fire TV / Android TV): TV UI mode or the leanback / Fire TV feature.
 * Screen size is not used, so tablets get the phone layout (bottom bar).
 */
fun isLeanback(ctx: Context): Boolean {
    val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    val pm = ctx.packageManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature("amazon.hardware.fire_tv")
}

/** App-wide UI flags shared between screens. */
object AppUi {
    /** The account menu (avatar) is open. */
    var accountMenu by mutableStateOf(false)
    /** Phone layout: floating bottom bar instead of the top tabs. */
    var phoneNav by mutableStateOf(false)
}

/** Material icon paths (Apache 2.0) for the bottom bar. */
object NavIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.White)).build()
    val Home = icon("home", "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z")
    val Movie = icon("movie", "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z")
    val Series = icon("series", "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6zM20,2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM12,14.5v-9l6,4.5 -6,4.5z")
    val Live = icon("live", "M21,6h-7.59l3.29,-3.29L16,2l-4,4 -4,-4 -0.71,0.71L10.59,6H3c-1.1,0 -2,0.89 -2,2v12c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V8c0,-1.11 -0.9,-2 -2,-2zM21,20H3V8h18v12zM9,10v8l7,-4z")
    val Person = icon("person", "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z")
}

/** Google profile photo (circular), else gold initials, else a person icon. */
@Composable
fun Avatar(size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val initials = (Cloud.name.ifEmpty { Cloud.email }).split(' ', '.', '@').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
    Box(modifier.size(size).clip(CircleShape).background(if (Cloud.signedIn) Pal.Gold else Pal.Selected), contentAlignment = Alignment.Center) {
        if (Cloud.signedIn && initials.isNotEmpty()) Text(initials, color = Color.Black, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold)
        else Icon(NavIcons.Person, null, tint = Pal.Text, modifier = Modifier.size(size * 0.62f))
        if (Cloud.signedIn && Cloud.photo.isNotEmpty())
            coil.compose.AsyncImage(Cloud.photo, "Profile photo", Images.get(ctx), Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
    }
}

enum class NavTab(val label: String, val icon: ImageVector) {
    HOME("Home", NavIcons.Home), MOVIES("Movies", NavIcons.Movie), SERIES("Series", NavIcons.Series),
    LIVE("Live TV", NavIcons.Live), PROFILE("Profile", NavIcons.Person)
}

/** Which bottom-bar tab a screen belongs to (follows the back chain for detail / grid screens). */
fun tabOf(s: Screen): NavTab? = when (s) {
    Screen.Home, Screen.Search -> NavTab.HOME
    Screen.MoviesTab -> NavTab.MOVIES
    Screen.SeriesTab -> NavTab.SERIES
    Screen.Live -> NavTab.LIVE
    Screen.Profile, Screen.Settings, Screen.Account, Screen.Categories, Screen.Remote -> NavTab.PROFILE
    is Screen.Languages -> tabOf(s.back)
    is Screen.Detail -> tabOf(s.back)
    is Screen.Grid -> tabOf(s.back)
    is Screen.Pool -> tabOf(s.back)
    is Screen.Play -> tabOf(s.back)
}

/** Floating bottom navigation for phones: rounded, elevated, black with gold selection. */
@Composable
fun BottomBar(current: NavTab?, onTab: (NavTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth().height(64.dp)
            .shadow(12.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp))
            .background(Color(0xF20A0A0A)).border(1.dp, Pal.Gold.copy(alpha = 0.25f), RoundedCornerShape(24.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavTab.entries.forEach { t ->
            val sel = t == current
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab) { onTab(t) }
                    .semantics { selected = sel; contentDescription = t.label },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.clip(RoundedCornerShape(14.dp)).background(if (sel) Pal.Gold.copy(alpha = 0.18f) else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
                    if (t == NavTab.PROFILE && Cloud.signedIn) Avatar(24.dp, if (sel) Modifier.border(1.5.dp, Pal.Gold, CircleShape) else Modifier)
                    else Icon(t.icon, null, tint = if (sel) Pal.Gold else Color(0xFFB8BCC8), modifier = Modifier.size(24.dp))
                }
                Text(t.label, color = if (sel) Pal.Gold else Color(0xFFB8BCC8), fontSize = 11.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** Avatar button for the TV top bar (D-pad focusable). */
@Composable
fun AvatarButton(onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Box(
        Modifier.onFocusChanged { f = it.isFocused }.clip(CircleShape)
            .border(if (f) 3.dp else 0.dp, if (f) Pal.Gold else Color.Transparent, CircleShape)
            .clickable(onClick = onClick).padding(3.dp)
            .semantics { contentDescription = "Account menu" }
    ) { Avatar(34.dp) }
}

/** Account menu: header, theme, Settings, Preferences, Sync now, Check for update, Sign in / Sign out. */
@Composable
fun AccountMenu(store: Store, go: (Screen) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    androidx.activity.compose.BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { onClose() },
        contentAlignment = if (AppUi.phoneNav) Alignment.BottomCenter else Alignment.TopEnd) {
        Column(
            Modifier.then(if (AppUi.phoneNav) Modifier.fillMaxWidth().navigationBarsPadding() else Modifier.padding(24.dp).width(420.dp))
                .clip(if (AppUi.phoneNav) RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp) else RoundedCornerShape(16.dp))
                .background(Pal.Surface).clickable(enabled = false) {}.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Avatar(52.dp)
                Column(Modifier.weight(1f)) {
                    Label(if (Cloud.signedIn) Cloud.name.ifEmpty { "Google account" } else "Not signed in", 18, Pal.Text)
                    Label(if (Cloud.signedIn) Cloud.email else "Sign in to sync your TV and phone", 13, Pal.Dim)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row1("Theme: ${Theme.label}  (tap to change)", {
                Theme.mode = Theme.next(Theme.mode); store.theme = Theme.mode
            }, Modifier.focusRequester(first))
            Row1("Settings", { onClose(); go(Screen.Settings) })
            Row1("Preferences (languages, remote, playback, sync)", { onClose(); go(Screen.Profile) })
            if (Cloud.signedIn) Row1(if (Cloud.busy) "Syncing..." else "Sync now" + syncAgo(), { Cloud.syncNow() })
            Row1("Check for update", {
                msg = "Checking for updates..."
                scope.launch {
                    val code = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode } catch (e: Exception) { 1 }
                    msg = Updater.checkAndUpdate(ctx, code)
                }
            })
            if (Cloud.signedIn) Row1("Sign out", { Cloud.signOut(); msg = "Signed out. Data on this device is kept." })
            else Row1("Sign in with Google", { onClose(); go(Screen.Account) })
            if (msg.isNotEmpty()) Label(msg, 14, Pal.Warn, maxLines = 3)
        }
    }
}

fun syncAgo(): String {
    val t = Cloud.lastSync
    if (t <= 0) return ""
    val m = (System.currentTimeMillis() - t) / 60000
    return "  ·  last " + when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ago"; else -> "${m / 1440} d ago" }
}

/** Profile / Preferences: account, languages, remote buttons, playback, sync, update, about. */
@Composable
fun ProfileScreen(repo: Repo, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    val ver = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" } }
    var aspect by remember { mutableStateOf(repo.store.aspect) }
    val firstFr = remember { FocusRequester() }
    val tv = rememberIsTv()
    LaunchedEffect(Unit) { if (tv) { delay(150); runCatching { firstFr.requestFocus() } } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Avatar(64.dp)
                Column(Modifier.weight(1f)) {
                    Label(if (Cloud.signedIn) Cloud.name.ifEmpty { "Google account" } else "Profile", 24, Pal.Text)
                    Label(if (Cloud.signedIn) Cloud.email else "Not signed in", 14, Pal.Dim)
                    if (Cloud.signedIn) Label((if (Cloud.busy) "Syncing..." else Cloud.status.ifEmpty { "Signed in" }) + syncAgo(), 13, Pal.Dim)
                    if (Cloud.error.isNotEmpty()) Label(Cloud.error, 13, Pal.Warn, maxLines = 3)
                }
            }
        }
        if (Cloud.signedIn) item {
            ButtonFlow {
                PillButton("Sync now", { Cloud.syncNow() }, Modifier.focusRequester(firstFr), primary = true)
                PillButton("Account & sync", { go(Screen.Account) })
                PillButton("Sign out", { Cloud.signOut() })
            }
        } else item { PillButton("Sign in with Google (sync TV and phone)", { go(Screen.Account) }, Modifier.focusRequester(firstFr), primary = true) }
        item { Label("Preferences", 18, Pal.Text, Modifier.padding(top = 8.dp)) }
        item { Row1("Theme: ${Theme.label}", { Theme.mode = Theme.next(Theme.mode); repo.store.theme = Theme.mode }) }
        item { Row1("Languages for Movies", { go(Screen.Languages("movies", Screen.Profile)) }) }
        item { Row1("Languages for Series", { go(Screen.Languages("series", Screen.Profile)) }) }
        item { Row1("Categories (order, hide, show)", { go(Screen.Categories) }) }
        item { Row1("Remote buttons", { go(Screen.Remote) }) }
        item {
            Row1("Video picture: " + when (aspect) { "fill" -> "Fill"; "zoom" -> "Zoom"; else -> "Fit" }, {
                aspect = when (aspect) { "fit" -> "fill"; "fill" -> "zoom"; else -> "fit" }; repo.store.aspect = aspect
            })
        }
        item { Row1("Movie & series info (TMDB / OMDb keys)", { go(Screen.Settings) }) }
        item { Row1("Settings (portal, MAC, more)", { go(Screen.Settings) }) }
        item {
            PillButton("Check for update", {
                msg = "Checking for updates..."
                scope.launch {
                    val code = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode } catch (e: Exception) { 1 }
                    msg = Updater.checkAndUpdate(ctx, code)
                }
            })
        }
        if (msg.isNotEmpty()) item { Label(msg, 14, Pal.Warn, maxLines = 4) }
        item { Label("About", 18, Pal.Text, Modifier.padding(top = 8.dp)) }
        item { Label("TVstreamer v$ver · Stalker portal player for Fire TV and Android phones", 14, Pal.Dim, maxLines = 2) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
