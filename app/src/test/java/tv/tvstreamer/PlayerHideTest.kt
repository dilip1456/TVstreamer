package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test
import tv.tvstreamer.LiveOverlay.Layer
import tv.tvstreamer.LiveOverlay.Pad
import tv.tvstreamer.LiveOverlay.Effect

class PlayerHideTest {
    private fun ch(id: String) = Item("itv", id, "Ch $id", "", "1", id.toIntOrNull() ?: 0, false)

    @Test fun onlyHideKeepsTheOverlayOpen() {
        assertFalse(PlayerHide.keepOverlay(PlayerHide.Change.ZAP))
        assertFalse(PlayerHide.keepOverlay(PlayerHide.Change.PICK))
        assertFalse(PlayerHide.keepOverlay(PlayerHide.Change.NUMBER))
        assertTrue(PlayerHide.keepOverlay(PlayerHide.Change.HIDE))
    }

    @Test fun hideSkipsToTheNextChannelAndDropsTheHiddenOne() {
        val list = listOf(ch("1"), ch("2"), ch("3"))
        val mid = PlayerHide.afterHide(list, 1)!!
        assertEquals(listOf("1", "3"), mid.first.map { it.id })
        assertEquals(1, mid.second)   // "3" slides into the same index
        val first = PlayerHide.afterHide(list, 0)!!
        assertEquals(listOf("2", "3"), first.first.map { it.id })
        assertEquals(0, first.second)
        val last = PlayerHide.afterHide(list, 2)!!
        assertEquals(listOf("1", "2"), last.first.map { it.id })
        assertEquals(1, last.second)
    }

    @Test fun hideOnTheOnlyChannelLeavesNothingToPlay() {
        assertNull(PlayerHide.afterHide(listOf(ch("1")), 0))
        assertNull(PlayerHide.afterHide(emptyList(), 0))
        assertNull(PlayerHide.afterHide(listOf(ch("1")), 3))
    }

    @Test fun afterHideTheTimeoutRunsEvenWithFocusOnHide() {
        assertTrue(PlayerHide.timeoutWhileFocused(hideHold = true, infoFocused = true))
        assertTrue(PlayerHide.timeoutWhileFocused(hideHold = true, infoFocused = false))
        assertFalse(PlayerHide.timeoutWhileFocused(hideHold = false, infoFocused = true))
        assertTrue(PlayerHide.timeoutWhileFocused(hideHold = false, infoFocused = false))
    }

    @Test fun zapShowsTheInfoBarThenTheTimeoutHidesIt() {
        assertEquals(Layer.INFO to Effect.Zap(1), LiveOverlay.press(Layer.NONE, Pad.UP))
        assertEquals(Layer.INFO to Effect.Zap(-1), LiveOverlay.press(Layer.INFO, Pad.DOWN))
        assertEquals(Layer.INFO to Effect.Zap(1), LiveOverlay.press(Layer.INFO, Pad.UP))
        assertEquals(Layer.NONE to Effect.None, LiveOverlay.press(Layer.NONE, Pad.UP, channels = 1))
        assertEquals(3_000L, OverlayLook.INFO_MS)
        assertEquals(OverlayLook.INFO_MS, LiveOverlay.INFO_MS)
    }

    @Test fun backOnTheInfoBarHidesItAndDoesNotLeaveThePlayer() {
        assertEquals(Layer.NONE to Effect.None, LiveOverlay.press(Layer.INFO, Pad.BACK))
        assertEquals(Layer.NONE to Effect.None, LiveOverlay.press(Layer.GUIDE, Pad.BACK))
        assertEquals(Layer.NONE to Effect.None, LiveOverlay.press(Layer.CHANNELS, Pad.BACK))
        assertEquals(Layer.NONE to Effect.Exit, LiveOverlay.press(Layer.NONE, Pad.BACK))
    }
}
