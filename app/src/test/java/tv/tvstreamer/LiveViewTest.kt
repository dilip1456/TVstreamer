package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class LiveViewTest {
    @Test fun roundTrip() {
        val v = LiveView(LiveModes.View.ALL, 42, 17, "itv:9|x")
        assertEquals(v, LiveView.decode(v.encode()))
        val h = LiveView(LiveModes.View.HIDDEN, 3, 0, "itv:1")
        assertEquals(h, LiveView.decode(h.encode()))
        // 1.53 saved "1" for All channels: still read as All channels after the update
        assertEquals(LiveModes.View.ALL, LiveView.decode("1|5|0|itv:2").view)
        assertEquals(LiveModes.View.ACTIVE, LiveView.decode("0|5|0|").view)
        assertEquals(LiveView(), LiveView.decode(""))
        assertEquals(LiveView(), LiveView.decode(null))
        assertEquals(LiveView(), LiveView.decode("garbage"))
    }

    @Test fun keepsScrollWhenChannelIsOnScreen() {
        assertNull(LiveView.scrollFor(saved = 40, target = 43))
        assertNull(LiveView.scrollFor(40, null))
        assertEquals(98, LiveView.scrollFor(40, 100))
        assertEquals(0, LiveView.scrollFor(40, 1))
    }
}
