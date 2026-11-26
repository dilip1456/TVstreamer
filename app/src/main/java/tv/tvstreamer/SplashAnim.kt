package tv.tvstreamer

import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure timing for the Bluey splash and loader: the wave-and-tail mark fades and settles in, then a soft blue glow
 * behind it pulses on a [PULSE_PERIOD] loop until the app is ready, then everything fades.
 * No Compose, so it can be unit-tested. The overlay never holds the UI past [MAX_MS].
 */
object SplashAnim {
    /** Mark fade-in and settle (scale 0.86 → 1). */
    const val INTRO_MS = 600
    const val FADE_MS = 260
    const val MAX_MS = 1600
    /** One glow pulse (in and out), shared by the splash and the in-place loader. */
    const val PULSE_PERIOD = 1500

    data class Frame(
        val markAlpha: Float,
        val markScale: Float,
        /** 0..1 strength of the blue glow behind the mark (pulses while waiting). */
        val glowAlpha: Float,
        /** Size of the glow relative to the mark (breathes with the pulse). */
        val glowScale: Float,
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
        val fadeU = if (t >= fadeAt) u(t, fadeAt, fadeAt + FADE_MS) else 0f
        val done = t >= fadeAt + FADE_MS
        val keep = 1f - fadeU

        val intro = smooth(u(t, 0L, INTRO_MS.toLong()))
        val pulse = loadingGlow(t)
        val glowIn = smooth(u(t, INTRO_MS / 4L, INTRO_MS.toLong()))
        return Frame(
            markAlpha = intro * keep,
            markScale = lerp(0.86f, 1f, intro),
            glowAlpha = glowIn * lerp(0.45f, 1f, pulse) * keep,
            glowScale = lerp(0.92f, 1.10f, pulse),
            wordmarkAlpha = smooth(u(t, INTRO_MS / 2L, INTRO_MS + 150L)) * keep,
            scrimAlpha = keep,
            done = done,
        )
    }

    /** When the fade-out may start. The intro always finishes first; we never hold past [MAX_MS]. */
    fun fadeStartMs(readyAtMs: Long?): Long = when {
        readyAtMs == null -> MAX_MS.toLong()
        else -> maxOf(INTRO_MS.toLong(), minOf(readyAtMs, MAX_MS.toLong()))
    }

    /** Soft glow amount (0..1) on a [PULSE_PERIOD] loop: 0.5 at the start, peaks a quarter of the way in. */
    fun loadingGlow(elapsedMs: Long): Float {
        val x = (elapsedMs.coerceAtLeast(0L).toFloat() / PULSE_PERIOD) * 2f * PI.toFloat()
        return 0.5f + 0.5f * sin(x)
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
