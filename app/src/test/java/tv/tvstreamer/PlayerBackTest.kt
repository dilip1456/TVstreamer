package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.tvstreamer.PlayerBack.Action

class PlayerBackTest {
    private fun press(
        now: Long,
        overlay: Boolean = false,
        armedAt: Long = 0L,
        repeat: Boolean = false,
    ) = PlayerBack.press(now, overlay, armedAt, repeat)

    @Test fun overlayClosesFirstAndStaysInThePlayer() {
        for (flag in listOf(
            PlayerBack.overlayOpen(controls = true),
            PlayerBack.overlayOpen(dialog = true),
            PlayerBack.overlayOpen(panel = true),
            PlayerBack.overlayOpen(info = true),
            PlayerBack.overlayOpen(upNext = true),
        )) {
            assertTrue(flag)
            val r = press(1_000L, overlay = true, armedAt = 500L)
            assertEquals(Action.HIDE_OVERLAY, r.action)
            assertEquals(0L, r.armedAt)
        }
        assertFalse(PlayerBack.overlayOpen())
    }

    @Test fun firstBackWithNothingOpenShowsHintAndDoesNotExit() {
        val r = press(5_000L, overlay = false)
        assertEquals(Action.HINT, r.action)
        assertEquals(5_000L, r.armedAt)
        assertEquals("Press Back again to exit", PlayerBack.HINT)
        assertEquals(2_000L, PlayerBack.EXIT_WINDOW_MS)
    }

    @Test fun secondBackWithinTheWindowExitsToThePreviousScreen() {
        // Exit means leave the player for whoever opened it (s.back), not Home.
        val first = press(10_000L)
        assertEquals(Action.HINT, first.action)
        val second = press(10_000L + 800L, armedAt = first.armedAt)
        assertEquals(Action.EXIT, second.action)
        assertEquals(0L, second.armedAt)
    }

    @Test fun secondBackAfterTheWindowExpiredShowsTheHintAgain() {
        val first = press(10_000L)
        val late = press(10_000L + PlayerBack.EXIT_WINDOW_MS + 1, armedAt = first.armedAt)
        assertEquals(Action.HINT, late.action)
        assertEquals(10_000L + PlayerBack.EXIT_WINDOW_MS + 1, late.armedAt)
    }

    @Test fun heldBackRepeatsAreNotASecondPress() {
        val first = press(20_000L)
        // A long-press burst: repeats must neither exit nor re-arm
        var armed = first.armedAt
        for (t in 20_040L..21_200L step 40L) {
            val r = press(t, armedAt = armed, repeat = true)
            assertEquals("repeat at $t", Action.IGNORE, r.action)
            assertEquals(first.armedAt, r.armedAt)
            armed = r.armedAt
        }
        // Releasing and pressing again inside the window still exits
        val second = press(21_400L, armedAt = armed)
        assertEquals(Action.EXIT, second.action)
    }

    @Test fun aSecondEventTooSoonIsTreatedAsAHoldNotADoubleBack() {
        val first = press(30_000L)
        val chatter = press(30_000L + PlayerBack.MIN_SECOND_MS - 1, armedAt = first.armedAt)
        assertEquals(Action.IGNORE, chatter.action)
        assertEquals(first.armedAt, chatter.armedAt)
        val real = press(30_000L + PlayerBack.MIN_SECOND_MS, armedAt = first.armedAt)
        assertEquals(Action.EXIT, real.action)
    }

    @Test fun hidingAnOverlayDisarmsAPendingExit() {
        val armed = press(40_000L)
        val hide = press(40_500L, overlay = true, armedAt = armed.armedAt)
        assertEquals(Action.HIDE_OVERLAY, hide.action)
        assertEquals(0L, hide.armedAt)
        val again = press(40_800L, overlay = false, armedAt = hide.armedAt)
        assertEquals(Action.HINT, again.action)
    }
}
