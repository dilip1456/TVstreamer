package tv.tvstreamer

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KeyRepeatTest {
    @Before fun setUp() = KeyRepeat.reset()

    private val dirs = listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

    @Test fun firstPressAlwaysPasses() {
        for (d in dirs) assertFalse(KeyRepeat.drop(d, 0, 1000))
        assertFalse(KeyRepeat.drop(KeyEvent.KEYCODE_DPAD_DOWN, 0, 1001))
    }

    @Test fun heldKeyIsPacedInEveryDirection() {
        for (d in dirs) {
            KeyRepeat.reset()
            // Fire TV style: first down, then a repeat every 40 ms for 2 s
            var passed = 0
            if (!KeyRepeat.drop(d, 0, 0)) passed++
            var t = 400L
            var rc = 1
            while (t <= 2400) { if (!KeyRepeat.drop(d, rc++, t)) passed++; t += 40 }
            // ~2 s of repeats at one step per MIN_GAP_MS (+ the first press)
            val max = 2 + (2000 / KeyRepeat.MIN_GAP_MS).toInt()
            assertTrue("dir $d passed $passed", passed in 10..max)
        }
    }

    @Test fun slowRepeatsAllPass() {
        assertFalse(KeyRepeat.drop(KeyEvent.KEYCODE_DPAD_LEFT, 0, 0))
        var t = 500L
        for (rc in 1..10) { assertFalse(KeyRepeat.drop(KeyEvent.KEYCODE_DPAD_LEFT, rc, t)); t += KeyRepeat.MIN_GAP_MS }
    }

    @Test fun otherKeysUntouched() {
        assertFalse(KeyRepeat.drop(KeyEvent.KEYCODE_DPAD_CENTER, 5, 10))
        assertFalse(KeyRepeat.drop(KeyEvent.KEYCODE_DPAD_CENTER, 6, 11))
        assertFalse(KeyRepeat.drop(KeyEvent.KEYCODE_BACK, 3, 12))
        assertEquals(false, KeyRepeat.isNav(KeyEvent.KEYCODE_MENU))
    }

    @Test fun switchingDirectionPassesAtOnce() {
        assertFalse(KeyRepeat.drop(KeyEvent.KEYCODE_DPAD_DOWN, 0, 0))
        assertFalse(KeyRepeat.drop(KeyEvent.KEYCODE_DPAD_DOWN, 1, 400))
        assertTrue(KeyRepeat.drop(KeyEvent.KEYCODE_DPAD_DOWN, 2, 440))
        assertFalse(KeyRepeat.drop(KeyEvent.KEYCODE_DPAD_RIGHT, 0, 450))
    }
}
