package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.tvstreamer.PlayerPad.Action

class PlayerPadTest {
    @Test fun upDownOnMoviesAndEpisodesShowControlsAndDoNotChangeItem() {
        assertEquals(Action.SHOW_CONTROLS, PlayerPad.hiddenVertical(live = false, up = true))
        assertEquals(Action.SHOW_CONTROLS, PlayerPad.hiddenVertical(live = false, up = false))
    }

    @Test fun upDownOnLiveTvStillZapChannels() {
        assertEquals(Action.ZAP_UP, PlayerPad.hiddenVertical(live = true, up = true))
        assertEquals(Action.ZAP_DOWN, PlayerPad.hiddenVertical(live = true, up = false))
    }
}
