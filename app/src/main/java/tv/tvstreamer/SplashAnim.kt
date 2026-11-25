package tv.tvstreamer

import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure timing for the Ashfire splash: ember → phoenix rise / wing flare → glow pulse → fade.
 * No Compose, so it can be unit-tested. The overlay never holds the UI past [MAX_MS].
 */
object SplashAnim {
    const val EMBER_MS = 160
    const val RISE_MS = 640
    const val RISE_END = EMBER_MS + RISE_MS // 800
    const val FADE_MS = 260
    const val MAX_MS = 1600
    const val PULSE_PERIOD = 900

    data class Frame(
        val emberAlpha: Float,
        val emberScale: Float,
        val birdAlpha: Float,
        val birdScale: Float,
        /** 1 = wings fully open; lower values tuck them in at the start of the rise. */
        val wingFlare: Float,
        /** 1 = still below rest; 0 = in place. */
        val birdRise: Float,
        val glowAlpha: Float,
        val wordmarkAlpha: Float,
        val scrimAlpha: Float,
        val done: Boolean,
    )

    /**
     * @param elapsedMs time since the splash was shown
     * @param readyAtMs elapsed time when the app became ready, or null if still loading
     */
    fun frame(elapsedMs: Long, readyAtMs: Long?): Frame {
        val t = elapsedMs.coerceAtLeast(0L)
        val fadeAt = fadeStartMs(readyAtMs)
        val fading = t >= fadeAt
        val fadeU = if (fading) u(t, fadeAt, fadeAt + FADE_MS) else 0f
        val done = t >= fadeAt + FADE_MS

        val emberIn = smooth(u(t, 0, EMBER_MS))
        val emberOut = smooth(u(t, EMBER_MS, EMBER_MS + RISE_MS / 2))
        val emberAlpha = (emberIn * (1f - emberOut) * (1f - fadeU))
        val emberScale = lerp(0.35f, 1.15f, emberIn)

        val riseU = smooth(u(t, EMBER_MS, RISE_END))
        val birdAlpha = smooth(u(t, EMBER_MS / 2, EMBER_MS + RISE_MS * 2 / 3)) * (1f - fadeU)
        val birdScale = lerp(0.55f, 1f, riseU)
        val wingFlare = lerp(0.78f, 1f, riseU)
        val birdRise = 1f - riseU

        val glowBase = smooth(u(t, EMBER_MS + RISE_MS / 3, RISE_END))
        val pulse = if (t >= RISE_END && !fading) 0.5f + 0.5f * sin(((t - RISE_END).toFloat() / PULSE_PERIOD) * 2f * PI.toFloat()).toFloat() else
            if (fading) 1f else 0f
        val glowAlpha = glowBase * lerp(0.35f, 0.85f, if (t >= RISE_END) pulse else 0f) * (1f - fadeU)

        val wordmarkAlpha = smooth(u(t, RISE_END - 280, RISE_END)) * (1f - fadeU)
        val scrimAlpha = 1f - fadeU

        return Frame(
            emberAlpha = emberAlpha,
            emberScale = emberScale,
            birdAlpha = birdAlpha,
            birdScale = birdScale,
            wingFlare = wingFlare,
            birdRise = birdRise,
            glowAlpha = glowAlpha,
            wordmarkAlpha = wordmarkAlpha,
            scrimAlpha = scrimAlpha,
            done = done,
        )
    }

    /** When the fade-out may start. The rise always finishes first; we never hold past [MAX_MS]. */
    fun fadeStartMs(readyAtMs: Long?): Long = when {
        readyAtMs == null -> MAX_MS.toLong()
        else -> maxOf(RISE_END.toLong(), readyAtMs)
    }

    /** Soft glow amount (0..1) for the compact in-place loader. */
    fun loadingGlow(elapsedMs: Long): Float {
        val x = (elapsedMs.coerceAtLeast(0L).toFloat() / PULSE_PERIOD) * 2f * PI.toFloat()
        return 0.5f + 0.5f * sin(x).toFloat()
    }

    private fun u(t: Long, start: Int, end: Int): Float {
        if (end <= start) return if (t >= end) 1f else 0f
        return ((t - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
    }

    private fun u(t: Long, start: Long, end: Long): Float {
        if (end <= start) return if (t >= end) 1f else 0f
        return ((t - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}
