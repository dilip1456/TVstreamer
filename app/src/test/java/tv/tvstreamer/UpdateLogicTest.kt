package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class UpdateLogicTest {
    @Test fun remoteCodeFromReleaseNameAndTag() {
        assertEquals(58L, UpdateLogic.remoteCode("TVStreamer 1.58 (code 58)", "v1.58"))
        assertEquals(36L, UpdateLogic.remoteCode("TVStreamer 1.36 (code 36)", "v1.36"))
        assertEquals(59L, UpdateLogic.remoteCode("", "v1.59"))
        assertEquals(7L, UpdateLogic.remoteCode("", "v7"))
        assertEquals(-1L, UpdateLogic.remoteCode("", ""))
    }

    @Test fun isNewerOnlyWhenRemoteCodeIsHigher() {
        assertFalse(UpdateLogic.isNewer(58, 58))
        assertFalse(UpdateLogic.isNewer(58, 57))
        assertFalse(UpdateLogic.isNewer(58, -1))
        assertTrue(UpdateLogic.isNewer(58, 59))
        assertTrue(UpdateLogic.isNewer(0, 1))
    }

    @Test fun askOnceOffersANewVersionAndSkipsADeclinedOneOnLaunch() {
        val m = UpdateLogic.Mode.ASK_ONCE
        assertEquals(UpdateLogic.Decision.UP_TO_DATE, UpdateLogic.decide(58, 58, 0, m, false))
        assertEquals(UpdateLogic.Decision.OFFER, UpdateLogic.decide(58, 59, 0, m, false))
        assertEquals(UpdateLogic.Decision.SKIP_DECLINED, UpdateLogic.decide(58, 59, 59, m, false))
        // Manual check may offer the declined version again
        assertEquals(UpdateLogic.Decision.OFFER, UpdateLogic.decide(58, 59, 59, m, true))
        // A still newer release is always offered
        assertEquals(UpdateLogic.Decision.OFFER, UpdateLogic.decide(58, 60, 59, m, false))
    }

    @Test fun autoInstallsWithoutAskingEvenIfPreviouslyDeclined() {
        assertEquals(UpdateLogic.Decision.AUTO_INSTALL, UpdateLogic.decide(58, 59, 59, UpdateLogic.Mode.AUTO, false))
        assertEquals(UpdateLogic.Decision.UP_TO_DATE, UpdateLogic.decide(59, 59, 0, UpdateLogic.Mode.AUTO, false))
    }

    @Test fun modeRoundTripAndProgress() {
        assertEquals(UpdateLogic.Mode.ASK_ONCE, UpdateLogic.Mode.parse(null))
        assertEquals(UpdateLogic.Mode.ASK_ONCE, UpdateLogic.Mode.parse("ask"))
        assertEquals(UpdateLogic.Mode.AUTO, UpdateLogic.Mode.parse("auto"))
        assertEquals("ask", UpdateLogic.Mode.encode(UpdateLogic.Mode.ASK_ONCE))
        assertEquals("auto", UpdateLogic.Mode.encode(UpdateLogic.Mode.AUTO))
        assertEquals(UpdateLogic.Mode.AUTO, UpdateLogic.Mode.ASK_ONCE.next())
        assertEquals(0, UpdateLogic.progress(0, 0))
        assertEquals(50, UpdateLogic.progress(50, 100))
        assertEquals(100, UpdateLogic.progress(200, 100))
    }
}
