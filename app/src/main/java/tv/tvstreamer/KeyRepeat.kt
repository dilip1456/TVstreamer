package tv.tvstreamer

import android.view.KeyEvent

/**
 * Holding a D-pad direction (1.95): remotes send key repeats every ~30-50 ms. Each one moved focus, scrolled a lazy
 * list and composed new rows / cards before the previous step had finished, which flooded the UI thread and
 * crashed the app (force close) on Fire TV. Repeats are now paced to one step per [MIN_GAP_MS]; the first press
 * and every key release always go through. Plain logic so it can be unit-tested.
 */
object KeyRepeat {
    /** At most one held-key step per this many ms (~9 steps a second: still a fast scroll). */
    const val MIN_GAP_MS = 110L

    private val DPAD = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END
    )

    fun isNav(code: Int): Boolean = code in DPAD

    private var lastCode = -1
    private var lastAt = Long.MIN_VALUE / 2

    /** True when this key-down should be dropped: a repeat of a navigation key that comes too soon after the last step. */
    @Synchronized
    fun drop(code: Int, repeatCount: Int, eventTime: Long): Boolean {
        if (!isNav(code)) return false
        if (repeatCount == 0 || code != lastCode) { lastCode = code; lastAt = eventTime; return false }
        if (eventTime - lastAt < MIN_GAP_MS) return true
        lastAt = eventTime
        return false
    }

    @Synchronized
    fun reset() { lastCode = -1; lastAt = Long.MIN_VALUE / 2 }
}
