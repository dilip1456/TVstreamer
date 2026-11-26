package tv.tvstreamer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
 * Phoenix mark plus the swappable wordmark. To rename the app, change
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
            painterResource(R.drawable.ic_phoenix),
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

/** Compact in-place loader for empty catalog states. Pulses; does not block navigation. */
@Composable
fun PhoenixLoading(message: String, modifier: Modifier = Modifier) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val origin = withFrameMillis { it }
        while (true) elapsed = withFrameMillis { it } - origin
    }
    val glow = SplashAnim.loadingGlow(elapsed)
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
            Box(
                Modifier.size((36 + 12 * glow).dp).clip(CircleShape)
                    .background(Pal.Glow.copy(alpha = 0.16f + 0.24f * glow))
            )
            Image(
                painterResource(R.drawable.ic_phoenix),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(52.dp).graphicsLayer { alpha = 0.85f + 0.15f * glow },
                contentScale = ContentScale.Fit
            )
        }
        Text(message, color = Pal.Dim, fontSize = Ts.ROW.sp, modifier = Modifier.padding(top = 8.dp), maxLines = 3)
    }
}

/**
 * Cold-start overlay. Screens underneath (including Live TV) keep loading.
 * Ember → rise and flare → pulse if still waiting → fade once ready, capped at [SplashAnim.MAX_MS].
 */
@Composable
fun PhoenixSplash(ready: Boolean, onDone: () -> Unit) {
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
    val bird = if (tv) 200.dp else 132.dp
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = frame.scrimAlpha }.background(Color(0xFF0A0A0A)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(bird * 1.25f)) {
                Box(
                    Modifier
                        .size(bird * (0.16f + 0.20f * frame.emberScale))
                        .graphicsLayer { alpha = maxOf(frame.emberAlpha, frame.glowAlpha * 0.55f) }
                        .clip(CircleShape)
                        .background(Pal.Glow.copy(alpha = 0.7f))
                )
                Box(
                    Modifier
                        .size(bird * (0.70f + 0.20f * frame.glowAlpha))
                        .graphicsLayer { alpha = frame.glowAlpha * 0.40f }
                        .clip(CircleShape)
                        .background(Pal.Glow.copy(alpha = 0.35f))
                )
                Image(
                    painterResource(R.drawable.ic_phoenix),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier
                        .size(bird)
                        .offset(y = bird * 0.22f * frame.birdRise)
                        .graphicsLayer {
                            alpha = frame.birdAlpha
                            scaleX = frame.birdScale * frame.wingFlare
                            scaleY = frame.birdScale
                        },
                    contentScale = ContentScale.Fit
                )
            }
            Image(
                painterResource(R.drawable.ic_wordmark),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier
                    .padding(top = if (tv) 22.dp else 14.dp)
                    .height(if (tv) 36.dp else 24.dp)
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
