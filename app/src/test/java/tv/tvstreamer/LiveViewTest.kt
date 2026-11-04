package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class LiveViewTest {
    @Test fun roundTrip() {
        val v = LiveView(true, 42, 17, "itv:9|x")
        assertEquals(v, LiveView.decode(v.encode()))
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
