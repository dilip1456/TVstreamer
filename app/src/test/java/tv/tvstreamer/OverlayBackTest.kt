package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class OverlayBackTest {
    @Test fun oneKeyDownClosesAndTheMatchingKeyUpIsSwallowed() {
        val down = OverlayBack.onKey(isDown = true, isUp = false, repeat = false, expectUp = false)
        assertTrue(down.close)
        assertTrue(down.consume)
        assertTrue(down.expectUp)
        val up = OverlayBack.onKey(isDown = false, isUp = true, repeat = false, expectUp = true)
        assertFalse(up.close)
        assertTrue(up.consume)
        assertFalse(up.expectUp)
    }

    @Test fun aStrayKeyUpDoesNotCloseAndIsNotSwallowed() {
        val up = OverlayBack.onKey(isDown = false, isUp = true, repeat = false, expectUp = false)
        assertFalse(up.close)
        assertFalse(up.consume)
    }

    @Test fun aHeldBackDoesNotCloseTwice() {
        val down = OverlayBack.onKey(isDown = true, isUp = false, repeat = false, expectUp = false)
        assertTrue(down.close)
        val repeat = OverlayBack.onKey(isDown = true, isUp = false, repeat = true, expectUp = true)
        assertFalse(repeat.close)
        assertTrue(repeat.consume)
        assertTrue(repeat.expectUp)
        val up = OverlayBack.onKey(isDown = false, isUp = true, repeat = false, expectUp = true)
        assertFalse(up.close)
        assertTrue(up.consume)
    }

    @Test fun backAndEscapeAreRecognized() {
        assertTrue(OverlayBack.isBackName("Back"))
        assertTrue(OverlayBack.isBackName("Escape"))
        assertFalse(OverlayBack.isBackName("DirectionLeft"))
    }

    @Test fun playerHidesAnOpenOverlayOnTheFirstBackThenNeedsASecondToLeave() {
        val hide = PlayerBack.press(1_000L, overlay = true, armedAt = 0L)
        assertEquals(PlayerBack.Action.HIDE_OVERLAY, hide.action)
        val hint = PlayerBack.press(1_200L, overlay = false, armedAt = 0L)
        assertEquals(PlayerBack.Action.HINT, hint.action)
        val exit = PlayerBack.press(1_200L + 400L, overlay = false, armedAt = hint.armedAt)
        assertEquals(PlayerBack.Action.EXIT, exit.action)
    }
}
