package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class OverlayLookTest {
    @Test fun panelStaysInTheSoftRange() {
        assertEquals(0.84f, OverlayLook.PANEL_ALPHA, 0.001f)
        assertTrue(OverlayLook.panelAlpha() in 0.80f..0.88f)
        assertEquals(0.80f, OverlayLook.panelAlpha(0.5f), 0.001f)
        assertEquals(0.88f, OverlayLook.panelAlpha(1f), 0.001f)
    }

    @Test fun blurOnlyOnApi31WhenTheDeviceIsNotLowRam() {
        assertFalse(OverlayLook.useBlur(30, lowRam = false))
        assertTrue(OverlayLook.useBlur(31, lowRam = false))
        assertTrue(OverlayLook.useBlur(34, lowRam = false))
        assertFalse(OverlayLook.useBlur(31, lowRam = true))
        assertFalse(OverlayLook.useBlur(34, lowRam = true))
    }

    @Test fun channelInfoHidesAfterAboutThreeSeconds() {
        assertEquals(3_000L, OverlayLook.INFO_MS)
    }
}
