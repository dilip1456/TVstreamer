package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLoadTest {
    private fun s(
        kind: FirstLoad.Kind = FirstLoad.Kind.HOME,
        hasPortal: Boolean = true,
        hasChannels: Boolean = false,
        hasCatalog: Boolean = false,
        localReading: Boolean = false,
        liveFetching: Boolean = false,
        liveStatus: String = "",
        channelCount: Int = 0,
        catalogLoading: Boolean = false,
        catalogDone: Int = 0,
        catalogTotal: Int = 0,
        catalogSize: Int = 0,
        catalogStopped: Boolean = false,
        liveFailed: Boolean = false,
    ) = FirstLoad.of(
        kind, hasPortal, hasChannels, hasCatalog, localReading, liveFetching, liveStatus,
        channelCount, catalogLoading, catalogDone, catalogTotal, catalogSize, catalogStopped, liveFailed
    )

    @Test fun hiddenWhenPortalIsMissing() {
        assertFalse(s(hasPortal = false, liveFetching = true).visible)
    }

    @Test fun homeHiddenOnceCatalogHasData() {
        assertFalse(s(hasCatalog = true, catalogLoading = true).visible)
    }

    @Test fun liveHiddenOnceChannelsHaveArrived() {
        assertFalse(s(kind = FirstLoad.Kind.LIVE, hasChannels = true, liveFetching = true).visible)
    }

    @Test fun liveHiddenWhenDownloadFailed() {
        val st = s(kind = FirstLoad.Kind.LIVE, liveFailed = true, liveStatus = LiveStatus.FAILED + "timeout")
        assertFalse(st.visible)
    }

    @Test fun homeShowsChannelsTitleOnFirstLaunch() {
        val st = s(liveFetching = true, liveStatus = "Downloading channel list… (Signing in)")
        assertTrue(st.visible)
        assertEquals(FirstLoad.TITLE_CHANNELS, st.title)
        assertEquals(FirstLoad.FIRST_LOAD_HINT, st.message)
        assertNull(st.fraction)
        assertEquals("Downloading channel list… (Signing in)", st.detail)
        assertFalse(st.canStop)
    }

    @Test fun homeSwitchesToLibraryOnceChannelsAreIn() {
        val st = s(hasChannels = true, channelCount = 1200, catalogLoading = true, catalogDone = 3, catalogTotal = 12, catalogSize = 840)
        assertTrue(st.visible)
        assertEquals(FirstLoad.TITLE_LIBRARY, st.title)
        assertEquals(0.25f, st.fraction!!, 0.001f)
        assertEquals("Page 3 of 12 · 840 titles", st.detail)
        assertTrue(st.canStop)
    }

    @Test fun indeterminateRunningCountWhenTotalUnknown() {
        val st = s(hasChannels = true, catalogLoading = true, catalogSize = 1200)
        assertTrue(st.visible)
        assertNull(st.fraction)
        assertEquals("1,200 titles", st.detail)
    }

    @Test fun liveUsesChannelCountAndOpeningSaved() {
        val opening = s(kind = FirstLoad.Kind.LIVE, localReading = true)
        assertTrue(opening.visible)
        assertEquals(FirstLoad.TITLE_CHANNELS, opening.title)
        assertEquals("Opening saved channels…", opening.detail)
        val counted = s(kind = FirstLoad.Kind.LIVE, liveFetching = true, channelCount = 1200)
        assertEquals("1,200 channels", counted.detail)
        assertNull(counted.fraction)
    }

    @Test fun stopKeepsStateAndHidesTheButton() {
        val st = s(hasChannels = true, catalogStopped = true, catalogSize = 0)
        assertTrue(st.visible)
        assertEquals(FirstLoad.TITLE_STOPPED, st.title)
        assertTrue(st.stopped)
        assertFalse(st.canStop)
        assertTrue(st.message.contains("already loaded"))
    }

    @Test fun homeIdleWithEmptyCatalogAndNothingInFlightIsHidden() {
        // Languages / Catalog Selector empty states must not be replaced
        assertFalse(s(hasChannels = true, hasCatalog = false).visible)
    }

    @Test fun fractionIsNullWithoutATotalAndClamped() {
        assertNull(FirstLoad.fraction(3, 0))
        assertEquals(0f, FirstLoad.fraction(0, 10)!!, 0.001f)
        assertEquals(1f, FirstLoad.fraction(12, 10)!!, 0.001f)
        assertEquals(0.5f, FirstLoad.fraction(5, 10)!!, 0.001f)
    }

    @Test fun detailPrefersPageCountsThenTitlesThenChannels() {
        assertEquals("Page 3 of 12 · 1,200 titles", FirstLoad.detail(5000, 3, 12, 1200, false, "Downloading…", true, false))
        assertEquals("1,200 titles", FirstLoad.detail(50, 0, 0, 1200, false, "", true, false))
        assertEquals("5,000 channels", FirstLoad.detail(5000, 0, 0, 0, false, "Downloading…", false, false))
        assertEquals("Opening saved channels…", FirstLoad.detail(0, 0, 0, 0, true, "", false, false))
        assertEquals("Downloading channel list… (Channels)", FirstLoad.detail(0, 0, 0, 0, false, "Downloading channel list… (Channels)", false, false))
        assertEquals("Nothing saved yet", FirstLoad.detail(0, 0, 0, 0, false, "", false, true))
    }
}
