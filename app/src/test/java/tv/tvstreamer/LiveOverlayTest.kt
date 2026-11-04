package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test
import tv.tvstreamer.LiveOverlay.Layer
import tv.tvstreamer.LiveOverlay.Pad
import tv.tvstreamer.LiveOverlay.Effect

class LiveOverlayTest {
    @Test fun upDownZapAtOnceAndShowInfo() {
        assertEquals(Layer.INFO to Effect.Zap(1), LiveOverlay.press(Layer.NONE, Pad.UP))
        assertEquals(Layer.INFO to Effect.Zap(-1), LiveOverlay.press(Layer.INFO, Pad.DOWN))
        assertEquals(Layer.NONE to Effect.None, LiveOverlay.press(Layer.NONE, Pad.UP, channels = 1))
    }

    @Test fun leftChannelsRightGuideOkInfo() {
        assertEquals(Layer.CHANNELS, LiveOverlay.press(Layer.NONE, Pad.LEFT).first)
        assertEquals(Layer.GUIDE, LiveOverlay.press(Layer.INFO, Pad.RIGHT).first)
        assertEquals(Layer.INFO to Effect.None, LiveOverlay.press(Layer.NONE, Pad.OK))
        assertEquals(Layer.NONE to Effect.None, LiveOverlay.press(Layer.INFO, Pad.OK))
        assertEquals(Effect.Retry, LiveOverlay.press(Layer.NONE, Pad.OK, hasError = true).second)
        assertEquals(Effect.Menu, LiveOverlay.press(Layer.NONE, Pad.MENU).second)
    }

    @Test fun backClosesTopOverlayThenLeaves() {
        assertEquals(Layer.NONE to Effect.None, LiveOverlay.press(Layer.GUIDE, Pad.BACK))
        assertEquals(Layer.NONE to Effect.None, LiveOverlay.press(Layer.CHANNELS, Pad.BACK))
        assertEquals(Layer.NONE to Effect.None, LiveOverlay.press(Layer.INFO, Pad.BACK))
        assertEquals(Layer.NONE to Effect.Exit, LiveOverlay.press(Layer.NONE, Pad.BACK))
    }

    @Test fun sidePanelsKeepTheirKeys() {
        assertEquals(Layer.CHANNELS to Effect.None, LiveOverlay.press(Layer.CHANNELS, Pad.UP))
        assertEquals(Layer.GUIDE to Effect.None, LiveOverlay.press(Layer.GUIDE, Pad.OK))
    }

    @Test fun categoryListKeepsOrderAndFallsBack() {
        fun ch(id: String, cat: String) = Item("itv", id, id, "", cat, 0, false)
        val l = listOf(ch("a", "1"), ch("b", "2"), ch("c", "1"), ch("d", "2"))
        assertEquals(listOf(1, 3) to 1, LiveOverlay.categoryList(l, 3))
        val lonely = listOf(ch("a", "1"), ch("b", "2"))
        assertEquals(listOf(0, 1) to 1, LiveOverlay.categoryList(lonely, 1))
    }
}
