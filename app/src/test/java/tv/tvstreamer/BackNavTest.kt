package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.tvstreamer.BackNav.Action

class BackNavTest {
    @Test fun onePressPopsOnlyTheTopLayer() {
        assertEquals(Action.CLOSE_POPUP, BackNav.action(popup = true, search = true, canLeaveScreen = true))
        assertEquals(Action.CLOSE_SEARCH, BackNav.action(search = true, select = true, canLeaveScreen = true))
        assertEquals(Action.EXIT_SELECT, BackNav.action(select = true, canLeaveScreen = true))
        assertEquals(Action.CLOSE_PLAYER_DIALOG, BackNav.action(playerDialog = true, playerInfo = true, canLeaveScreen = true))
        assertEquals(Action.CLOSE_PLAYER_PANEL, BackNav.action(playerPanel = true, playerInfo = true, canLeaveScreen = true))
        assertEquals(Action.CLOSE_PLAYER_INFO, BackNav.action(playerInfo = true, playerLandscape = true, canLeaveScreen = true))
        assertEquals(Action.HIDE_CONTROLS, BackNav.action(playerControls = true, playerLandscape = true, canLeaveScreen = true))
        assertEquals(Action.EXIT_FULLSCREEN, BackNav.action(playerLandscape = true, canLeaveScreen = true))
        assertEquals(Action.SETTINGS_UP, BackNav.action(settingsDetail = true, canLeaveScreen = true))
        assertEquals(Action.LEAVE_SCREEN, BackNav.action(canLeaveScreen = true))
        assertEquals(Action.NONE, BackNav.action())
    }

    @Test fun leavingATabIsOnePressNotFocusTheMenuFirst() {
        // Live / Movies / Series / Home: no intermediate "focus top tabs" layer
        assertEquals(Action.LEAVE_SCREEN, BackNav.action(canLeaveScreen = true))
        assertEquals(Action.CLOSE_POPUP, BackNav.action(popup = true, canLeaveScreen = true))
        assertEquals(Action.EXIT_SELECT, BackNav.action(select = true, canLeaveScreen = true))
    }

    @Test fun playerStackUnwindsOneLayerAtATime() {
        assertEquals(Action.CLOSE_PLAYER_INFO, BackNav.action(playerInfo = true, canLeaveScreen = true))
        assertEquals(Action.EXIT_FULLSCREEN, BackNav.action(playerLandscape = true, canLeaveScreen = true))
        assertEquals(Action.LEAVE_SCREEN, BackNav.action(canLeaveScreen = true))
    }
}
