package tv.tvstreamer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.atomic.AtomicLong

/**
 * Bluey mark plus the swappable wordmark. To rename the app, change
 * [R.string.app_name] and [R.drawable.ic_wordmark] together.
 * Sized for a 24–32 dp top bar on TV and phone. Not focusable.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, height: Dp = 28.dp) {
    val name = stringResource(R.string.app_name)
    Row(
        modifier.height(height),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(height * 0.28f)
    ) {
        Image(
            painterResource(R.drawable.ic_bluey),
            contentDescription = null,
            modifier = Modifier.height(height).width(height),
            contentScale = ContentScale.Fit
        )
        Image(
            painterResource(R.drawable.ic_wordmark),
            contentDescription = name,
            modifier = Modifier.height(height * 0.52f).widthIn(max = height * 4.2f),
            contentScale = ContentScale.Fit
        )
    }
}

/** Soft round blue glow (radial fade to transparent) drawn behind the mark. No blur, so it is cheap on Fire TV. */
@Composable
private fun BrandGlow(size: Dp, strength: Float) {
    Box(
        Modifier.size(size).graphicsLayer { alpha = strength.coerceIn(0f, 1f) }.background(
            Brush.radialGradient(
                0f to Pal.Glow.copy(alpha = 0.62f),
                0.45f to Pal.Glow.copy(alpha = 0.26f),
                1f to Color.Transparent
            ),
            CircleShape
        )
    )
}

/** Compact in-place loader for empty catalog states: the mark with a pulsing blue glow. Does not block navigation. */
@Composable
fun BrandLoading(message: String, modifier: Modifier = Modifier) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val origin = withFrameMillis { it }
        while (true) elapsed = withFrameMillis { it } - origin
    }
    val glow = SplashAnim.loadingGlow(elapsed)
    val tv = rememberIsTv()
    val mark = if (tv) 56.dp else 48.dp
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(mark * 1.6f)) {
            BrandGlow(mark * (1.25f + 0.30f * glow), 0.45f + 0.55f * glow)
            Image(
                painterResource(R.drawable.ic_bluey),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(mark).graphicsLayer {
                    val s = 0.97f + 0.03f * glow
                    scaleX = s; scaleY = s
                },
                contentScale = ContentScale.Fit
            )
        }
        Text(message, color = Pal.Dim, fontSize = Ts.ROW.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 3)
    }
}

/**
 * Cold-start overlay. Screens underneath (including Live TV) keep loading.
 * Mark fades and settles in, the glow behind it pulses while waiting, then it fades once ready, capped at [SplashAnim.MAX_MS].
 */
@Composable
fun BrandSplash(ready: Boolean, onDone: () -> Unit) {
    var elapsed by remember { mutableLongStateOf(0L) }
    val readyAt = remember { AtomicLong(-1L) }
    LaunchedEffect(ready) {
        if (ready && readyAt.get() < 0L) readyAt.set(elapsed)
    }
    LaunchedEffect(Unit) {
        val origin = withFrameMillis { it }
        while (true) {
            val t = withFrameMillis { it } - origin
            elapsed = t
            val at = readyAt.get().let { if (it >= 0L) it else null }
            if (SplashAnim.frame(t, at).done) {
                onDone()
                break
            }
        }
    }
    val at = readyAt.get().let { if (it >= 0L) it else null }
    val frame = SplashAnim.frame(elapsed, at)
    val tv = rememberIsTv()
    val mark = if (tv) 200.dp else 132.dp
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = frame.scrimAlpha }.background(Color(Brand.BG)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(mark * 1.6f)) {
                BrandGlow(mark * 1.45f * frame.glowScale, frame.glowAlpha)
                Image(
                    painterResource(R.drawable.ic_bluey_hero),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier
                        .size(mark)
                        .graphicsLayer {
                            alpha = frame.markAlpha
                            scaleX = frame.markScale
                            scaleY = frame.markScale
                        },
                    contentScale = ContentScale.Fit
                )
            }
            Image(
                painterResource(R.drawable.ic_wordmark),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier
                    .padding(top = if (tv) 6.dp else 4.dp)
                    .height(if (tv) 40.dp else 28.dp)
                    .graphicsLayer { alpha = frame.wordmarkAlpha },
                contentScale = ContentScale.Fit
            )
        }
    }
}

/**
 * Shared empty / error state (1.99): a short title, one line of help and an optional action, left-aligned with the
 * page content. Used wherever a list has nothing to show, so every screen says it the same way.
 */
@Composable
fun EmptyState(title: String, message: String = "", modifier: Modifier = Modifier, warn: Boolean = false,
               action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.padding(vertical = Layout.L), verticalArrangement = Arrangement.spacedBy(Layout.S)) {
        Text(title, color = if (warn) Pal.Warn else Pal.Text, fontSize = Ts.TITLE.sp, maxLines = 2)
        if (message.isNotEmpty()) Text(message, color = Pal.Dim, fontSize = Ts.ROW.sp, maxLines = 4, modifier = Modifier.widthIn(max = 640.dp))
        if (action != null && onAction != null) Box(Modifier.padding(top = Layout.XS)) { PillButton(action, onAction, primary = true, size = Ts.HEAD) }
    }
}

/**
 * First-launch / empty-cache state (1.99.3): Bluey mark with a pulsing glow, a short explanation,
 * a real progress bar (determinate when a total is known) and the existing Stop button.
 */
@Composable
fun FirstLoadPanel(state: FirstLoad.State, onStop: () -> Unit, modifier: Modifier = Modifier, onResume: (() -> Unit)? = null) {
    if (!state.visible) return
    Column(modifier.padding(vertical = Layout.L), verticalArrangement = Arrangement.spacedBy(Layout.S)) {
        BrandLoading(state.title, Modifier)
        if (state.message.isNotEmpty()) Text(state.message, color = Pal.Dim, fontSize = Ts.ROW.sp, maxLines = 3, modifier = Modifier.widthIn(max = 640.dp))
        val frac = state.fraction
        if (frac != null) LinearProgressIndicator(
            progress = { frac },
            modifier = Modifier.fillMaxWidth(0.55f).height(4.dp),
            color = Pal.Primary,
            trackColor = Pal.Dim.copy(alpha = 0.3f)
        ) else LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(0.55f).height(4.dp),
            color = Pal.Primary,
            trackColor = Pal.Dim.copy(alpha = 0.3f)
        )
        if (state.detail.isNotEmpty()) Text(state.detail, color = Pal.Text2, fontSize = Ts.SECONDARY.sp, maxLines = 2)
        if (state.canStop) Box(Modifier.padding(top = Layout.XS)) { PillButton("Stop", onStop, primary = true, size = Ts.HEAD) }
        else if (state.stopped && onResume != null) Box(Modifier.padding(top = Layout.XS)) { PillButton("Resume", onResume, primary = true, size = Ts.HEAD) }
    }
}
