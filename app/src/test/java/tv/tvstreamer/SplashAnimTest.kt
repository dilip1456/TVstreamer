package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashAnimTest {
    @Test fun markFadesAndSettlesIn() {
        val start = SplashAnim.frame(0, null)
        assertTrue("mark starts hidden", start.markAlpha < 0.05f)
        assertEquals(1f, start.scrimAlpha, 0.001f)
        val mid = SplashAnim.frame(SplashAnim.INTRO_MS / 2L, null)
        assertTrue(mid.markAlpha > 0.3f && mid.markAlpha < 0.95f)
        assertTrue(mid.markScale > 0.86f && mid.markScale < 1f)
        val end = SplashAnim.frame(SplashAnim.INTRO_MS.toLong(), null)
        assertEquals(1f, end.markAlpha, 0.02f)
        assertEquals(1f, end.markScale, 0.02f)
        assertTrue(SplashAnim.frame(SplashAnim.INTRO_MS + 150L, null).wordmarkAlpha > 0.95f)
        assertFalse(end.done)
    }

    @Test fun readyDuringIntroStillFinishesTheIntro() {
        assertEquals(SplashAnim.INTRO_MS.toLong(), SplashAnim.fadeStartMs(0L))
        val during = SplashAnim.frame(300, 0L)
        assertFalse(during.done)
        assertEquals(1f, during.scrimAlpha, 0.001f)
        val afterFade = SplashAnim.frame((SplashAnim.INTRO_MS + SplashAnim.FADE_MS).toLong(), 0L)
        assertTrue(afterFade.done)
        assertEquals(0f, afterFade.scrimAlpha, 0.02f)
        assertTrue(afterFade.markAlpha < 0.05f)
    }

    @Test fun glowPulsesWhileWaitingThenFadesWhenReady() {
        val glows = (SplashAnim.INTRO_MS..SplashAnim.MAX_MS step 50).map { SplashAnim.frame(it.toLong(), null).glowAlpha }
        assertTrue("glow should breathe", glows.max() - glows.min() > 0.3f)
        assertTrue(glows.all { it > 0f })
        val justReady = SplashAnim.frame(1200, 1200L)
        assertFalse(justReady.done)
        val faded = SplashAnim.frame(1200L + SplashAnim.FADE_MS, 1200L)
        assertTrue(faded.done)
        assertEquals(0f, faded.scrimAlpha, 0.02f)
    }

    @Test fun neverHoldsPastMax() {
        assertEquals(SplashAnim.MAX_MS.toLong(), SplashAnim.fadeStartMs(null))
        assertEquals(SplashAnim.MAX_MS.toLong(), SplashAnim.fadeStartMs(5000L))
        val cap = SplashAnim.frame((SplashAnim.MAX_MS + SplashAnim.FADE_MS).toLong(), null)
        assertTrue(cap.done)
        assertEquals(0f, cap.scrimAlpha, 0.02f)
    }

    @Test fun loadingGlowLoopsEveryPeriod() {
        assertEquals(1500, SplashAnim.PULSE_PERIOD)
        val a = SplashAnim.loadingGlow(0)
        val b = SplashAnim.loadingGlow(SplashAnim.PULSE_PERIOD / 4L)
        val c = SplashAnim.loadingGlow(SplashAnim.PULSE_PERIOD.toLong())
        assertEquals(0.5f, a, 0.02f)
        assertTrue(b > 0.95f)
        assertTrue(SplashAnim.loadingGlow(SplashAnim.PULSE_PERIOD * 3L / 4L) < 0.05f)
        assertEquals(a, c, 0.02f)
    }
}
