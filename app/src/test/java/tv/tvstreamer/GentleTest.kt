package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class GentleTest {
    @Test fun limiterSpacesRequests() {
        var t = 1_000_000L
        val slept = mutableListOf<Long>()
        val l = RateLimiter(500, { t }, { slept += it; t += it })
        repeat(4) { l.acquire() }
        assertEquals(listOf(500L, 500L, 500L), slept)       // 4 requests take 1.5 s: about 2 a second
        t += 2_000
        l.acquire()
        assertEquals(3, slept.size)                         // after a quiet spell no wait
    }

    @Test fun limiterRefusesDuringPause() {
        var t = 0L
        val l = RateLimiter(500, { t }, { t += it })
        l.pause(60_000)
        try { l.acquire(); fail("should refuse") } catch (e: PortalBusyException) { assertEquals(60_000, e.waitMs) }
        t += 60_001
        l.acquire()
        assertFalse(l.paused)
    }

    @Test fun backoffDoublesAndCaps() {
        val b = Backoff(60_000, 30 * 60_000)
        assertEquals(listOf(60_000L, 120_000L, 240_000L, 480_000L, 960_000L, 1_800_000L, 1_800_000L), List(7) { b.next() })
        b.reset(); assertEquals(60_000L, b.next())
    }

    @Test fun slowDownCodes() {
        assertTrue(PortalHttpException(429, "").slowDown)
        assertTrue(PortalHttpException(403, "").slowDown)
        assertTrue(PortalHttpException(503, "").slowDown)
        assertFalse(PortalHttpException(404, "").slowDown)
    }

    @Test fun pageGapIsSmallAndRandom() {
        val r = Random(1)
        val g = List(200) { pageGap(r) }
        assertTrue(g.all { it in 300..700 })
        assertTrue(g.toSet().size > 50)
    }

    @Test fun refreshPolicy() {
        val h = RefreshPolicy.HOUR
        val p = RefreshPolicy()
        val t0 = 1_000L * h
        assertTrue(p.autoDue(t0))
        p.recordAuto(t0)
        assertFalse(p.autoDue(t0 + 23 * h)); assertTrue(p.autoDue(t0 + 24 * h))
        // Early refresh: not right after a refresh, at most 2 a day, 4 h apart
        assertFalse(p.earlyAllowed(t0 + h))
        assertTrue(p.earlyAllowed(t0 + 4 * h)); p.recordEarly(t0 + 4 * h)
        assertFalse(p.earlyAllowed(t0 + 6 * h))
        assertTrue(p.earlyAllowed(t0 + 8 * h)); p.recordEarly(t0 + 8 * h)
        assertFalse(p.earlyAllowed(t0 + 13 * h))             // third in 24 h
        // Manual: 10 minute cooldown
        assertEquals(0, p.manualWait(t0))
        p.recordManual(t0 + 20 * h)
        assertEquals(10 * RefreshPolicy.MINUTE, p.manualWait(t0 + 20 * h))
        assertEquals(0, p.manualWait(t0 + 20 * h + 10 * RefreshPolicy.MINUTE))
        // Survives a restart
        val q = RefreshPolicy.from(p.toJson())
        assertEquals(p.lastAuto, q.lastAuto); assertEquals(p.lastManual, q.lastManual); assertEquals(p.early, q.early)
    }
}
