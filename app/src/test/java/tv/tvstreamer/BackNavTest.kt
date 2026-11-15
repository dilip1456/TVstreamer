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
        assertEquals(Action.LEAVE_SCREEN, BackNav.action(playerInfo = true, playerLandscape = true, canLeaveScreen = true))
        assertEquals(Action.LEAVE_SCREEN, BackNav.action(playerControls = true, playerLandscape = true, canLeaveScreen = true))
        assertEquals(Action.LEAVE_SCREEN, BackNav.action(playerLandscape = true, canLeaveScreen = true))
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

    @Test fun oneBackLeavesThePlayerEvenWithTheInfoBarOrFullscreen() {
        // 1.68: Live TV player -> one Back -> the Live TV list (no hidden info-bar / fullscreen / controls step)
        assertEquals(Action.LEAVE_SCREEN, BackNav.action(playerInfo = true, canLeaveScreen = true))
        assertEquals(Action.LEAVE_SCREEN, BackNav.action(playerLandscape = true, canLeaveScreen = true))
        assertEquals(Action.LEAVE_SCREEN, BackNav.action(playerControls = true, canLeaveScreen = true))
        assertEquals(Action.LEAVE_SCREEN, BackNav.action(canLeaveScreen = true))
        // Things the user opened on purpose still close first, in one press
        assertEquals(Action.CLOSE_PLAYER_PANEL, BackNav.action(playerPanel = true, playerInfo = true, canLeaveScreen = true))
        assertEquals(Action.CLOSE_PLAYER_DIALOG, BackNav.action(playerDialog = true, playerLandscape = true, canLeaveScreen = true))
    }
}
