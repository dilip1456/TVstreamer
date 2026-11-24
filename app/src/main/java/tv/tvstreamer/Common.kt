@file:OptIn(ExperimentalFoundationApi::class)

package tv.tvstreamer

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** App colours (Netflix-like dark theme). */
/** App theme: "dark" (default), "light" or "system". Saved in Store ("theme", synced) and applied app-wide. */
object Theme {
    var mode by mutableStateOf("dark")
    /** System dark mode, updated by the activity. */
    var systemDark by mutableStateOf(true)
    /** The full-screen player is always dark. */
    var forceDark by mutableStateOf(false)
    val dark: Boolean get() = forceDark || when (mode) { "light" -> false; "system" -> systemDark; else -> true }
    val label: String get() = when (mode) { "light" -> "Light"; "system" -> "System"; else -> "Dark" }
    fun next(m: String) = when (m) { "dark" -> "light"; "light" -> "system"; else -> "dark" }
}

/** Colours for the current theme. Video / poster overlays keep fixed dark colours. */
object Pal {
    private fun pick(dark: Long, light: Long) = Color(if (Theme.dark) dark else light)
    val Bg get() = pick(0xFF08080C, 0xFFF5F5F8)
    val Surface get() = pick(0xFF12121A, 0xFFFFFFFF)
    val Card get() = pick(0xFF1A1A22, 0xFFE6E6EC)
    val Accent = Color(0xFFE50914)
    /** Brand gold (logo #D4AF37): focus borders and highlights. */
    val Gold = Color(0xFFD4AF37)
    /** Gold for text (darker in light mode so it stays readable). */
    val GoldText get() = pick(0xFFD4AF37, 0xFF7A5C00)
    val Focus = Gold
    val Dim get() = pick(0xFF9AA0B5, 0xFF555B6B)
    val Warn get() = pick(0xFFFFB74D, 0xFFA04F00)
    val Error get() = pick(0xFFFF8A80, 0xFFC62828)
    /** Main text, secondary text, descriptions. */
    val Text get() = pick(0xFFFFFFFF, 0xFF141418)
    val Text2 get() = pick(0xFFE0E0E0, 0xFF2A2A33)
    val Desc get() = pick(0xFFDDDDDD, 0xFF3A3A44)
    /** Subtle fills for rows / buttons. */
    val Soft get() = pick(0x22FFFFFF, 0x12000000)
    val Soft2 get() = pick(0x33FFFFFF, 0x1C000000)
    val Selected get() = pick(0xFF3A3A48, 0xFFD5D5E0)
    val Field get() = pick(0xFF1A1A22, 0xFFEBEBF1)
    val FieldFocus get() = pick(0xFF1E1E26, 0xFFE2E2EA)
    val Row get() = pick(0xFF1E1E26, 0xFFFFFFFF)
    val FocusBg get() = pick(0xFF2A2414, 0xFFFFF1C4)
    val WarnBox get() = pick(0xFF2A1F1A, 0xFFFFEEDC)
    val ChipSel get() = pick(0xFF5A4A12, 0xFFF1DE9C)
    val Shimmer get() = pick(0xFF383844, 0xFFF0F0F5)
}

/** True on Fire TV / Android TV (or a large screen), false on phones. */
@Composable
fun rememberIsTv(): Boolean {
    val ctx = LocalContext.current
    return remember { isTvDevice(ctx) }
}

fun isTvDevice(ctx: Context): Boolean {
    val cfg = ctx.resources.configuration
    val tvMode = (cfg.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    val leanback = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        ctx.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    return tvMode || leanback || cfg.smallestScreenWidthDp >= 600
}

/**
 * Focusable + clickable card for D-pad and touch: grows and gets a soft gold glow when focused.
 * Long press or the remote's Menu key fires onMenu. No blur / drop-shadow (cheap on Fire TV).
 */
fun Modifier.focusCard(
    onClick: () -> Unit,
    onMenu: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    scale: Float = 1.06f,
    shape: Shape = RoundedCornerShape(14.dp)
): Modifier = composed {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) scale else 1f, Motion.spec(), label = "focusScale")
    val ring by animateFloatAsState(if (f) 1f else 0f, Motion.spec(), label = "focusRing")
    this
        .popupOpener()
        .onFocusChanged { if (it.isFocused != f) { f = it.isFocused; if (f) onFocused?.invoke() } }
        .graphicsLayer { scaleX = s; scaleY = s }
        .then(if (ring > 0.01f) Modifier
            .border(8.dp, Pal.Focus.copy(alpha = 0.16f * ring), shape)
            .border(2.dp, Pal.Focus.copy(alpha = 0.95f * ring), shape) else Modifier)
        .clip(shape)
        .onKeyEvent { e ->
            if (onMenu != null && e.type == KeyEventType.KeyUp && e.key == Key.Menu) { onMenu(); true } else false
        }
        .combinedClickable(onClick = onClick, onLongClick = onMenu)
}

/** Top menu tab (Netflix style): plain text; focused = soft light pill (animated), current tab = bold white. */
@Composable
fun NavTab(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, icon: ImageVector? = null) {
    var f by remember { mutableStateOf(false) }
    val bg by androidx.compose.animation.animateColorAsState(if (f) Color(0xF2F5F0E6) else Color.Transparent, Motion.colorSpec(), label = "tabBg")
    val fg by androidx.compose.animation.animateColorAsState(when { f -> Color(0xFF141414); selected -> Pal.Text; else -> Pal.Dim }, Motion.colorSpec(), label = "tabFg")
    Box(
        modifier.popupOpener()
            .onFocusChanged { f = it.isFocused }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) androidx.compose.material3.Icon(icon, text, tint = fg, modifier = Modifier.size(22.dp))
        else Text(text, color = fg, fontSize = Ts.ROW.sp, fontWeight = if (selected || f) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

/** Soft gold wash behind TV / catalog headers (static gradient, no blur). */
@Composable
fun CinematicWash(modifier: Modifier = Modifier) {
    if (!Theme.dark) return
    Box(
        modifier.fillMaxWidth().height(440.dp).background(
            Brush.verticalGradient(listOf(Color(0xFF15110C), Pal.Gold.copy(alpha = 0.08f), Color.Transparent))
        )
    )
}

/** One motion language for focus and transitions: ~180 ms ease (cheap on Fire TV: no blur, no shadows). */
object Motion {
    const val MS = 180
    fun <T> spec(): androidx.compose.animation.core.TweenSpec<T> = androidx.compose.animation.core.tween(MS, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    fun colorSpec(): androidx.compose.animation.core.TweenSpec<Color> = spec()
}

/** Compact pill / text button (the "Fix match" style) with a filled gold focus state. Used for every action button in the app. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
    size: Int = Ts.HEAD,
    compact: Boolean = false,
    onMenu: (() -> Unit)? = null,
    icon: ImageVector? = null
) {
    var f by remember { mutableStateOf(false) }
    val target = when {
        f -> Pal.Gold
        primary -> Pal.Accent
        selected -> Pal.Selected
        else -> Pal.Soft2
    }
    val bg by androidx.compose.animation.animateColorAsState(target, Motion.colorSpec(), label = "pillBg")
    val s by animateFloatAsState(if (f) 1.05f else 1f, Motion.spec(), label = "pillScale")
    Box(
        modifier
            .popupOpener()
            .onFocusChanged { f = it.isFocused }
            .graphicsLayer { scaleX = s; scaleY = s }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .onKeyEvent { e -> if (onMenu != null && e.type == KeyEventType.KeyUp && e.key == Key.Menu) { onMenu(); true } else false }
            .combinedClickable(onClick = onClick, onLongClick = onMenu)
            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 6.dp else 8.dp),
        contentAlignment = Alignment.Center
    ) {
        val fg = if (f) Color.Black else if (primary) Color.White else Pal.Text
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                androidx.compose.material3.Icon(icon, null, tint = fg, modifier = Modifier.size((size + 6).dp))
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            }
            Text(Glyphs.plain(text), color = fg, fontSize = size.sp,
                fontWeight = if (primary || selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Wrapping row of compact buttons (side by side, wraps onto the next line when narrow). */
@Composable
fun ButtonFlow(gapDp: Int = 8, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content) { measurables, constraints ->
        val gap = gapDp.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        var x = 0; var y = 0; var rowH = 0
        val pos = placeables.map { p ->
            if (x > 0 && x + p.width > constraints.maxWidth) { x = 0; y += rowH + gap; rowH = 0 }
            val at = x to y
            x += p.width + gap
            rowH = maxOf(rowH, p.height)
            at
        }
        layout(constraints.maxWidth, y + rowH) { placeables.forEachIndexed { i, p -> p.place(pos[i].first, pos[i].second) } }
    }
}

/** One shared Coil image loader: memory + 200 MB disk cache, MAG user agent. */
object Images {
    @Volatile private var loader: ImageLoader? = null
    fun get(ctx: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(ctx.applicationContext)
            .okHttpClient {
                OkHttpClient.Builder()
                    // Posters often come from the portal's own server: a few at a time, not a burst per screen
                    .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 3 })
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", UA).build()) }
                    .build()
            }
            .memoryCache { MemoryCache.Builder(ctx.applicationContext).maxSizePercent(0.2).build() }
            .diskCache { DiskCache.Builder().directory(ctx.applicationContext.cacheDir.resolve("img")).maxSizeBytes(200L * 1024 * 1024).build() }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build().also { loader = it }
    }
}

/**
 * Poster / backdrop / logo with a coloured initials card underneath: it shows while loading,
 * and stays when the portal has no image or the image fails.
 */
@Composable
fun Poster(url: String, title: String, modifier: Modifier = Modifier, fit: ContentScale = ContentScale.Crop, initialsSize: Int = 26) {
    val ctx = LocalContext.current
    val hue = ((title.hashCode() and 0x7FFFFFFF) % 360).toFloat()
    Box(
        modifier.background(Brush.linearGradient(listOf(Color.hsl(hue, 0.45f, 0.30f), Color.hsl((hue + 40) % 360, 0.45f, 0.14f)))),
        contentAlignment = Alignment.Center
    ) {
        if (initialsSize > 1) Text(
            title.split(" ", "-", ":").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(),
            color = Pal.Text.copy(alpha = 0.8f), fontSize = initialsSize.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
        )
        if (url.isNotBlank()) {
            val req = remember(url) { ImageRequest.Builder(ctx).data(url).crossfade(150).build() }  // short fade: less work per D-pad step on TV
            AsyncImage(model = req, imageLoader = Images.get(ctx), contentDescription = title,
                contentScale = fit, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Animated grey placeholder used while a row or grid is loading. */
fun Modifier.shimmer(): Modifier = composed {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "shimmerX")
    background(Brush.linearGradient(listOf(Pal.Card, Pal.Shimmer, Pal.Card), start = Offset(x * 500f, 0f), end = Offset(x * 500f + 500f, 500f)))
}

object Ic {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.White))
            .build()

    val Play = icon("Play", "M8,5v14l11,-7z")
    val Pause = icon("Pause", "M6,19h4V5H6v14zm8,-14v14h4V5h-4z")
    val Next = icon("Next", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    val Prev = icon("Prev", "M6,6h2v12H6zm3.5,6l8.5,6V6z")
    val Rewind = icon("Rewind", "M11,18V6l-8.5,6 8.5,6zm0.5,-6l8.5,6V6l-8.5,6z")
    val Forward = icon("Forward", "M4,18l8.5,-6L4,6v12zm9,-12v12l8.5,-6L13,6z")
    val Subtitles = icon("Subtitles", "M20,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM4,12h4v2H4v-2zm10,6H4v-2h10v2zm6,0h-4v-2h4v2zm0,-4H10v-2h10v2z")
    val Audio = icon("Audio", "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z")
    val Aspect = icon("Aspect", "M19,12h-2v3h-3v2h5v-5zM7,9h3V7H5v5h2V9zm14,-6H3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zm0,16.01H3V4.99h18v14.02z")
    val Back = icon("Back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    val Star = icon("Star", "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z")
    val StarOutline = icon("StarOutline", "M22,9.24l-7.19,-0.62L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21 12,17.27 18.18,21l-1.63,-7.03L22,9.24zM12,15.4l-3.76,2.27 1,-4.28 -3.32,-2.88 4.38,-0.38L12,6.1l1.71,4.04 4.38,0.38 -3.32,2.88 1,4.28L12,15.4z")
    val More = icon("More", "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zm0,2c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zm0,6c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z")
    // 1.68 player option bar
    val Eye = icon("Eye", "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zm0,-8c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z")
    val EyeOff = icon("EyeOff", "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75 -1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7zM2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3 4.38,-0.84l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27zM7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03 0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2zm4.31,-0.78l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z")
    val Channels = icon("Channels", "M3,13h2v-2H3v2zm0,4h2v-2H3v2zm0,-8h2V7H3v2zm4,4h14v-2H7v2zm0,4h14v-2H7v2zM7,7v2h14V7H7z")
    val Guide = icon("Guide", "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zm0.5,-13H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z")
    val Move = icon("Move", "M10,9h4V6h3l-5,-5 -5,5h3v3zm-1,1H6V7l-5,5 5,5v-3h3v-4zm14,2l-5,-5v3h-3v4h3v3l5,-5zm-9,3h-4v3H7l5,5 5,-5h-3v-3z")
    val Fullscreen = icon("Fullscreen", "M7,14H5v5h5v-2H7v-3zm-2,-4h2V7h3V5H5v5zm12,7h-3v2h5v-5h-2v3zM14,5v2h3v3h2V5h-5z")
    val FullscreenExit = icon("FullscreenExit", "M5,16h3v3h2v-5H5v2zm3,-8H5v2h5V5H8v3zm6,11h2v-3h3v-2h-5v5zm2,-11V5h-2v5h5V8h-3z")
}
