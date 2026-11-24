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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings sections (1.53, Netflix style): a short list on the left (TV / tablet), the section's rows on the right. */
object SettingsNav {
    enum class Sec(val title: String) {
        ACCOUNT("Account"), PORTAL("Portal"), CONTENT("Content"), PLAYBACK("Playback"),
        KEYS("API keys"), HIDDEN("Hidden"), HELP("About & help")
    }
    val ALL = Sec.entries.toList()

    /** Back: from a section's rows up to the section list (TV) / the list of sections (phone); from there, leave. */
    fun back(inDetail: Boolean): Boolean = inDetail   // true = handled here (go up one level)
}

/** A big rounded settings row: one-line title, optional small value / note, smooth focus (same motion as the app). */
@Composable
fun SettingRow(title: String, value: String = "", modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val bg by animateColorAsState(when { f -> Pal.FocusBg; selected -> Pal.ChipSel.copy(alpha = 0.55f); else -> Pal.Soft }, Motion.colorSpec(), label = "setBg")
    val shape = RoundedCornerShape(Radii.row)
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp)
            .onFocusChanged { f = it.isFocused }
            .focusCard(onClick, scale = 1.02f, shape = shape)
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 7.dp),
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
        when (which) {
            SettingsNav.Sec.ACCOUNT -> {
                SettingRow("Google account", if (Cloud.signedIn) Cloud.email.ifEmpty { "Signed in" } else "Sign in to sync", first) { go(Screen.Account) }
                SettingRow("Theme", Theme.label) { Theme.mode = Theme.next(Theme.mode); s.theme = Theme.mode }
            }
            SettingsNav.Sec.PORTAL -> {
                Box(first) { SettingField("Portal URL", portal) { portal = it } }
                SettingField("MAC address", mac) { mac = it }
                SettingField("Serial number", serial) { serial = it }
                SettingField("Lock password", pw, password = true) { pw = it }
                PillButton("Save and reload", {
                    keyboard?.hide()
                    s.portal = portal; s.mac = mac.trim().uppercase(); s.serial = serial.trim(); s.password = pw
                    note = "Saved. Reloading…"
                    scope.launch { repo.api.reset(); repo.load(force = true); note = repo.status.ifEmpty { "Loaded" } }
                }, primary = true)
            }
            SettingsNav.Sec.CONTENT -> {
                SettingRow("What to show", "Groups and rows", first) { go(Screen.Categories) }
                SettingRow("Movie languages") { go(Screen.Languages("movies", Screen.Settings)) }
                SettingRow("Series languages") { go(Screen.Languages("series", Screen.Settings)) }
                SettingRow("Live TV languages") { go(Screen.Languages("live", Screen.Settings)) }
                SettingRow("Adult sections", if (repo.showAdult) "Shown" else "Hidden") {
                    repo.showAdult = !repo.showAdult
                    note = if (repo.showAdult) "Adult and locked sections are shown. Playing them still needs the lock password." else "Adult and locked sections are hidden from lists."
                }
                var hours by remember { mutableStateOf(s.catalogRefreshHours) }
                SettingRow("Refresh titles after", "$hours h") {
                    hours = CatalogStale.HOURS[(CatalogStale.HOURS.indexOf(hours).let { if (it < 0) 0 else it } + 1) % CatalogStale.HOURS.size]
                    s.catalogRefreshHours = hours
                    note = "Movies and Series refresh from the portal after $hours hours, or on the first launch of a new day."
                }
                val lib = repo.lib
                var confirm by remember { mutableStateOf(false) }
                when {
                    lib.loading || lib.indexing -> SettingRow("Stop loading", libraryStatus(repo)) { lib.stop(); note = "Loading stopped" }
                    confirm -> SettingRow("Check for new titles now?", "OK to confirm") { confirm = false; note = lib.requestManual() }
                    else -> SettingRow("Refresh library", libraryStatus(repo)) {
                        val w = lib.manualWait()
                        if (w > 0) note = "Try again in ${(w + 59_999) / 60_000} min" else confirm = true
                    }
                }
                if (lib.stopped && !lib.loading && !lib.indexing) SettingRow("Resume loading") { lib.resume(); note = "Loading resumed" }
                var clearHist by remember { mutableStateOf(false) }
                if (!clearHist) {
                    SettingRow("Clear watch history") { clearHist = true }
                } else {
                    SettingRow("Last 24 hours") {
                        s.clearHistory(24 * 3600_000L)
                        note = "Cleared last 24 hours"
                        clearHist = false
                    }
                    SettingRow("All history") {
                        s.clearHistory()
                        note = "All history cleared"
                        clearHist = false
                    }
                    SettingRow("Cancel") { clearHist = false }
                }
            }
            SettingsNav.Sec.PLAYBACK -> {
                SettingRow("Autoplay next episode", if (autoNext) "On" else "Off", first) { autoNext = !autoNext; s.autoNext = autoNext }
                if (AppUi.phoneNav) SettingRow("Phone player starts in", if (phoneStartLand) "Landscape" else "Portrait") {
                    phoneStartLand = !phoneStartLand; s.phoneStartLandscape = phoneStartLand
                }
                var aspect by remember { mutableStateOf(s.aspect) }
                SettingRow("Video picture", when (aspect) { "fill" -> "Fill"; "zoom" -> "Zoom"; else -> "Fit" }) {
                    aspect = when (aspect) { "fit" -> "fill"; "fill" -> "zoom"; else -> "fit" }; s.aspect = aspect
                }
                SettingRow("Subtitle size", subSize.replaceFirstChar { it.uppercase() }) {
                    subSize = TrackPrefs.SIZES[(TrackPrefs.SIZES.indexOf(subSize) + 1) % TrackPrefs.SIZES.size]; s.subSize = subSize
                }
                SettingRow("Remote buttons") { go(Screen.Remote) }
                if (s.audioLang.isNotEmpty() || s.subLang.isNotEmpty())
                    SettingRow("Forget audio / subtitle choices") { s.audioLang = ""; s.subLang = ""; s.seriesTracks = ""; note = "Forgotten" }
            }
            SettingsNav.Sec.KEYS -> {
                var moreKeys by remember { mutableStateOf(osu.isNotEmpty() || osp.isNotEmpty() || sdk.isNotEmpty()) }
                Box(first) { FieldWithLink({ SettingField("TMDB", tmdb, password = true) { tmdb = it } }, "Get key") { link("TMDB", TMDB_KEY_URL) } }
                FieldWithLink({ SettingField("OMDb", omdb, password = true) { omdb = it } }, "Get key") { link("OMDb", OMDB_KEY_URL) }
                FieldWithLink({ SettingField("OpenSubtitles", osk, password = true) { osk = it } }, "Get key") { link("OpenSubtitles", OpenSubs.KEY_URL) }
                FieldWithLink({ SettingField("Gemini", gem, password = true) { gem = it } }, "Get key") { link("Google AI Studio", Gemini.KEY_URL) }
                if (moreKeys) {
                    SettingField("OpenSubtitles username (optional: 20 downloads/day instead of 5)", osu) { osu = it }
                    SettingField("OpenSubtitles password (optional)", osp, password = true) { osp = it }
                    FieldWithLink({ SettingField("SubDL (subtitle backup)", sdk, password = true) { sdk = it } }, "Get key") { link("SubDL", SubDl.KEY_URL) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Save and test", {
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
                    if (!moreKeys) PillButton("More subtitle options", { moreKeys = true })
                    PillButton("Learn more", {
                        learn = "API keys" to ("All optional. TMDB: posters, plots, cast. OMDb: IMDb ratings. OpenSubtitles (+ optional login for more downloads) and SubDL: subtitle downloads; files are saved on the device so replays never download again. " +
                            "Gemini: \"Suggested for you\" and voice / AI search (titles from your history are sent to Google). Keys sync with Google sync.")
                    })
                }
            }
            SettingsNav.Sec.HIDDEN -> {
                Box(first) { HiddenRowsPanel(repo, title = false) }
            }
            SettingsNav.Sec.HELP -> {
                SettingRow("Check for updates", Updater.statusLine().ifEmpty { "GitHub release" }, first) {
                    note = "Checking for updates…"
                    scope.launch { note = Updater.check(ctx, s, manual = true) }
                }
                SettingRow("Automatic updates", updateMode.label) {
                    updateMode = updateMode.next(); s.updateMode = updateMode
                    note = updateMode.detail
                }
                SettingRow("Portal replies") { learn = "Portal replies" to repo.api.debugLog() }
                SettingRow("Live link diagnostics") { learn = "Live link diagnostics" to LinkDiag.report() }
                SettingRow("Clear movie info cache") { Tmdb.clearCache(); note = "Cleared" }
                val ver = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" } }
                Text("TVstreamer $ver", color = Pal.Dim, fontSize = Ts.CAPTION.sp, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
            }
        }
    }

    @Composable
    fun DetailPane(which: SettingsNav.Sec, modifier: Modifier) {
        Column(
            modifier.verticalScroll(rememberScrollState()).imePadding().onFocusChanged { inDetail = it.hasFocus },
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(which.title, color = Pal.Text, fontSize = Ts.TITLE.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
            if (statusText.isNotEmpty()) Text(statusText, color = Pal.Warn, fontSize = Ts.SECONDARY.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Detail(which, Modifier.focusRequester(detailFr))
            Spacer(Modifier.height(40.dp))
        }
    }

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        if (split) Row(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                if (statusText.isNotEmpty()) Text(statusText, color = Pal.Warn, fontSize = Ts.SECONDARY.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                SettingsNav.ALL.forEach { x -> SettingRow(x.title) { sec = x; note = "" } }
            } else DetailPane(cur, Modifier.fillMaxSize().padding(16.dp))
        }
        learn?.let { (t, b) -> InfoDialog(t, b) { learn = null } }
    }
}
