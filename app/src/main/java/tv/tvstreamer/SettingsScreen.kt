package tv.tvstreamer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One Settings screen for the whole app (1.91): every option lives here exactly once.
 * TV / tablet: a short section list on the left, the section's options on the right. Phone: sections, then options.
 * Option names are plain title case with no brackets; [SettingsLabels] holds them so a test can check that.
 */
object SettingsNav {
    enum class Sec(val title: String) {
        ACCOUNT("Account"), PORTAL("Portal"), CATALOG("Catalog"), LANGUAGES("Languages"), PLAYBACK("Playback"),
        KEYS("API Keys"), HIDDEN("Hidden Items"), GENERAL("General"), TROUBLE("Troubleshooting")
    }
    val ALL = Sec.entries.toList()

    /** Back: from a section's rows up to the section list (TV) / the list of sections (phone); from there, leave. */
    fun back(inDetail: Boolean): Boolean = inDetail   // true = handled here (go up one level)
}

/** Every option name shown in Settings, by section. Plain title case, no brackets. */
object SettingsLabels {
    const val GOOGLE_ACCOUNT = "Google Account"
    const val SYNC_NOW = "Sync Now"
    const val SIGN_IN = "Sign In with Google"
    const val SIGN_OUT = "Sign Out"
    const val PORTAL_URL = "Portal URL"
    const val MAC = "MAC Address"
    const val SERIAL = "Serial Number"
    const val LOCK_PASSWORD = "Lock Password"
    const val SAVE_RELOAD = "Save and Reload"
    const val CATALOG_SELECTOR = "Catalog Selector"
    const val SENSITIVE = "Sensitive Content Only"
    const val REFRESH_INTERVAL = "Library Refresh Interval"
    const val REFRESH_LIBRARY = "Refresh Library"
    const val STOP_LOADING = "Stop Loading"
    const val RESUME_LOADING = "Resume Loading"
    const val CONFIRM_REFRESH = "Check for New Titles Now"
    const val CLEAR_HISTORY = "Clear Watch History"
    const val CLEAR_DAY = "Last 24 Hours"
    const val CLEAR_ALL = "All History"
    const val CANCEL = "Cancel"
    const val LANG_MOVIES = "Movie Languages"
    const val LANG_SERIES = "Series Languages"
    const val LANG_LIVE = "Live TV Languages"
    const val AUTOPLAY = "Autoplay Next Episode"
    const val PICTURE = "Picture Size"
    const val PHONE_ORIENTATION = "Phone Player Orientation"
    const val SUB_SIZE = "Subtitle Size"
    const val RESET_TRACKS = "Reset Audio and Subtitle Choices"
    const val TMDB = "TMDB Key"
    const val OMDB = "OMDb Key"
    const val OS_KEY = "OpenSubtitles Key"
    const val OS_USER = "OpenSubtitles Username"
    const val OS_PASS = "OpenSubtitles Password"
    const val SUBDL = "SubDL Key"
    const val GEMINI = "Gemini Key"
    const val SAVE_TEST = "Save and Test"
    const val ABOUT_KEYS = "About API Keys"
    const val THEME = "Theme"
    const val REMOTE = "Remote Buttons"
    const val UPDATE_MODE = "App Updates"
    const val CHECK_UPDATE = "Check for Updates"
    const val PORTAL_REPLIES = "Portal Replies"
    const val LINK_DIAG = "Live Link Diagnostics"
    const val CLEAR_INFO = "Clear Movie Info Cache"

    val ALL = listOf(GOOGLE_ACCOUNT, SYNC_NOW, SIGN_IN, SIGN_OUT, PORTAL_URL, MAC, SERIAL, LOCK_PASSWORD, SAVE_RELOAD,
        CATALOG_SELECTOR, SENSITIVE, REFRESH_INTERVAL, REFRESH_LIBRARY, STOP_LOADING, RESUME_LOADING, CONFIRM_REFRESH,
        CLEAR_HISTORY, CLEAR_DAY, CLEAR_ALL, CANCEL, LANG_MOVIES, LANG_SERIES, LANG_LIVE, AUTOPLAY, PICTURE,
        PHONE_ORIENTATION, SUB_SIZE, RESET_TRACKS, TMDB, OMDB, OS_KEY, OS_USER, OS_PASS, SUBDL, GEMINI, SAVE_TEST,
        ABOUT_KEYS, THEME, REMOTE, UPDATE_MODE, CHECK_UPDATE, PORTAL_REPLIES, LINK_DIAG, CLEAR_INFO)

    /** Picture size values (stored key "aspect": fit / fill / zoom). */
    fun aspectLabel(a: String) = when (a) { "fill" -> "Fill Screen"; "zoom" -> "Zoom"; else -> "Fit to Screen" }
    fun nextAspect(a: String) = when (a) { "fit" -> "fill"; "fill" -> "zoom"; else -> "fit" }
}

/** A big rounded settings row: one-line title, optional small value / note, smooth focus (same motion as the app). */
@Composable
fun SettingRow(title: String, value: String = "", modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val bg by animateColorAsState(when { f -> Pal.FocusBg; selected -> Pal.ChipSel.copy(alpha = 0.55f); else -> Pal.Soft }, Motion.colorSpec(), label = "setBg")
    val shape = RoundedCornerShape(Radii.row)
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp)
            .onFocusChanged { f = it.isFocused }
            .focusCard(onClick, scale = 1.02f, shape = shape)
            .background(bg)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, color = if (f) Pal.GoldText else Pal.Text, fontSize = Ts.ROW.sp, fontWeight = if (f || selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, color = if (f) Pal.GoldText else Pal.Dim, fontSize = Ts.HEAD.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 320.dp))
    }
}

@Composable
fun SettingsScreen(repo: Repo, go: (Screen) -> Unit) {
    val s = repo.store
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val ctx = LocalContext.current
    val tv = rememberIsTv()
    val split = !AppUi.phoneNav || tv
    var portal by remember { mutableStateOf(s.portal) }
    var mac by remember { mutableStateOf(s.mac) }
    var serial by remember { mutableStateOf(s.serial) }
    var pw by remember { mutableStateOf(s.password) }
    var tmdb by remember { mutableStateOf(s.tmdbKey) }
    var omdb by remember { mutableStateOf(s.omdbKey) }
    var gem by remember { mutableStateOf(s.geminiKey) }
    var osk by remember { mutableStateOf(s.osKey) }
    var osu by remember { mutableStateOf(s.osUser) }
    var osp by remember { mutableStateOf(s.osPass) }
    var sdk by remember { mutableStateOf(s.subdlKey) }
    var autoNext by remember { mutableStateOf(s.autoNext) }
    var phoneStartLand by remember { mutableStateOf(s.phoneStartLandscape) }
    var updateMode by remember { mutableStateOf(s.updateMode) }
    var subSize by remember { mutableStateOf(s.subSize) }
    var aspect by remember { mutableStateOf(s.aspect) }
    var note by remember { mutableStateOf("") }
    var learn by remember { mutableStateOf<Pair<String, String>?>(null) }
    var sec by remember { mutableStateOf(if (split) SettingsNav.Sec.ACCOUNT else null) }
    var inDetail by remember { mutableStateOf(false) }
    val secFr = remember { SettingsNav.ALL.associateWith { FocusRequester() } }
    val detailFr = remember { FocusRequester() }
    LaunchedEffect(Unit) { portal = s.portal; mac = s.mac; serial = s.serial; pw = s.password }
    LaunchedEffect(Unit) { if (tv) { delay(150); runCatching { secFr.getValue(SettingsNav.Sec.ACCOUNT).requestFocus() } } }
    fun link(name: String, url: String) {
        note = if (openUrl(ctx, url)) "Opened $name in the browser" else "No browser here. Open $url on a phone"
    }
    val statusText = note.ifEmpty { repo.status }

    // Back goes up one level: section rows -> section list (TV) / sections (phone) -> leave Settings
    BackHandler(enabled = !Popups.any && learn == null && (if (split) inDetail else sec != null)) {
        if (split) sec?.let { runCatching { secFr.getValue(it).requestFocus() } } else sec = null
    }

    @Composable
    fun Detail(which: SettingsNav.Sec, first: Modifier) {
        val L = SettingsLabels
        when (which) {
            SettingsNav.Sec.ACCOUNT -> {
                SettingRow(L.GOOGLE_ACCOUNT, if (Cloud.signedIn) Cloud.email.ifEmpty { "Signed in" } else "Not signed in", first) { go(Screen.Account) }
                if (Cloud.signedIn) {
                    SettingRow(L.SYNC_NOW, if (Cloud.busy) "Syncing…" else Cloud.status.ifEmpty { "Signed in" } + syncAgo()) { Cloud.syncNow() }
                    SettingRow(L.SIGN_OUT) { Cloud.signOut(); note = "Signed out. Data on this device is kept." }
                } else SettingRow(L.SIGN_IN, "Sync your TV and phone") { go(Screen.Account) }
                if (Cloud.error.isNotEmpty()) Text(Cloud.error, color = Pal.Warn, fontSize = Ts.SECONDARY.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            SettingsNav.Sec.PORTAL -> {
                Box(first) { SettingField(L.PORTAL_URL, portal) { portal = it } }
                SettingField(L.MAC, mac) { mac = it }
                SettingField(L.SERIAL, serial) { serial = it }
                SettingField(L.LOCK_PASSWORD, pw, password = true) { pw = it }
                PillButton(L.SAVE_RELOAD, {
                    keyboard?.hide()
                    s.portal = portal; s.mac = mac.trim().uppercase(); s.serial = serial.trim(); s.password = pw
                    note = "Saved. Reloading…"
                    scope.launch { repo.api.reset(); repo.load(force = true); note = repo.status.ifEmpty { "Loaded" } }
                }, primary = true)
            }
            SettingsNav.Sec.CATALOG -> {
                SettingRow(L.CATALOG_SELECTOR, "Movies, series and Live TV", first) { go(Screen.Categories) }
                SettingRow(L.SENSITIVE, if (repo.showAdult) "On" else "Off") {
                    repo.showAdult = !repo.showAdult
                    note = if (repo.showAdult) "Only password-controlled movies, series and channels are shown. History is not saved in this mode." else "Sensitive content is hidden. Normal catalog is shown."
                }
                var hours by remember { mutableStateOf(s.catalogRefreshHours) }
                SettingRow(L.REFRESH_INTERVAL, "Every $hours hours") {
                    hours = CatalogStale.HOURS[(CatalogStale.HOURS.indexOf(hours).let { if (it < 0) 0 else it } + 1) % CatalogStale.HOURS.size]
                    s.catalogRefreshHours = hours
                    note = "The Movies and Series lists will refresh from the portal after $hours hours, or on the first launch of a new day."
                }
                val lib = repo.lib
                var confirm by remember { mutableStateOf(false) }
                when {
                    lib.loading || lib.indexing -> SettingRow(L.STOP_LOADING, libraryStatus(repo)) { lib.stop(); note = "Loading stopped" }
                    confirm -> SettingRow(L.CONFIRM_REFRESH, "Press OK to confirm") { confirm = false; note = lib.requestManual() }
                    else -> SettingRow(L.REFRESH_LIBRARY, libraryStatus(repo)) {
                        val w = lib.manualWait()
                        if (w > 0) note = "Try again in ${(w + 59_999) / 60_000} min" else confirm = true
                    }
                }
                if (lib.stopped && !lib.loading && !lib.indexing) SettingRow(L.RESUME_LOADING) { lib.resume(); note = "Loading resumed" }
                var clearHist by remember { mutableStateOf(false) }
                if (!clearHist) {
                    SettingRow(L.CLEAR_HISTORY, "Continue watching and resume points") { clearHist = true }
                } else {
                    SettingRow(L.CLEAR_DAY) { s.clearHistory(24 * 3600_000L); note = "Cleared last 24 hours"; clearHist = false }
                    SettingRow(L.CLEAR_ALL) { s.clearHistory(); note = "All history cleared"; clearHist = false }
                    SettingRow(L.CANCEL) { clearHist = false }
                }
            }
            SettingsNav.Sec.LANGUAGES -> {
                SettingRow(L.LANG_MOVIES, "", first) { go(Screen.Languages("movies", Screen.Settings)) }
                SettingRow(L.LANG_SERIES) { go(Screen.Languages("series", Screen.Settings)) }
                SettingRow(L.LANG_LIVE) { go(Screen.Languages("live", Screen.Settings)) }
            }
            SettingsNav.Sec.PLAYBACK -> {
                SettingRow(L.AUTOPLAY, if (autoNext) "On" else "Off", first) { autoNext = !autoNext; s.autoNext = autoNext }
                SettingRow(L.PICTURE, L.aspectLabel(aspect)) { aspect = L.nextAspect(aspect); s.aspect = aspect }
                if (AppUi.phoneNav) SettingRow(L.PHONE_ORIENTATION, if (phoneStartLand) "Landscape" else "Portrait") {
                    phoneStartLand = !phoneStartLand; s.phoneStartLandscape = phoneStartLand
                }
                SettingRow(L.SUB_SIZE, subSize.replaceFirstChar { it.uppercase() }) {
                    subSize = TrackPrefs.SIZES[(TrackPrefs.SIZES.indexOf(subSize) + 1) % TrackPrefs.SIZES.size]; s.subSize = subSize
                }
                if (s.audioLang.isNotEmpty() || s.subLang.isNotEmpty())
                    SettingRow(L.RESET_TRACKS) { s.audioLang = ""; s.subLang = ""; s.seriesTracks = ""; note = "Audio and subtitle choices reset" }
            }
            SettingsNav.Sec.KEYS -> {
                Box(first) { FieldWithLink({ SettingField(L.TMDB, tmdb, password = true) { tmdb = it } }, "Get Key") { link("TMDB", TMDB_KEY_URL) } }
                FieldWithLink({ SettingField(L.OMDB, omdb, password = true) { omdb = it } }, "Get Key") { link("OMDb", OMDB_KEY_URL) }
                FieldWithLink({ SettingField(L.GEMINI, gem, password = true) { gem = it } }, "Get Key") { link("Google AI Studio", Gemini.KEY_URL) }
                FieldWithLink({ SettingField(L.OS_KEY, osk, password = true) { osk = it } }, "Get Key") { link("OpenSubtitles", OpenSubs.KEY_URL) }
                SettingField(L.OS_USER, osu) { osu = it }
                SettingField(L.OS_PASS, osp, password = true) { osp = it }
                FieldWithLink({ SettingField(L.SUBDL, sdk, password = true) { sdk = it } }, "Get Key") { link("SubDL", SubDl.KEY_URL) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(L.SAVE_TEST, {
                        keyboard?.hide()
                        s.tmdbKey = tmdb; s.omdbKey = omdb; s.osKey = osk; s.subdlKey = sdk
                        if (osu != s.osUser) s.osUser = osu
                        if (osp != s.osPass) s.osPass = osp
                        Tmdb.setKeys(tmdb, omdb)
                        val gemChanged = gem != s.geminiKey
                        s.geminiKey = gem; Gemini.useKey(gem)
                        note = "Testing keys…"
                        scope.launch {
                            val parts = mutableListOf<String>()
                            if (tmdb.isNotBlank() || omdb.isNotBlank()) parts += withContext(Dispatchers.IO) { Tmdb.test(tmdb, omdb) }
                            if (gem.isNotBlank()) parts += withContext(Dispatchers.IO) { Gemini.test(gem) } else if (gemChanged) parts += "Gemini off"
                            if (osk.isNotBlank()) parts += "OpenSubtitles saved"
                            if (sdk.isNotBlank()) parts += "SubDL saved"
                            note = parts.joinToString(" · ").ifEmpty { "Saved" }
                            if (gem.isNotBlank()) launch { Ai.refreshSuggested(repo, force = true) }
                        }
                    }, primary = true)
                    PillButton(L.ABOUT_KEYS, {
                        learn = L.ABOUT_KEYS to ("All keys are optional and sync with your Google account.\n\n" +
                            "TMDB: posters, plots and cast. OMDb: IMDb ratings.\n\n" +
                            "Gemini: Suggested for you, plus voice and AI search. Titles from your history are sent to Google.\n\n" +
                            "OpenSubtitles and SubDL: subtitle downloads, saved on the device so replays never download again. " +
                            "Signing in with an OpenSubtitles username and password raises the limit from 5 to 20 downloads a day.")
                    })
                }
            }
            SettingsNav.Sec.HIDDEN -> {
                Box(first) { HiddenRowsPanel(repo, title = false) }
            }
            SettingsNav.Sec.GENERAL -> {
                SettingRow(L.THEME, Theme.label, first) { Theme.mode = Theme.next(Theme.mode); s.theme = Theme.mode }
                SettingRow(L.REMOTE) { go(Screen.Remote) }
                SettingRow(L.UPDATE_MODE, updateMode.label) {
                    updateMode = updateMode.next(); s.updateMode = updateMode; note = updateMode.detail
                }
                SettingRow(L.CHECK_UPDATE, Updater.statusLine()) {
                    note = "Checking for updates…"
                    scope.launch { note = Updater.check(ctx, s, manual = true).ifEmpty { Updater.statusLine().ifEmpty { "Up to date" } } }
                }
                val ver = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" } }
                Text(stringResource(R.string.app_name) + " version $ver", color = Pal.Dim, fontSize = Ts.CAPTION.sp, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
            }
            SettingsNav.Sec.TROUBLE -> {
                SettingRow(L.PORTAL_REPLIES, "", first) { learn = L.PORTAL_REPLIES to repo.api.debugLog() }
                SettingRow(L.LINK_DIAG, "Last channels tried") { learn = L.LINK_DIAG to LinkDiag.report() }
                SettingRow(L.CLEAR_INFO) { Tmdb.clearCache(); note = "Cleared" }
            }
        }
    }

    @Composable
    fun DetailPane(which: SettingsNav.Sec, modifier: Modifier) {
        Column(
            modifier.verticalScroll(rememberScrollState()).imePadding().onFocusChanged { inDetail = it.hasFocus },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(which.title, color = Pal.Text, fontSize = Ts.TITLE.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
            if (statusText.isNotEmpty()) Text(statusText, color = Pal.Warn, fontSize = Ts.SECONDARY.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Detail(which, Modifier.focusRequester(detailFr))
            Spacer(Modifier.height(40.dp))
        }
    }

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        if (split) Row(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Column(Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Settings", color = Pal.GoldText, fontSize = Ts.TITLE.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, bottom = 14.dp))
                SettingsNav.ALL.forEach { x ->
                    // Focusing a section shows it on the right (Netflix style); Right / OK go into it
                    SettingRow(x.title, "", Modifier.focusRequester(secFr.getValue(x)).onFocusChanged { if (it.isFocused) { sec = x; note = "" } },
                        selected = sec == x) { runCatching { detailFr.requestFocus() } }
                }
            }
            sec?.let { DetailPane(it, Modifier.weight(1f).fillMaxHeight()) }
        } else {
            val cur = sec
            if (cur == null) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Settings", color = Pal.GoldText, fontSize = Ts.TITLE.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    Avatar(52.dp)
                    Column(Modifier.weight(1f)) {
                        Text(if (Cloud.signedIn) Cloud.name.ifEmpty { "Google account" } else "Not signed in", color = Pal.Text, fontSize = Ts.ROW.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (Cloud.signedIn) Cloud.email else "Sign in to sync your TV and phone", color = Pal.Dim, fontSize = Ts.SECONDARY.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (statusText.isNotEmpty()) Text(statusText, color = Pal.Warn, fontSize = Ts.SECONDARY.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                SettingsNav.ALL.forEach { x -> SettingRow(x.title) { sec = x; note = "" } }
            } else DetailPane(cur, Modifier.fillMaxSize().padding(16.dp))
        }
        learn?.let { (t, b) -> InfoDialog(t, b) { learn = null } }
    }
}
