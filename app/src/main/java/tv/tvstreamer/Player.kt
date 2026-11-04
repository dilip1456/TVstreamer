@file:OptIn(ExperimentalFoundationApi::class)
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package tv.tvstreamer

import androidx.compose.foundation.border

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val SEEK_MS = 10_000L
private const val HIDE_AFTER_MS = 5_000L

private val streamHttp: OkHttpClient by lazy {
    Net.portal.newBuilder()      // shares the portal client's small connection pool
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()
}

fun fmt(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Give the player a hint when the URL does not end in a recognisable extension (e.g. live.php?...&extension=m3u8). */
private fun mediaItemFor(url: String, force: Int = 0): MediaItem {
    val l = url.lowercase(Locale.US)
    val mime = when {
        force == 1 -> MimeTypes.APPLICATION_M3U8
        force == 2 -> MimeTypes.VIDEO_MP2T
        ".m3u8" in l || "extension=m3u8" in l || "type=m3u8" in l || "/hls/" in l -> MimeTypes.APPLICATION_M3U8
        ".mpd" in l || "extension=mpd" in l -> MimeTypes.APPLICATION_MPD
        else -> null
    }
    return MediaItem.Builder().setUri(url).apply { if (mime != null) setMimeType(mime) }.build()
}

private fun friendly(e: PlaybackException): String {
    val cause = e.cause
    val http = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
    val base = when {
        http != null -> "Stream server answered HTTP $http" + when (http) {
            401, 403 -> " (not authorised: link expired, MAC not allowed, or too many connections)"
            404 -> " (stream not found / offline)"
            458, 509, 429 -> " (connection limit reached)"
            else -> ""
        }
        cause is UnrecognizedInputFormatException -> "Stream format not recognised"
        e.errorCode in 2000..2999 -> "Network problem reaching the stream server"
        e.errorCode in 3000..3999 -> "Could not read the stream (unsupported or broken format)"
        e.errorCode in 4000..4999 -> "This device cannot decode the stream"
        else -> "Playback failed"
    }
    return "$base\n${e.errorCodeName}${cause?.message?.let { ": " + it.take(160) } ?: ""}"
}

private fun trackLabel(f: androidx.media3.common.Format, n: Int): String {
    val parts = mutableListOf<String>()
    f.label?.takeIf { it.isNotBlank() }?.let { parts += it }
    f.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
        val name = Locale(it).displayLanguage
        if (parts.none { p -> p.equals(name, true) }) parts += name.ifBlank { it }
    }
    if (f.channelCount > 0) parts += when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${f.channelCount}ch" }
    f.sampleMimeType?.substringAfter("/")?.uppercase()?.let { if (it.length <= 8) parts += it }
    return parts.joinToString(" · ").ifEmpty { "Track $n" }
}

private data class TrackChoice(val label: String, val group: Tracks.Group?, val index: Int, val selected: Boolean)

private fun choices(tracks: Tracks, type: Int): List<TrackChoice> {
    val out = mutableListOf<TrackChoice>()
    for (g in tracks.groups) {
        if (g.type != type) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            out += TrackChoice(trackLabel(g.getTrackFormat(i), out.size + 1), g, i, g.isTrackSelected(i))
        }
    }
    return out
}

@Composable
fun PlayerScreen(repo: Repo, initial: List<Item>, start: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var lastOkKey by remember { mutableStateOf<String?>(null) }
    var lastOkAt by remember { mutableStateOf(0L) }
    DisposableEffect(Unit) { Theme.forceDark = true; onDispose { Theme.forceDark = false } }
    val isTv = rememberIsTv()
    // The play list can grow: the next season's first episode is appended when an episode ends
    var list by remember { mutableStateOf(initial) }
    var idx by remember { mutableIntStateOf(start.coerceIn(0, (initial.size - 1).coerceAtLeast(0))) }
    var attempt by remember { mutableIntStateOf(0) }
    var autoRetries by remember { mutableIntStateOf(0) }
    // Which link strategy to start from, and which one gave the current URL (see Stalker.link)
    var linkFrom by remember { mutableIntStateOf(0) }
    var linkUsed by remember { mutableIntStateOf(0) }
    var showLog by remember { mutableStateOf(false) }
    // Live streams whose address does not say HLS / TS: play the same link again as HLS, then as plain TS
    var mimeTry by remember { mutableIntStateOf(0) }
    var zapDir by remember { mutableIntStateOf(0) }          // +1 / -1 when the channel was reached by zapping (auto-skip keeps going)
    var skipped by remember { mutableIntStateOf(0) }         // dead channels skipped in a row
    var formatTries by remember { mutableIntStateOf(0) }
    var strategyTries by remember { mutableIntStateOf(0) }
    var lastUrl by remember { mutableStateOf("") }
    // Live TV (1.53): no player controls, only these overlays (see LiveOverlay): info bar, channels (Left), schedule (Right)
    var layer by remember { mutableStateOf(LiveOverlay.Layer.NONE) }
    var infoTick by remember { mutableIntStateOf(0) }
    val panel = layer == LiveOverlay.Layer.CHANNELS || layer == LiveOverlay.Layer.GUIDE
    var err by remember { mutableStateOf("") }
    var resolving by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var playWhenReady by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var controls by remember { mutableStateOf(initial.getOrNull(start)?.kind != "itv") }   // never for live channels
    var interact by remember { mutableIntStateOf(0) }
    var focusSeekBar by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) } // audio | subs | aspect | menu
    var aspect by remember { mutableStateOf(repo.store.aspect) }
    var barPos by remember { mutableStateOf(repo.store.barPos) }
    var flash by remember { mutableStateOf("") }
    var flashTick by remember { mutableIntStateOf(0) }
    var zapTick by remember { mutableIntStateOf(0) }
    var favTick by remember { mutableIntStateOf(0) }
    var seekSide by remember { mutableIntStateOf(0) }     // -1 / 1 while the double-tap ripple shows
    var seekTotal by remember { mutableLongStateOf(0L) }
    var seekTick by remember { mutableIntStateOf(0) }
    var fastHold by remember { mutableStateOf(false) }    // press-and-hold 2x speed
    var prevIdx by remember { mutableIntStateOf(-1) }      // for "Back to previous channel"
    var nextEp by remember { mutableStateOf<Item?>(null) } // the episode after the current one (series only)
    var upNextCancelled by remember { mutableStateOf("") }  // item key whose "Next episode" countdown was cancelled
    var seriesKey by remember { mutableStateOf("") }       // show the current episode belongs to (per-series track choices)
    var subSize by remember { mutableStateOf(repo.store.subSize) }
    val autoNext = remember { repo.store.autoNext }
    val scope = rememberCoroutineScope()

    val item = list[idx].let { if (it.kind == "itv") repo.named(it) else it }   // custom channel name
    val cur by rememberUpdatedState(item)
    val live = item.kind == "itv"
    val canSeek = !live && dur > 0

    val rootFr = remember { FocusRequester() }
    val playFr = remember { FocusRequester() }
    val seekFr = remember { FocusRequester() }
    val retryFr = remember { FocusRequester() }
    val upNextFr = remember { FocusRequester() }

    val http = remember { OkHttpDataSource.Factory(streamHttp).setUserAgent(UA) }
    val exo = remember {
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
            .setConstantBitrateSeekingEnabled(true)
        ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http), extractors))
            .setSeekBackIncrementMs(SEEK_MS)
            .setSeekForwardIncrementMs(SEEK_MS)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    fun touch() { interact++ }
    fun showInfo() { if (layer == LiveOverlay.Layer.NONE || layer == LiveOverlay.Layer.INFO) layer = LiveOverlay.Layer.INFO; infoTick++ }
    fun showControls(seekBar: Boolean = false) {
        if (live) { controls = false; showInfo(); return }   // live: the info bar instead of the player controls
        focusSeekBar = seekBar; controls = true; touch()
    }
    fun saveNow() {
        val i = cur
        if (i.kind == "vod" && exo.duration > 0 && exo.duration != C.TIME_UNSET) repo.store.saveResume(i, exo.currentPosition, exo.duration)
    }
    // Remember the last episode played for each show (opens the show there next time)
    LaunchedEffect(cur.key) {
        val ep = cur
        nextEp = null
        if (ep.kind == "vod" && ep.isEpisode()) withContext(Dispatchers.IO) {
            repo.store.parentOf(ep)?.let { repo.store.saveLastEp(it.key, ep.key, false); repo.store.saveSort("season_${it.key}", ep.season) }
        }
        // Next episode: the next one in the list when it is the same show, else the show's next (also next season's first)
        if (ep.kind == "vod" && ep.isEpisodeLike()) {
            val at = list.indexOfFirst { it.key == ep.key }
            nextEp = withContext(Dispatchers.IO) {
                runCatching {
                    val parent = repo.store.parentOf(ep)
                    val after = list.getOrNull(at + 1)?.takeIf { it.kind == "vod" && it.isEpisodeLike() }
                    if (after != null && (if (parent != null) repo.store.parentOf(after)?.key == parent.key
                            else Tags.norm(after.parsed.title) == Tags.norm(ep.parsed.title))) return@runCatching after
                    if (parent == null) return@runCatching null
                    if (parent.id.startsWith("grp:")) NextEpisode.after(repo.lib.syntheticEpisodes(parent), ep)
                    else repo.seasons.nextEpisode(parent, ep)
                }.getOrNull()
            }
        }
    }
    fun go(n: Int) {
        if (list.size <= 1) return
        zapDir = when (n) { idx + 1 -> 1; idx - 1 -> -1; else -> 0 }
        saveNow()
        val to = (n + list.size) % list.size
        if (to != idx) prevIdx = idx
        idx = to
        autoRetries = 0
        linkFrom = 0
        zapTick++
        if (list[to].kind == "itv") { controls = false; showInfo() }
        touch()
    }
    /** Play episode [n] next (inserting it after the current item when it is not in the list yet). */
    fun playEpisode(n: Item, finished: Boolean) {
        val ended = cur
        val at = list.indexOfFirst { it.key == n.key }
        if (at >= 0) go(at) else {
            list = list.take(idx + 1) + n + list.drop(idx + 1)
            go(idx + 1)
        }
        // Finished: drop it from Continue watching (go() saved its position first); the new episode becomes the show's last
        if (finished) repo.store.removeResume(ended.key)
    }
    fun next() { val n = nextEp; if (n != null) playEpisode(n, finished = false) else go(idx + 1) }  // nextEp is only set for episodes
    fun trackParams(): androidx.media3.common.TrackSelectionParameters.Builder = exo.trackSelectionParameters.buildUpon()
    /** Remember an audio / subtitle choice globally and, during a series, for that show. */
    fun rememberTracks(audio: String?, sub: String?) {
        audio?.let { repo.store.audioLang = it }
        sub?.let { repo.store.subLang = it }
        if (seriesKey.isNotEmpty()) repo.store.seriesTracks = TrackPrefs.write(repo.store.seriesTracks, seriesKey, audio, sub)
    }
    val subCache = remember { Subtitles.Cache(java.io.File(ctx.filesDir, "subs")) }
    /** Adds a downloaded / saved subtitle file to the current item, keeping the position. */
    fun attachSub(f: java.io.File, l: String, source: String, select: Boolean) {
        val base = exo.currentMediaItem ?: return
        val sc = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(f))
            .setMimeType(OpenSubs.mimeFor(f)).setLanguage(l)
            .setLabel("${OpenSubs.langName(l)} ($source)")
            .setSelectionFlags(if (select) C.SELECTION_FLAG_DEFAULT else 0).build()
        val at = exo.currentPosition
        if (select) exo.trackSelectionParameters = trackParams().clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(l).build()
        exo.setMediaItem(base.buildUpon().setSubtitleConfigurations(listOf(sc)).build(), at)
        exo.prepare()
    }
    var autoSubFor by remember { mutableStateOf("") }
    /** Replays / later sessions: re-attach the subtitle picked for this title from the on-device copy (no download). */
    fun autoAttachSaved() {
        val target = cur
        if (target.kind != "vod" || autoSubFor == target.key) return
        autoSubFor = target.key
        if (exo.currentMediaItem?.localConfiguration?.subtitleConfigurations?.isNotEmpty() == true) return
        scope.launch {
            val q0 = runCatching { subtitleQuery(repo, target, "") }.getOrNull() ?: return@launch
            val (l, f) = withContext(Dispatchers.IO) {
                val l = subCache.choice(q0)
                l to l.takeIf { it.isNotEmpty() }?.let { subCache.get(q0.copy(lang = it)) }
            }
            if (f == null || cur.key != target.key) return@launch
            attachSub(f, l, "saved", select = repo.store.subLang != "off")
        }
    }
    fun seekBy(ms: Long, quiet: Boolean = false) {
        if (live) return
        val d = exo.duration
        var t = exo.currentPosition + ms
        if (d > 0 && d != C.TIME_UNSET) t = t.coerceAtMost(d - 1000)
        exo.seekTo(t.coerceAtLeast(0))
        pos = exo.currentPosition
        if (quiet) return
        flash = if (ms > 0) "+${ms / 1000}s" else "−${-ms / 1000}s"
        flashTick++
    }
    fun togglePlay() {
        if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
        if (exo.playWhenReady) exo.pause() else exo.play()
        touch()
    }
    fun setAspect(a: String) { aspect = a; repo.store.aspect = a }

    // Remote button actions (Settings > Remote buttons), sent here by the central key handler
    DisposableEffect(Unit) {
        Keys.player = { a ->
            when (a) {
                Act.PLAY_PAUSE -> { togglePlay(); if (!exo.playWhenReady) showControls(); true }
                Act.SEEK_BACK -> { seekBy(-SEEK_MS); if (!live) showControls(true); true }
                Act.SEEK_FWD -> { seekBy(SEEK_MS); if (!live) showControls(true); true }
                Act.NEXT -> { next(); true }
                Act.CH_UP -> { go(idx + 1); true }
                Act.PREV, Act.CH_DOWN -> { go(idx - 1); true }
                Act.LAST_CH -> { if (prevIdx in list.indices && prevIdx != idx) go(prevIdx) else { flash = "No previous channel"; flashTick++ }; true }
                Act.CONTROLS -> { showControls(); true }
                Act.ASPECT -> {
                    val next = when (aspect) { "fit" -> "fill"; "fill" -> "zoom"; else -> "fit" }
                    setAspect(next); flash = "Picture: " + next.replaceFirstChar { it.uppercase() }; flashTick++; true
                }
                Act.SUBS -> { dialog = "subs"; true }
                Act.AUDIO -> { dialog = "audio"; true }
                Act.MENU -> { dialog = "menu"; true }
                Act.FAV -> {
                    val i = cur
                    repo.toggleFav(i); favTick++; touch()
                    toast(ctx, (if (repo.isFav(i)) "Added to favorites: " else "Removed from favorites: ") + Names.title(i.name))
                    true
                }
                else -> false
            }
        }
        onDispose { Keys.player = null }
    }

    // Phones: landscape + immersive while playing. TV: nothing to change.
    DisposableEffect(Unit) {
        val act = ctx.findActivity()
        val oldOrientation = act?.requestedOrientation
        var ctrl: WindowInsetsControllerCompat? = null
        if (act != null && !isTv) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ctrl = WindowCompat.getInsetsController(act.window, act.window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            ctrl?.show(WindowInsetsCompat.Type.systemBars())
            if (act != null && !isTv) act.requestedOrientation = oldOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /**
     * Live playback failed: probe the link (masked into LinkDiag), then, in order: replay as the sniffed format
     * (following redirects, so relative playlist entries resolve), fetch a fresh link (signing in again when the
     * server refused the session), try the next create_link way, and finally show the bottom bar (auto-skip on zap).
     */
    fun liveError(error: PlaybackException) {
        val ch = cur
        val url = lastUrl
        val code0 = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        scope.launch {
            val p = if (url.isNotEmpty()) withContext(Dispatchers.IO) { repo.api.probe(url) } else null
            if (cur.key != ch.key) return@launch
            val fmt = p?.takeIf { it.error.isEmpty() }?.let { LiveLink.sniff(it.contentType, it.head) }
            val code = code0 ?: p?.http
            val why = LiveLink.classify(code, fmt)
            LinkDiag.update(ch.key) { it.copy(http = code, format = fmt?.name ?: p?.error.orEmpty(), result = "failed: $why · ${error.errorCodeName}") }
            val force = LiveLink.forceFor(fmt)
            val parse = error.cause is UnrecognizedInputFormatException ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
            when {
                force > 0 && force != mimeTry && formatTries < 2 && p != null -> {
                    formatTries++; mimeTry = force; LinkDiag.learnFormat(ch.key, force)
                    lastUrl = p.finalUrl
                    exo.setMediaItem(mediaItemFor(p.finalUrl, force)); exo.prepare(); exo.playWhenReady = true
                }
                force == 0 && parse && mimeTry < 2 && url.isNotEmpty() && formatTries < 2 -> {
                    formatTries++; mimeTry++
                    exo.setMediaItem(mediaItemFor(url, mimeTry)); exo.prepare(); exo.playWhenReady = true
                }
                autoRetries < 2 -> {
                    autoRetries++
                    if (LiveLink.needsResign(code, fmt)) withContext(Dispatchers.IO) { repo.api.forceResign() }
                    attempt++
                }
                strategyTries < 3 && linkUsed + 1 < repo.api.linkTries(ch) -> {
                    strategyTries++; autoRetries = 0; linkFrom = linkUsed + 1; attempt++
                }
                else -> err = friendly(error) + "\n" + why
            }
        }
    }

    // Player events -> state. Errors are shown with Retry instead of failing silently.
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exo.seekToDefaultPosition(); exo.prepare(); return
                }
                // Live: find out what the stream server really answered (status, type, first bytes) and pick the retry
                if (cur.kind == "itv") { liveError(error); return }
                val parse = error.cause is UnrecognizedInputFormatException ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                if (cur.kind == "itv" && parse && mimeTry < 2 && lastUrl.isNotEmpty()) {
                    mimeTry++
                    exo.setMediaItem(mediaItemFor(lastUrl, mimeTry)); exo.prepare(); exo.playWhenReady = true
                    return
                }
                // Movies / episodes: when the link itself does not play, try the next way of asking the portal
                if (cur.kind == "vod" && error.errorCode in 2000..3999 && linkUsed + 1 < repo.api.linkTries(cur)) {
                    linkFrom = linkUsed + 1
                    attempt++
                    return
                }
                // Live links often expire or drop: silently fetch a fresh link once or twice first
                if (autoRetries < 2 && (cur.kind == "itv" || error.errorCode in 2000..2999)) {
                    autoRetries++
                    attempt++
                    return
                }
                if (cur.kind == "vod") repo.lib.reportFailure()
                err = friendly(error) + (if (cur.kind == "vod") "\nLink: ${repo.api.mask(exo.currentMediaItem?.localConfiguration?.uri?.toString() ?: "").take(160)}" else "")
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) {
                    autoRetries = 0; skipped = 0; formatTries = 0
                    if (cur.kind == "itv") LinkDiag.update(cur.key) { it.copy(result = "playing" + (if (mimeTry > 0) " (as ${if (mimeTry == 1) "HLS" else "TS"})" else "")) }
                }
            }
            override fun onPlayWhenReadyChanged(p: Boolean, reason: Int) { playWhenReady = p }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) autoAttachSaved()
                if (state == Player.STATE_ENDED && cur.kind == "vod") {
                    val ended = cur
                    val n = nextEp
                    if (autoNext && n != null && upNextCancelled != ended.key) { playEpisode(n, finished = true); return }
                    repo.store.removeResume(ended.key)
                    if (ended.isEpisode()) kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { repo.store.parentOf(ended)?.let { repo.store.saveLastEp(it.key, ended.key, true) } }
                    if (autoNext && n == null && !ended.isEpisodeLike() && idx < list.size - 1) go(idx + 1) else showControls()
                }
            }
            override fun onTracksChanged(t: Tracks) { tracks = t }
        }
        exo.addListener(l)
        onDispose { saveNow(); exo.removeListener(l); exo.release() }
    }

    // Pause when the app goes to the background, resume when it comes back.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        var resume = false
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_STOP -> { saveNow(); resume = exo.playWhenReady; exo.pause() }
                Lifecycle.Event.ON_START -> if (resume) {
                    if (cur.kind == "itv") exo.seekToDefaultPosition()
                    exo.play()
                }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // Resolve the stream link (fresh create_link each time) and start playback.
    LaunchedEffect(idx, attempt) {
        err = ""
        resolving = true
        mimeTry = if (item.kind == "itv") LinkDiag.formatOf(item.key) else 0   // a format learned from sniffing this channel
        if (linkFrom == 0) strategyTries = 0
        exo.stop()
        try {
            // Interruptible: zapping on cancels this channel's link requests instead of letting them run in the background
            val (url, used) = kotlinx.coroutines.runInterruptible(Dispatchers.IO) { repo.api.link(item, linkFrom) }
            linkUsed = used
            // Remembered audio / subtitle choice (the show's own choice wins over the global one)
            seriesKey = withContext(Dispatchers.IO) {
                when {
                    item.isEpisode() -> repo.store.parentOf(item)?.key ?: ""
                    item.kind == "vod" && item.isEpisodeLike() -> "title:" + Tags.norm(item.parsed.title)
                    else -> ""
                }
            }
            val ch = TrackPrefs.resolve(TrackPrefs.Choice(repo.store.audioLang, repo.store.subLang),
                seriesKey.takeIf { it.isNotEmpty() }?.let { TrackPrefs.read(repo.store.seriesTracks, it) })
            exo.trackSelectionParameters = trackParams().clearOverrides()
                .setPreferredAudioLanguage(ch.audio.ifEmpty { null })
                .setPreferredTextLanguage(ch.sub.takeIf { it.isNotEmpty() && it != "off" })
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, ch.sub == "off")
                .build()
            // Headers are read now so a token obtained during link() is included.
            http.setDefaultRequestProperties(repo.api.streamHeaders())
            lastUrl = url
            exo.setMediaItem(mediaItemFor(url, mimeTry))
            exo.prepare()
            if (item.kind == "vod") {
                val (p, d) = repo.store.resumeInfo(item)
                if (p > 5000 && (d <= 0 || p < d * 0.95)) exo.seekTo(p)
            }
            exo.playWhenReady = true
            repo.markPlayed(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (item.kind == "vod") repo.lib.reportFailure()
            err = "Could not get a stream link from the portal.\n${e.message ?: ""}".trim()
        } finally {
            resolving = false
        }
    }

    // Live guide while watching: this channel and its neighbours first (zapping shows now / next at once), and this
    // channel's day schedule for the schedule list (Right)
    LaunchedEffect(idx) {
        if (item.kind == "itv") repo.epg.watching(item.id, Epg.neighbours(idx, list.size).mapNotNull { list.getOrNull(it)?.id })
    }

    // Live: a dead channel reached by zapping is skipped (same direction) after a short note; at most 5 in a row
    var skipIn by remember { mutableStateOf(false) }
    LaunchedEffect(err, idx) {
        skipIn = false
        if (item.kind != "itv" || err.isEmpty()) return@LaunchedEffect
        val step = LiveLink.skipStep(zapDir, skipped) ?: return@LaunchedEffect
        skipIn = true
        delay(2_500)
        if (err.isNotEmpty() && dialog == null && !panel) { skipped++; skipIn = false; go(idx + step) }
    }

    // Position ticker + periodic resume save.
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            delay(500)
            pos = exo.currentPosition
            dur = exo.duration.let { if (it == C.TIME_UNSET || it < 0) 0 else it }
            buffered = exo.bufferedPosition
            if (++n % 20 == 0) saveNow()
        }
    }

    // Auto-hide controls while playing.
    LaunchedEffect(controls, interact, playing, dialog, err) {
        if (controls && playing && dialog == null && err.isEmpty()) {
            delay(HIDE_AFTER_MS)
            controls = false
        }
    }
    LaunchedEffect(layer, infoTick) {
        if (layer == LiveOverlay.Layer.INFO) { delay(LiveOverlay.INFO_MS); if (layer == LiveOverlay.Layer.INFO) layer = LiveOverlay.Layer.NONE }
    }
    LaunchedEffect(flashTick) { if (flash.isNotEmpty()) { delay(700); flash = "" } }

    // Move focus to the right place whenever the overlay changes.
    LaunchedEffect(controls, err, dialog, panel) {
        delay(60)
        runCatching {
            when {
                dialog != null || panel -> {}
                err.isNotEmpty() && !controls -> rootFr.requestFocus()   // the error is a quiet bar: keep zapping
                controls -> if (focusSeekBar && canSeek) seekFr.requestFocus() else playFr.requestFocus()
                else -> rootFr.requestFocus()
            }
        }
    }

    // "Next episode in 10 s" near the end of an episode (Settings > Play next episode automatically)
    val upNextIn = if (autoNext && !live && nextEp != null && upNextCancelled != item.key && err.isEmpty() && !resolving)
        NextEpisode.countdown(pos, dur) else null
    val showUpNext = upNextIn != null && dialog == null
    LaunchedEffect(showUpNext) {
        if (showUpNext) { controls = false; delay(80); runCatching { upNextFr.requestFocus() } }
        else { delay(60); runCatching { if (dialog == null && !panel && !controls) rootFr.requestFocus() } }
    }

    // TV: Back first hides the controls, then leaves the player
    BackHandler(isTv && controls && dialog == null && !showUpNext && !Popups.any) { controls = false }
    BackHandler(showUpNext) { upNextCancelled = item.key }
    BackHandler(dialog != null) { dialog = null; touch() }
    // Live: Back first hides the info bar (the side panels close themselves), then leaves the player
    BackHandler(live && layer == LiveOverlay.Layer.INFO && dialog == null && !Popups.any) { layer = LiveOverlay.Layer.NONE }

    // Keys that are swallowed on KeyDown also have their KeyUp swallowed,
    // so the button that receives focus does not get a stray click.
    val swallowUp = remember { mutableSetOf<Key>() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFr)
            .onFocusChanged { if (it.isFocused) Popups.focused(rootFr) }
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp) return@onPreviewKeyEvent swallowUp.remove(e.key)
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val k = e.key
                // Media / channel keys work at any time
                val handled = when (k) {
                    Key.MediaPlayPause -> { togglePlay(); true }
                    Key.MediaPlay -> { exo.play(); touch(); true }
                    Key.MediaPause -> { exo.pause(); showControls(); true }
                    Key.MediaFastForward -> { seekBy(SEEK_MS); if (!live) showControls(true); true }
                    Key.MediaRewind -> { seekBy(-SEEK_MS); if (!live) showControls(true); true }
                    Key.MediaNext -> { next(); true }
                    Key.ChannelUp -> { go(idx + 1); true }
                    Key.MediaPrevious, Key.ChannelDown -> { go(idx - 1); true }
                    else -> false
                }
                if (handled) { swallowUp += k; return@onPreviewKeyEvent true }
                if (dialog != null || panel || showUpNext || Popups.any) { touch(); return@onPreviewKeyEvent false }
                // Live: no player controls. Up / Down zap at once (also with the info bar or the error bar showing),
                // OK shows the info bar (or retries a failed link), Left the category's channels, Right the schedule.
                if (live) {
                    val pad = when (k) {
                        Key.DirectionUp -> LiveOverlay.Pad.UP
                        Key.DirectionDown -> LiveOverlay.Pad.DOWN
                        Key.DirectionLeft -> LiveOverlay.Pad.LEFT
                        Key.DirectionRight -> LiveOverlay.Pad.RIGHT
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> LiveOverlay.Pad.OK
                        Key.Menu -> LiveOverlay.Pad.MENU
                        else -> null
                    } ?: return@onPreviewKeyEvent false
                    // OK twice quickly: hide / unhide this channel (it keeps playing, marked Hidden in the bar)
                    if (pad == LiveOverlay.Pad.OK && err.isEmpty()) {
                        if (e.nativeKeyEvent.repeatCount > 0) { swallowUp += k; return@onPreviewKeyEvent true }
                        val now = android.os.SystemClock.uptimeMillis()
                        if (DoublePress.isDouble(lastOkKey, lastOkAt, item.key, now)) {
                            lastOkKey = null; repo.toggleOn(item)
                            toast(ctx, if (repo.isOn(item)) "Unhidden" else "Hidden")
                            layer = LiveOverlay.Layer.INFO; infoTick++
                            swallowUp += k; return@onPreviewKeyEvent true
                        }
                        lastOkKey = item.key; lastOkAt = now
                    }
                    val (nl, fx) = LiveOverlay.press(layer, pad, hasError = err.isNotEmpty(), channels = list.size)
                    layer = nl
                    when (fx) {
                        is LiveOverlay.Effect.Zap -> go(idx + fx.step)
                        LiveOverlay.Effect.Retry -> { autoRetries = 0; linkFrom = 0; attempt++ }
                        LiveOverlay.Effect.Menu -> dialog = "menu"
                        else -> {}
                    }
                    if (nl == LiveOverlay.Layer.INFO) infoTick++
                    swallowUp += k; return@onPreviewKeyEvent true
                }
                if (controls) { touch(); return@onPreviewKeyEvent false }
                // Controls hidden: remote shortcuts
                val used = when (k) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (err.isNotEmpty()) { autoRetries = 0; linkFrom = 0; attempt++ } else showControls(); true
                    }
                    Key.DirectionLeft -> { seekBy(-SEEK_MS); showControls(true); true }
                    Key.DirectionRight -> { seekBy(SEEK_MS); showControls(true); true }
                    Key.DirectionUp -> { if (list.size > 1) go(idx + 1) else showControls(); true }
                    Key.DirectionDown -> { if (list.size > 1) go(idx - 1) else showControls(); true }
                    Key.Menu -> { dialog = "menu"; true }
                    else -> false
                }
                if (used) swallowUp += k
                used
            }
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    keepScreenOn = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(false)
                    player = exo
                }
            },
            update = { v ->
                v.subtitleView?.apply {
                    setFractionalTextSize(TrackPrefs.subFraction(subSize))
                    setStyle(androidx.media3.ui.CaptionStyleCompat(android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null))
                    setBottomPaddingFraction(if (controls) 0.22f else 0.06f)
                }
                v.resizeMode = when (aspect) {
                    "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize()
        )

        // Touch gestures (under the controls, so buttons still get their taps). Standard player behaviour:
        //  single tap = show / hide controls (never seeks)
        //  double tap left / right half = -10 s / +10 s (movies, episodes); repeated double taps add up
        //  press and hold = 2x speed while held (movies, episodes); live: hold = menu
        //  swipe up / down = next / previous channel (live only)
        val gestureScope = rememberCoroutineScope()
        var drag by remember { mutableFloatStateOf(0f) }
        Box(
            Modifier.fillMaxSize()
                .then(if (live) Modifier.pointerInput(list.size) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (drag < -100) go(idx + 1) else if (drag > 100) go(idx - 1)
                            drag = 0f
                        },
                        onDragCancel = { drag = 0f },
                        onVerticalDrag = { _, d -> drag += d }
                    )
                } else Modifier)
                .pointerInput(live) {
                    if (live) {
                        detectTapGestures(
                            onTap = { layer = if (layer == LiveOverlay.Layer.INFO) LiveOverlay.Layer.NONE else { infoTick++; LiveOverlay.Layer.INFO } },
                            onLongPress = { dialog = "menu" }
                        )
                    } else {
                        detectTapGestures(
                            onPress = {
                                val hold = gestureScope.launch {
                                    delay(viewConfiguration.longPressTimeoutMillis)
                                    if (exo.isPlaying) { exo.setPlaybackSpeed(2f); fastHold = true }
                                }
                                tryAwaitRelease()
                                hold.cancel()
                                if (fastHold) { exo.setPlaybackSpeed(1f); fastHold = false }
                            },
                            onLongPress = { },
                            onTap = { if (controls) controls = false else showControls() },
                            onDoubleTap = { o ->
                                val side = if (o.x < size.width / 2f) -1 else 1
                                seekTotal = if (side == seekSide) seekTotal + SEEK_MS else SEEK_MS
                                seekSide = side
                                seekBy(side * SEEK_MS, quiet = true)
                                seekTick++
                                if (controls) touch()
                            }
                        )
                    }
                }
        )
        // Double-tap ripple on the side that was tapped, with the running total ("+30s")
        LaunchedEffect(seekTick) { if (seekTick > 0) { delay(900); seekSide = 0; seekTotal = 0L } }
        if (seekSide != 0) {
            val left = seekSide < 0
            Box(
                Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight().fillMaxWidth(0.38f)
                    .background(
                        Color(0x33FFFFFF),
                        if (left) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                        else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(if (left) "«  −${seekTotal / 1000}s" else "+${seekTotal / 1000}s  »",
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (fastHold) {
            Text("2×  ▶▶", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)
                    .background(Color(0x99000000), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 6.dp))
        }

        if ((resolving || buffering) && err.isEmpty()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Pal.Accent)
        }
        if (flash.isNotEmpty()) {
            Text(flash, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(horizontal = 20.dp, vertical = 10.dp))
        }

        // Channel / episode banner after zapping, even when controls are hidden.
        var showZap by remember { mutableStateOf(false) }
        LaunchedEffect(zapTick) { if (zapTick > 0) { showZap = true; delay(3500); showZap = false } }
        if (showZap && !controls && !live) {
            Column(Modifier.align(Alignment.TopStart).padding(24.dp).background(Color(0xCC000000), RoundedCornerShape(10.dp)).padding(14.dp)) {
                Text((if (live && item.num > 0) "${item.num}  " else "") + (if (live) ChannelTitle.pretty(item.name) else item.name), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (list.size > 1) Text("${idx + 1} / ${list.size}", color = Pal.Dim, fontSize = 14.sp)
                if (live) NowNext(repo, item, Modifier.widthIn(max = 420.dp))
            }
        }

        // Live: the floating info bar (now with progress, next), never the player controls
        androidx.compose.animation.AnimatedVisibility(
            visible = live && layer == LiveOverlay.Layer.INFO && err.isEmpty() && dialog == null,
            enter = androidx.compose.animation.fadeIn(Motion.spec()) + androidx.compose.animation.slideInVertically(Motion.spec()) { it / 3 },
            exit = androidx.compose.animation.fadeOut(Motion.spec()),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            LiveInfoBar(repo, item, idx, list.size, isTv, actions = if (isTv) null else ({
                PillButton("☰  Channels", { layer = LiveOverlay.Layer.CHANNELS }, size = 13)
                PillButton("Schedule", { layer = LiveOverlay.Layer.GUIDE }, size = 13)
                PillButton("⋮", { dialog = "menu" }, size = 13)
            }))
        }
        if (controls && !live) {
            key(favTick) {
                Controls(
                    item = item, live = live, list = list, idx = idx, playing = playWhenReady, pos = pos, dur = dur,
                    buffered = buffered, canSeek = canSeek, isFav = repo.isFav(item), aspect = aspect, barPos = if (isTv) "bottom" else barPos, isTv = isTv,
                    hasNext = list.size > 1 || (!live && nextEp != null),
                    hasAudio = choices(tracks, C.TRACK_TYPE_AUDIO).size > 1, hasSubs = choices(tracks, C.TRACK_TYPE_TEXT).isNotEmpty(),
                    playFr = playFr, seekFr = seekFr,
                    onPlay = { togglePlay() }, onSeekBy = { seekBy(it); touch() },
                    onSeekTo = { exo.seekTo(it); pos = it; touch() },
                    onPrev = { go(idx - 1) }, onNext = { next() },
                    onFav = { repo.toggleFav(item); favTick++; touch() },
                    onDialog = { dialog = it },
                    onAspect = { setAspect(when (aspect) { "fit" -> "fill"; "fill" -> "zoom"; else -> "fit" }); touch() },
                    onMove = { barPos = when (barPos) { "bottom" -> "left"; "left" -> "right"; else -> "bottom" }; repo.store.barPos = barPos; touch() },
                    onTouch = { touch() }
                )
            }
        }

        if (showUpNext) {
            val n = nextEp
            Column(
                Modifier.align(Alignment.BottomEnd).padding(if (isTv) 40.dp else 20.dp).widthIn(max = 420.dp)
                    .background(Color(0xEE15151C), RoundedCornerShape(14.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Next episode in ${upNextIn}s", color = Color.White, fontSize = if (isTv) 20.sp else 17.sp, fontWeight = FontWeight.Bold)
                if (n != null) Text(listOf(n.season, n.name).filter { it.isNotBlank() }.joinToString(" · "), color = Pal.Dim, fontSize = 14.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("▶  Play now", { nextEp?.let { playEpisode(it, finished = true) } }, Modifier.focusRequester(upNextFr), primary = true)
                    PillButton("Cancel", { upNextCancelled = item.key; touch() })
                }
            }
        }

        // Link / playback failure: a small bar at the bottom, never a blocking popup. Up / Down keep zapping, OK retries.
        if (err.isNotEmpty() && !controls) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = if (isTv) 36.dp else 20.dp, start = 16.dp, end = 16.dp)
                    .widthIn(max = 720.dp).background(Color(0xE6141419), RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Can't play ${if (live) ChannelTitle.pretty(item.name) else item.name}", color = Color.White, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (skipIn) "Not working · skipping to the next channel…" else
                        err.lineSequence().firstOrNull().orEmpty() + if (isTv) (if (live && list.size > 1) "  ·  OK retry  ·  ▲▼ other channel" else "  ·  OK retry") else "",
                        color = Pal.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!isTv) {
                    PillButton("Retry", { autoRetries = 0; linkFrom = 0; attempt++ }, size = 13)
                    if (list.size > 1) PillButton("Next", { go(idx + 1) }, size = 13)
                }
            }
        }

        if (live && layer == LiveOverlay.Layer.CHANNELS) {
            // Same sections as the Live TV list behind (e.g. "Sports › Cricket"), not the portal's language groups (1.55)
            val (ix, at, secTitle) = remember(list, idx) { LiveOverlay.sectionList(repo.liveSpans, repo.liveSpanKeys, idx, list) }
            val catName = secTitle.ifBlank { repo.liveCats.firstOrNull { it.id == item.cat }?.title?.let { LiveKind.sectionLabel(it) }.orEmpty() }
            ChannelPanel(repo, ix.map { list[it] }, at, title = catName.ifBlank { "Channels" },
                onPlay = { n -> layer = LiveOverlay.Layer.NONE; ix.getOrNull(n)?.let { if (it != idx) go(it) } }) { layer = LiveOverlay.Layer.NONE }
        }
        if (live && layer == LiveOverlay.Layer.GUIDE) GuidePanel(repo, item) { layer = LiveOverlay.Layer.NONE }

        dialog?.let { d ->
            val opts: List<Pair<String, () -> Unit>> = when (d) {
                "audio" -> choices(tracks, C.TRACK_TYPE_AUDIO).map { c ->
                    ((if (c.selected) "✓ " else "    ") + c.label) to {
                        exo.trackSelectionParameters = trackParams()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .setOverrideForType(TrackSelectionOverride(c.group!!.mediaTrackGroup, c.index)).build()
                        c.group.getTrackFormat(c.index).language?.takeIf { it.isNotBlank() && it != "und" }?.let { rememberTracks(it, null) }
                        dialog = null
                    }
                }.ifEmpty { listOf("No other audio tracks" to { dialog = null }) }
                "subs" -> {
                    val off = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ||
                        choices(tracks, C.TRACK_TYPE_TEXT).none { it.selected }
                    listOf(((if (off) "✓ " else "    ") + "Off") to {
                        exo.trackSelectionParameters = trackParams()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                        rememberTracks(null, "off")
                        dialog = null
                    }) + choices(tracks, C.TRACK_TYPE_TEXT).map { c ->
                        ((if (c.selected && !off) "✓ " else "    ") + c.label) to {
                            exo.trackSelectionParameters = trackParams()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(c.group!!.mediaTrackGroup, c.index)).build()
                            c.group.getTrackFormat(c.index).language?.takeIf { it.isNotBlank() && it != "und" }?.let { rememberTracks(null, it) }
                            dialog = null
                        }
                    } + listOfNotNull(
                        (if (live) null else "⬇  Download subtitles…" to { dialog = "subdl" }),
                        "    Text size: ${subSize.replaceFirstChar { it.uppercase() }}" to {
                            subSize = TrackPrefs.SIZES[(TrackPrefs.SIZES.indexOf(subSize) + 1) % TrackPrefs.SIZES.size]
                            repo.store.subSize = subSize
                        }
                    )
                }
                "subdl" -> {
                    val pref = repo.store.subLang.takeIf { it.isNotEmpty() && it != "off" }?.let { TrackPrefs.norm(it) }
                    val langs = (listOfNotNull(pref, Locale.getDefault().language.takeIf { it.length == 2 }, "en") + OpenSubs.LANGS).distinct()
                    langs.map { l ->
                        OpenSubs.langName(l) to {
                            dialog = null
                            val target = item
                            flash = "Searching subtitles…"; flashTick++
                            scope.launch {
                                try {
                                    val q = subtitleQuery(repo, target, l)
                                    val got = Subtitles.fetch(repo.store, subCache, q) { m -> flash = m; flashTick++ }
                                    subCache.remember(q, l)
                                    if (cur.key != target.key) return@launch
                                    attachSub(got.file, l, got.source, select = true)
                                    rememberTracks(null, l)
                                    flash = "${OpenSubs.langName(l)} subtitles added" + if (got.cached) " (saved copy)" else " (${got.source})"; flashTick++
                                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                                    toast(ctx, e.message ?: "Could not download subtitles")
                                }
                            }
                        }
                    }
                }
                "aspect" -> listOf("fit" to "Fit (whole picture)", "fill" to "Fill (stretch to screen)", "zoom" to "Zoom (crop edges)").map { (k, t) ->
                    ((if (aspect == k) "✓ " else "    ") + t) to { setAspect(k); dialog = null }
                }
                else -> buildList {
                    if (!live) add((if (repo.isFav(item)) "Remove favorite" else "Add favorite") to { repo.toggleFav(item); favTick++; dialog = null })
                    add("Audio track" to { dialog = "audio" })
                    add("Subtitles" to { dialog = "subs" })
                    add("Picture: Fit / Fill / Zoom" to { dialog = "aspect" })
                    if (!isTv) add("Move control bar (now: $barPos)" to { barPos = when (barPos) { "bottom" -> "left"; "left" -> "right"; else -> "bottom" }; repo.store.barPos = barPos; dialog = null })
                    add((if (live) "Not working? Retry link" else "Reload stream") to { autoRetries = 0; linkFrom = 0; attempt++; dialog = null })
                    if (err.isNotEmpty() || live) add("Stream details" to { dialog = "details" })
                }
            }
            val title = when (d) { "audio" -> "Audio"; "subs" -> "Subtitles"; "subdl" -> "Download subtitles: language"; "aspect" -> "Picture"; else -> item.name }
            when {
                d == "details" -> InfoDialog("Stream details", (if (err.isNotEmpty()) err + "\n\n" else "") +
                    (if (live) LinkDiag.of(item.key)?.line()?.let { it + "\n\n" }.orEmpty() else "") + repo.api.debugLog()) { dialog = null }
                // Live: the shared channel menu (play, hide, modes, rename, guide …) with the player entries below it
                live && d !in setOf("audio", "subs", "subdl", "aspect") -> ChannelMenu(repo, item, null, opts, null, inPlayer = true,
                    onGuide = { dialog = null; layer = LiveOverlay.Layer.GUIDE }) { favTick++; dialog = null }
                else -> ChoiceDialog(title, opts) { dialog = null }
            }
        }
    }
}

/**
 * A short menu: title, the options valid for the item, Close last. One Back closes it and focus returns to the item that
 * opened it (see PopupFrame).
 */
@Composable
fun ChoiceDialog(title: String, opts: List<Pair<String, () -> Unit>>, focusKey: Any? = title, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(focusKey, opts.size) { delay(60); runCatching { first.requestFocus() } }
    // Compact, see-through card on the right (1.54): small text, tight rows, the page stays visible behind it
    PopupFrame(onClose, alignment = Alignment.CenterEnd, scrim = Color(0x40000000)) {
        Column(
            Modifier.padding(end = 40.dp).widthIn(min = 240.dp, max = 330.dp).heightIn(max = 420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Pal.Surface.copy(alpha = 0.86f))
                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, color = Pal.Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            Spacer(Modifier.height(4.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                itemsIndexed(opts) { i, (label, action) ->
                    MenuEntry(label, action, if (i == 0) Modifier.focusRequester(first) else Modifier)
                }
                item {
                    Spacer(Modifier.height(4.dp))
                    MenuEntry("Close", onClose, if (opts.isEmpty()) Modifier.focusRequester(first) else Modifier, dim = true)
                }
            }
        }
    }
}

/** One menu line: quiet until focused, then a soft gold highlight (Netflix-like, no heavy buttons). */
@Composable
fun MenuEntry(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, dim: Boolean = false) {
    var f by remember { mutableStateOf(false) }
    val bg by androidx.compose.animation.animateColorAsState(if (f) Pal.Gold else Color.Transparent, Motion.colorSpec(), label = "menuBg")
    Box(
        modifier.fillMaxWidth().popupOpener().onFocusChanged { f = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(label, color = when { f -> Color.Black; dim -> Pal.Dim; else -> Pal.Text }, fontSize = 14.sp,
            fontWeight = if (f) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CtrlButton(icon: ImageVector, desc: String, onClick: () -> Unit, modifier: Modifier = Modifier, big: Boolean = false, enabled: Boolean = true) {
    var f by remember { mutableStateOf(false) }
    val sz = if (big) 72.dp else 52.dp
    Box(
        modifier.size(sz).popupOpener().onFocusChanged { f = it.isFocused }.clip(CircleShape)
            .background(if (f) Color.White else Color(0x40FFFFFF))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = when { f -> Color.Black; enabled -> Color.White; else -> Color(0x66FFFFFF) },
            modifier = Modifier.size(if (big) 40.dp else 28.dp))
    }
}

@Composable
private fun SeekBar(pos: Long, dur: Long, buffered: Long, fr: FocusRequester, onSeekBy: (Long) -> Unit, onSeekTo: (Long) -> Unit, modifier: Modifier = Modifier) {
    var f by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val frac = dragFrac ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val bufFrac = if (dur > 0) (buffered.toFloat() / dur).coerceIn(0f, 1f) else 0f
    BoxWithConstraints(
        modifier.height(36.dp)
            .focusRequester(fr)
            .onFocusChanged { f = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { onSeekBy(-SEEK_MS); true }
                    Key.DirectionRight -> { onSeekBy(SEEK_MS); true }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(dur) {
                detectTapGestures { o -> if (dur > 0) onSeekTo((o.x / size.width * dur).toLong()) }
            }
            .pointerInput(dur) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFrac = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragFrac?.let { if (dur > 0) onSeekTo((it * dur).toLong()) }; dragFrac = null },
                    onDragCancel = { dragFrac = null },
                    onHorizontalDrag = { ch, _ -> dragFrac = (ch.position.x / size.width).coerceIn(0f, 1f) }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val h = if (f) 8.dp else 5.dp
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(4.dp)).background(Color(0x55FFFFFF)))
        Box(Modifier.fillMaxWidth(bufFrac).height(h).clip(RoundedCornerShape(4.dp)).background(Color(0x88FFFFFF)))
        Box(Modifier.fillMaxWidth(frac).height(h).clip(RoundedCornerShape(4.dp)).background(Pal.Accent))
        val knob = if (f) 22.dp else 14.dp
        Box(
            Modifier.offset(x = (maxWidth * frac - knob / 2).coerceAtLeast(0.dp)).size(knob).clip(CircleShape)
                .background(if (f) Color.White else Pal.Accent)
        )
    }
}

@Composable
private fun Controls(
    item: Item, live: Boolean, list: List<Item>, idx: Int, playing: Boolean, pos: Long, dur: Long, buffered: Long,
    canSeek: Boolean, isFav: Boolean, aspect: String, barPos: String, isTv: Boolean, hasNext: Boolean, hasAudio: Boolean, hasSubs: Boolean,
    playFr: FocusRequester, seekFr: FocusRequester,
    onPlay: () -> Unit, onSeekBy: (Long) -> Unit, onSeekTo: (Long) -> Unit,
    onPrev: () -> Unit, onNext: () -> Unit, onFav: () -> Unit, onDialog: (String) -> Unit, onAspect: () -> Unit,
    onMove: () -> Unit, onTouch: () -> Unit
) {
    val pad = if (isTv) 40.dp else 16.dp
    if (isTv) {
        TvControls(item, live, list, idx, playing, pos, dur, buffered, canSeek, aspect, hasNext, hasAudio, hasSubs, playFr, seekFr,
            onPlay, onSeekBy, onSeekTo, onPrev, onNext, onDialog)
        return
    }
    Box(Modifier.fillMaxSize()) {
        // Top: title, favourite, options (Back = system / remote Back)
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .padding(horizontal = pad, vertical = if (isTv) 24.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text((if (live && item.num > 0) "${item.num}  " else "") + item.name, color = Color.White,
                    fontSize = if (isTv) 24.sp else 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(
                    item.season.ifEmpty { null },
                    if (list.size > 1) "${idx + 1} / ${list.size}" else null
                ).joinToString("  ·  ")
                if (sub.isNotEmpty()) Text(sub, color = Pal.Dim, fontSize = 14.sp, maxLines = 1)
            }
            CtrlButton(if (isFav) Ic.Star else Ic.StarOutline, "Favorite", onFav)
            CtrlButton(Ic.More, "Options", { onDialog("menu") })
        }

        // Centre: prev, -10, play/pause, +10, next
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 28.dp else 18.dp)
        ) {
            if (list.size > 1) CtrlButton(Ic.Prev, if (live) "Previous channel" else "Previous", onPrev)
            if (!live) CtrlButton(Ic.Rewind, "Back 10 seconds", { onSeekBy(-SEEK_MS) }, enabled = canSeek)
            CtrlButton(if (playing) Ic.Pause else Ic.Play, if (playing) "Pause" else "Play", onPlay, Modifier.focusRequester(playFr), big = true)
            if (!live) CtrlButton(Ic.Forward, "Forward 10 seconds", { onSeekBy(SEEK_MS) }, enabled = canSeek)
            if (hasNext) CtrlButton(Ic.Next, if (live) "Next channel" else "Next", onNext)
        }

        // Option buttons (movable: bottom / left / right, as before)
        val opts: @Composable () -> Unit = {
            if (hasAudio || !live) PillButton("Audio", { onDialog("audio") }, size = 14)
            PillButton("Subtitles", { onDialog("subs") }, size = 14)
            PillButton(when (aspect) { "fill" -> "Fill"; "zoom" -> "Zoom"; else -> "Fit" }, onAspect, size = 14)
        }

        // Bottom: seek bar / live badge (+ options when bar is at the bottom)
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                .padding(horizontal = pad, vertical = if (isTv) 24.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!live) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(fmt(pos), color = Color.White, fontSize = 14.sp)
                    SeekBar(pos, dur, buffered, seekFr, onSeekBy, onSeekTo, Modifier.weight(1f))
                    Text(if (dur > 0) fmt(dur) else "--:--", color = Color.White, fontSize = 14.sp)
                }
            } else {
                Text("● LIVE", color = Pal.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            if (barPos == "bottom") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { opts() }
            }
        }
        if (barPos != "bottom") {
            Column(
                Modifier.align(if (barPos == "left") Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 12.dp).background(Color(0xAA000000), RoundedCornerShape(14.dp)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) { opts() }
        }
    }
}

/** OpenSubtitles search for [item]: TMDB / IMDb ids when known (the show's ids for an episode), else the title. */
private suspend fun subtitleQuery(repo: Repo, item: Item, lang: String): OpenSubs.Query = withContext(Dispatchers.IO) {
    val episode = item.isEpisodeLike()
    val parent = if (item.isEpisode()) repo.store.parentOf(item) else null
    val show = parent ?: item
    val meta = runCatching { Tmdb.get(show) }.getOrNull()?.takeIf { it.found }
    val epNo = if (episode) EpisodeOrder.episodeNo(item) else 0
    val season = if (episode) EpisodeOrder.seasonNo(item).takeIf { it > 0 } ?: (if (epNo > 0) 1 else 0) else 0
    OpenSubs.Query(tmdbId = meta?.tmdbId ?: 0, imdbId = meta?.imdbId.orEmpty(),
        title = Names.title(show.parsed.title.ifBlank { show.name }), year = meta?.year?.ifBlank { null } ?: show.year.take(4),
        season = season, episode = epNo, lang = lang)
}

/**
 * TV / D-pad controls: a standard player bar under the video and nothing at the sides. Seek bar on top, then
 * previous · −10 s · play/pause · +10 s · next on the left and audio · subtitles · picture · more on the right.
 * Play/pause has focus when the bar opens; Up reaches the seek bar (Left / Right seek there).
 */
@Composable
private fun TvControls(
    item: Item, live: Boolean, list: List<Item>, idx: Int, playing: Boolean, pos: Long, dur: Long, buffered: Long,
    canSeek: Boolean, aspect: String, hasNext: Boolean, hasAudio: Boolean, hasSubs: Boolean,
    playFr: FocusRequester, seekFr: FocusRequester,
    onPlay: () -> Unit, onSeekBy: (Long) -> Unit, onSeekTo: (Long) -> Unit,
    onPrev: () -> Unit, onNext: () -> Unit, onDialog: (String) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000), Color(0xEE000000))))
                .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text((if (live && item.num > 0) "${item.num}  " else "") + item.name, color = Color.White,
                fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                item.season.ifEmpty { null },
                if (list.size > 1) "${idx + 1} / ${list.size}" else null
            ).joinToString("  ·  ")
            if (sub.isNotEmpty()) Text(sub, color = Pal.Dim, fontSize = 14.sp, maxLines = 1)
            if (!live) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(fmt(pos), color = Color.White, fontSize = 15.sp)
                    SeekBar(pos, dur, buffered, seekFr, onSeekBy, onSeekTo, Modifier.weight(1f))
                    Text(if (dur > 0) fmt(dur) else "--:--", color = Color.White, fontSize = 15.sp)
                }
            } else {
                Text("● LIVE", color = Pal.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (list.size > 1) CtrlButton(Ic.Prev, if (live) "Previous channel" else "Previous", onPrev)
                if (!live) CtrlButton(Ic.Rewind, "Back 10 seconds", { onSeekBy(-SEEK_MS) }, enabled = canSeek)
                CtrlButton(if (playing) Ic.Pause else Ic.Play, if (playing) "Pause" else "Play", onPlay, Modifier.focusRequester(playFr))
                if (!live) CtrlButton(Ic.Forward, "Forward 10 seconds", { onSeekBy(SEEK_MS) }, enabled = canSeek)
                if (hasNext) CtrlButton(Ic.Next, if (live) "Next channel" else "Next episode", onNext)
                Spacer(Modifier.weight(1f))
                if (hasAudio || !live) CtrlButton(Ic.Audio, "Audio", { onDialog("audio") })
                CtrlButton(Ic.Subtitles, if (hasSubs) "Subtitles" else "Subtitles (none)", { onDialog("subs") })
                CtrlButton(Ic.Aspect, "Picture: " + aspect, { onDialog("aspect") })
                CtrlButton(Ic.More, "More", { onDialog("menu") })
            }
        }
    }
}
