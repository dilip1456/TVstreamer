@file:OptIn(ExperimentalFoundationApi::class)
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package tv.tvstreamer

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
    OkHttpClient.Builder()
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
private fun mediaItemFor(url: String): MediaItem {
    val l = url.lowercase(Locale.US)
    val mime = when {
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
fun PlayerScreen(repo: Repo, list: List<Item>, start: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val isTv = rememberIsTv()
    var idx by remember { mutableIntStateOf(start.coerceIn(0, (list.size - 1).coerceAtLeast(0))) }
    var attempt by remember { mutableIntStateOf(0) }
    var autoRetries by remember { mutableIntStateOf(0) }
    // Which link strategy to start from, and which one gave the current URL (see Stalker.link)
    var linkFrom by remember { mutableIntStateOf(0) }
    var linkUsed by remember { mutableIntStateOf(0) }
    var showLog by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var resolving by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var playWhenReady by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var controls by remember { mutableStateOf(true) }
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

    val item = list[idx]
    val cur by rememberUpdatedState(item)
    val live = item.kind == "itv"
    val canSeek = !live && dur > 0

    val rootFr = remember { FocusRequester() }
    val playFr = remember { FocusRequester() }
    val seekFr = remember { FocusRequester() }
    val retryFr = remember { FocusRequester() }

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
    fun showControls(seekBar: Boolean = false) { focusSeekBar = seekBar; controls = true; touch() }
    fun saveNow() {
        val i = cur
        if (i.kind == "vod" && exo.duration > 0 && exo.duration != C.TIME_UNSET) repo.store.saveResume(i, exo.currentPosition, exo.duration)
    }
    // Remember the last episode played for each show (opens the show there next time)
    LaunchedEffect(cur.key) {
        val ep = cur
        if (ep.kind == "vod" && ep.isEpisode()) withContext(Dispatchers.IO) {
            repo.store.parentOf(ep)?.let { repo.store.saveLastEp(it.key, ep.key, false) }
        }
    }
    fun go(n: Int) {
        if (list.size <= 1) return
        saveNow()
        val to = (n + list.size) % list.size
        if (to != idx) prevIdx = idx
        idx = to
        autoRetries = 0
        linkFrom = 0
        zapTick++
        touch()
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
                Act.NEXT, Act.CH_UP -> { go(idx + 1); true }
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

    // Player events -> state. Errors are shown with Retry instead of failing silently.
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exo.seekToDefaultPosition(); exo.prepare(); return
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
                err = friendly(error) + (if (cur.kind == "vod") "\nLink: ${repo.api.mask(exo.currentMediaItem?.localConfiguration?.uri?.toString() ?: "").take(160)}" else "")
                controls = true
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) autoRetries = 0
            }
            override fun onPlayWhenReadyChanged(p: Boolean, reason: Int) { playWhenReady = p }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED && cur.kind == "vod") {
                    repo.store.removeResume(cur.key)
                    val ended = cur
                    if (ended.isEpisode()) kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { repo.store.parentOf(ended)?.let { repo.store.saveLastEp(it.key, ended.key, true) } }
                    if (idx < list.size - 1) go(idx + 1) else showControls()
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
        exo.stop()
        try {
            val (url, used) = withContext(Dispatchers.IO) { repo.api.link(item, linkFrom) }
            linkUsed = used
            // Headers are read now so a token obtained during link() is included.
            http.setDefaultRequestProperties(repo.api.streamHeaders())
            exo.setMediaItem(mediaItemFor(url))
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
            err = "Could not get a stream link from the portal.\n${e.message ?: ""}".trim()
            controls = true
        } finally {
            resolving = false
        }
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
    LaunchedEffect(flashTick) { if (flash.isNotEmpty()) { delay(700); flash = "" } }

    // Move focus to the right place whenever the overlay changes.
    LaunchedEffect(controls, err, dialog) {
        delay(60)
        runCatching {
            when {
                dialog != null -> {}
                err.isNotEmpty() -> retryFr.requestFocus()
                controls -> if (focusSeekBar && canSeek) seekFr.requestFocus() else playFr.requestFocus()
                else -> rootFr.requestFocus()
            }
        }
    }

    BackHandler(dialog != null) { dialog = null; touch() }

    // Keys that are swallowed on KeyDown also have their KeyUp swallowed,
    // so the button that receives focus does not get a stray click.
    val swallowUp = remember { mutableSetOf<Key>() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFr)
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
                    Key.MediaNext, Key.ChannelUp -> { go(idx + 1); true }
                    Key.MediaPrevious, Key.ChannelDown -> { go(idx - 1); true }
                    else -> false
                }
                if (handled) { swallowUp += k; return@onPreviewKeyEvent true }
                if (dialog != null || err.isNotEmpty()) { touch(); return@onPreviewKeyEvent false }
                if (controls) { touch(); return@onPreviewKeyEvent false }
                // Controls hidden: remote shortcuts
                val used = when (k) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { showControls(); true }
                    Key.DirectionLeft -> { if (live) showControls() else { seekBy(-SEEK_MS); showControls(true) }; true }
                    Key.DirectionRight -> { if (live) showControls() else { seekBy(SEEK_MS); showControls(true) }; true }
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
                            onTap = { if (controls) controls = false else showControls() },
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
        if (showZap && !controls) {
            Column(Modifier.align(Alignment.TopStart).padding(24.dp).background(Color(0xCC000000), RoundedCornerShape(10.dp)).padding(14.dp)) {
                Text((if (live && item.num > 0) "${item.num}  " else "") + item.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (list.size > 1) Text("${idx + 1} / ${list.size}", color = Pal.Dim, fontSize = 14.sp)
            }
        }

        if (controls && err.isEmpty()) {
            key(favTick) {
                Controls(
                    item = item, live = live, list = list, idx = idx, playing = playWhenReady, pos = pos, dur = dur,
                    buffered = buffered, canSeek = canSeek, isFav = repo.isFav(item), aspect = aspect, barPos = barPos, isTv = isTv,
                    hasAudio = choices(tracks, C.TRACK_TYPE_AUDIO).size > 1, hasSubs = choices(tracks, C.TRACK_TYPE_TEXT).isNotEmpty(),
                    playFr = playFr, seekFr = seekFr,
                    onBack = onBack, onPlay = { togglePlay() }, onSeekBy = { seekBy(it); touch() },
                    onSeekTo = { exo.seekTo(it); pos = it; touch() },
                    onPrev = { go(idx - 1) }, onNext = { go(idx + 1) },
                    onFav = { repo.toggleFav(item); favTick++; touch() },
                    onDialog = { dialog = it },
                    onAspect = { setAspect(when (aspect) { "fit" -> "fill"; "fill" -> "zoom"; else -> "fit" }); touch() },
                    onMove = { barPos = when (barPos) { "bottom" -> "left"; "left" -> "right"; else -> "bottom" }; repo.store.barPos = barPos; touch() },
                    onTouch = { touch() }
                )
            }
        }

        if (err.isNotEmpty()) {
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 560.dp).padding(24.dp)
                    .background(Color(0xEE15151C), RoundedCornerShape(14.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(item.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Can't play this stream", color = Pal.Error, fontSize = 18.sp)
                Text(err, color = Pal.Dim, fontSize = 13.sp, maxLines = 14, overflow = TextOverflow.Ellipsis)
                if (showLog) Text(repo.api.debugLog(), color = Pal.Dim, fontSize = 11.sp, maxLines = 30, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Retry", { autoRetries = 0; linkFrom = 0; attempt++ }, Modifier.focusRequester(retryFr), primary = true)
                    PillButton(if (showLog) "Close portal replies" else "Portal replies", { showLog = !showLog })
                    if (list.size > 1) PillButton(if (live) "Next channel" else "Next", { go(idx + 1) })
                    PillButton("Back", onBack)
                }
            }
        }

        dialog?.let { d ->
            val opts: List<Pair<String, () -> Unit>> = when (d) {
                "audio" -> choices(tracks, C.TRACK_TYPE_AUDIO).map { c ->
                    ((if (c.selected) "✓ " else "    ") + c.label) to {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .setOverrideForType(TrackSelectionOverride(c.group!!.mediaTrackGroup, c.index)).build()
                        dialog = null
                    }
                }.ifEmpty { listOf("No other audio tracks" to { dialog = null }) }
                "subs" -> {
                    val off = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ||
                        choices(tracks, C.TRACK_TYPE_TEXT).none { it.selected }
                    listOf(((if (off) "✓ " else "    ") + "Off") to {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                        dialog = null
                    }) + choices(tracks, C.TRACK_TYPE_TEXT).map { c ->
                        ((if (c.selected && !off) "✓ " else "    ") + c.label) to {
                            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(c.group!!.mediaTrackGroup, c.index)).build()
                            dialog = null
                        }
                    }
                }
                "aspect" -> listOf("fit" to "Fit (whole picture)", "fill" to "Fill (stretch to screen)", "zoom" to "Zoom (crop edges)").map { (k, t) ->
                    ((if (aspect == k) "✓ " else "    ") + t) to { setAspect(k); dialog = null }
                }
                else -> buildList {
                    add((if (repo.isFav(item)) "Remove favorite" else "Add favorite") to { repo.toggleFav(item); favTick++; dialog = null })
                    if (live) {
                        add("Move to My Channels" to { repo.moveToCustom(item, "My Channels"); dialog = null })
                    }
                    add("Audio track" to { dialog = "audio" })
                    add("Subtitles" to { dialog = "subs" })
                    add("Picture: Fit / Fill / Zoom" to { dialog = "aspect" })
                    add("Move control bar (now: $barPos)" to { barPos = when (barPos) { "bottom" -> "left"; "left" -> "right"; else -> "bottom" }; repo.store.barPos = barPos; dialog = null })
                    add("Reload stream" to { autoRetries = 0; linkFrom = 0; attempt++; dialog = null })
                }
            }
            val title = when (d) { "audio" -> "Audio"; "subs" -> "Subtitles"; "aspect" -> "Picture"; else -> item.name }
            ChoiceDialog(title, opts) { dialog = null }
        }
    }
}

@Composable
fun ChoiceDialog(title: String, opts: List<Pair<String, () -> Unit>>, focusKey: Any? = title, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(focusKey, opts.size) { delay(60); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures { onClose() } }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(min = 280.dp, max = 460.dp).heightIn(max = 420.dp)
                .background(Color(0xF21A1A22), RoundedCornerShape(14.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(opts) { i, (label, action) ->
                    PillButton(label, action, Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier), size = 16)
                }
                item { PillButton("Close", onClose, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun CtrlButton(icon: ImageVector, desc: String, onClick: () -> Unit, modifier: Modifier = Modifier, big: Boolean = false, enabled: Boolean = true) {
    var f by remember { mutableStateOf(false) }
    val sz = if (big) 72.dp else 52.dp
    Box(
        modifier.size(sz).onFocusChanged { f = it.isFocused }.clip(CircleShape)
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
    canSeek: Boolean, isFav: Boolean, aspect: String, barPos: String, isTv: Boolean, hasAudio: Boolean, hasSubs: Boolean,
    playFr: FocusRequester, seekFr: FocusRequester,
    onBack: () -> Unit, onPlay: () -> Unit, onSeekBy: (Long) -> Unit, onSeekTo: (Long) -> Unit,
    onPrev: () -> Unit, onNext: () -> Unit, onFav: () -> Unit, onDialog: (String) -> Unit, onAspect: () -> Unit,
    onMove: () -> Unit, onTouch: () -> Unit
) {
    val pad = if (isTv) 40.dp else 16.dp
    Box(Modifier.fillMaxSize()) {
        // Top: back, title, favourite
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .padding(horizontal = pad, vertical = if (isTv) 24.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CtrlButton(Ic.Back, "Back", onBack)
            Column(Modifier.weight(1f)) {
                Text((if (live && item.num > 0) "${item.num}  " else "") + item.name, color = Color.White,
                    fontSize = if (isTv) 24.sp else 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(
                    item.season.ifEmpty { null },
                    if (list.size > 1) (if (live) "Channel" else "Item") + " ${idx + 1} of ${list.size}" else null,
                    if (live) "LIVE" else null
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
            if (list.size > 1) CtrlButton(Ic.Next, if (live) "Next channel" else "Next", onNext)
        }

        // Option buttons (movable: bottom / left / right, as before)
        val opts: @Composable () -> Unit = {
            if (hasAudio || !live) PillButton("Audio", { onDialog("audio") }, size = 14)
            PillButton(if (hasSubs) "Subtitles" else "Subs (none)", { onDialog("subs") }, size = 14)
            PillButton(when (aspect) { "fill" -> "Fill"; "zoom" -> "Zoom"; else -> "Fit" }, onAspect, size = 14)
            PillButton("Move", onMove, size = 14)
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
