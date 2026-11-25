package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashAnimTest {
    @Test fun emberAppearsFirst() {
        val f = SplashAnim.frame(80, null)
        assertTrue("ember should be visible", f.emberAlpha > 0.2f)
        assertTrue("bird still hidden", f.birdAlpha < 0.4f)
        assertFalse(f.done)
        assertEquals(1f, f.scrimAlpha, 0.001f)
    }

    @Test fun birdRisesAndWingsFlare() {
        val mid = SplashAnim.frame((SplashAnim.EMBER_MS + SplashAnim.RISE_MS / 2).toLong(), null)
        val end = SplashAnim.frame(SplashAnim.RISE_END.toLong(), null)
        assertTrue(mid.birdAlpha > 0.4f)
        assertTrue(mid.birdRise > 0f && mid.birdRise < 1f)
        assertTrue(mid.wingFlare > 0.78f && mid.wingFlare < 1.01f)
        assertEquals(0f, end.birdRise, 0.02f)
        assertEquals(1f, end.wingFlare, 0.02f)
        assertEquals(1f, end.birdScale, 0.02f)
        assertTrue(end.wordmarkAlpha > 0.85f)
        assertFalse(end.done)
    }

    @Test fun readyDuringRiseStillFinishesTheRise() {
        // Became ready immediately: fade may not start before the rise ends.
        assertEquals(SplashAnim.RISE_END.toLong(), SplashAnim.fadeStartMs(0L))
        val during = SplashAnim.frame(400, 0L)
        assertFalse(during.done)
        assertEquals(1f, during.scrimAlpha, 0.001f)
        val afterFade = SplashAnim.frame((SplashAnim.RISE_END + SplashAnim.FADE_MS).toLong(), 0L)
        assertTrue(afterFade.done)
        assertEquals(0f, afterFade.scrimAlpha, 0.02f)
        assertTrue(afterFade.birdAlpha < 0.05f)
    }

    @Test fun pulseWhileWaitingThenFadesWhenReady() {
        val pulse = SplashAnim.frame(1000, null)
        assertFalse(pulse.done)
        assertTrue(pulse.glowAlpha > 0f)
        assertEquals(1f, pulse.scrimAlpha, 0.001f)
        val justReady = SplashAnim.frame(1200, 1200L)
        assertFalse(justReady.done)
        val faded = SplashAnim.frame(1200L + SplashAnim.FADE_MS, 1200L)
        assertTrue(faded.done)
        assertEquals(0f, faded.scrimAlpha, 0.02f)
    }

    @Test fun neverHoldsPastMax() {
        assertEquals(SplashAnim.MAX_MS.toLong(), SplashAnim.fadeStartMs(null))
        val cap = SplashAnim.frame((SplashAnim.MAX_MS + SplashAnim.FADE_MS).toLong(), null)
        assertTrue(cap.done)
        assertEquals(0f, cap.scrimAlpha, 0.02f)
    }

    @Test fun loadingGlowOscillates() {
        val a = SplashAnim.loadingGlow(0)
        val b = SplashAnim.loadingGlow(SplashAnim.PULSE_PERIOD / 4L)
        val c = SplashAnim.loadingGlow(SplashAnim.PULSE_PERIOD.toLong())
        assertEquals(0.5f, a, 0.02f)
        assertTrue(b > 0.8f)
        assertEquals(a, c, 0.02f)
    }
}
