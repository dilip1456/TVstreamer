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

    @Test fun onlyRealRateLimitsAreSlowDown() {
        assertTrue(PortalHttpException(429, "").slowDown)
        assertEquals(PortalVerdict.RATE_LIMIT, PortalVerdict.of(503, "120"))
        assertFalse(PortalHttpException(403, "").slowDown)          // expired token: sign in again instead
        assertFalse(PortalHttpException(401, "").slowDown)
        assertFalse(PortalHttpException(503, "").slowDown)          // server error without Retry-After
        assertFalse(PortalHttpException(500, "").slowDown)
        assertFalse(PortalHttpException(404, "").slowDown)
        assertEquals(PortalVerdict.AUTH, PortalVerdict.of(403))
        assertEquals(PortalVerdict.SERVER, PortalVerdict.of(502))
    }

    @Test fun gatePausesOnlyForRateLimitsOrRepeatedFailures() {
        val g = PortalGate(authLimit = 3, serverLimit = 3, shortMs = 30_000)
        assertEquals(120_000L, g.onError(429, "120"))                // Retry-After wins over the 1 min backoff
        g.onSuccess()
        assertEquals(60_000L, g.onError(429, null))
        g.onSuccess()
        // One 403 (expired token) or one 500 (a dead channel) never pauses the app
        assertEquals(0L, g.onError(403, null))
        assertEquals(0L, g.onError(500, null))
        assertEquals(0L, g.onError(404, null))
        g.onSuccess()
        // Refused again and again even with fresh tokens: a short pause, growing slowly
        assertEquals(listOf(0L, 0L, 30_000L, 60_000L), List(4) { g.onError(403, null) })
        g.onSuccess()
        assertEquals(listOf(0L, 0L, 30_000L), List(3) { g.onError(502, null) })
    }

    @Test fun signInAgainOnlyForTokenProblems() {
        assertTrue(needsNewToken(PortalHttpException(403, "")))
        assertTrue(needsNewToken(PortalHttpException(401, "")))
        assertTrue(needsNewToken(org.json.JSONException("Authorization failed.")))
        assertTrue(needsNewToken(java.io.IOException("unexpected end of stream")))
        assertFalse(needsNewToken(PortalHttpException(429, "")))
        assertFalse(needsNewToken(PortalHttpException(500, "")))
        assertFalse(needsNewToken(PortalBusyException(1000)))
        assertFalse(needsNewToken(java.net.SocketTimeoutException()))
    }

    @Test fun linkTriesStopOnPauseOrCancelOnly() {
        assertTrue(stopsLinkTries(PortalBusyException(1000)))
        assertTrue(stopsLinkTries(java.io.InterruptedIOException()))
        assertFalse(stopsLinkTries(java.net.SocketTimeoutException()))
        assertFalse(stopsLinkTries(PortalHttpException(500, "")))     // next strategy may work
    }

    @Test fun busyMessagesSayWhatHappened() {
        assertTrue(busyText(30_000, PortalVerdict.RATE_LIMIT, 429).contains("slow down (HTTP 429)"))
        assertTrue(busyText(30_000, PortalVerdict.RATE_LIMIT, 429).contains("30 s"))
        assertTrue(busyText(30_000, PortalVerdict.AUTH, 403).contains("refusing"))
        assertFalse(busyText(30_000, PortalVerdict.AUTH, 403).contains("slow down"))
        assertTrue(busyText(30_000, PortalVerdict.SERVER, 502).contains("server errors (HTTP 502)"))
        assertTrue(busyText(120_000).contains("about 2 min"))
        val l = RateLimiter(500, { 0L }, {})
        l.pause(30_000, PortalVerdict.SERVER, 503)
        assertEquals(PortalVerdict.SERVER, l.busy().reason)
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

    @Test fun ttlCacheExpiresAndEvicts() {
        var t = 0L
        val c = TtlCache<String, Int>(2, 1000, { t })
        c["a"] = 1; c["b"] = 2
        assertEquals(1, c["a"])
        c["c"] = 3                                     // "b" was used least recently
        assertEquals(null, c["b"])
        t = 1001
        assertEquals(null, c["a"])
    }
}
