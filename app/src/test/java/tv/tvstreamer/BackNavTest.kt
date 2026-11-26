package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun nestedScreensLeaveBeforeTheMainSection() {
        assertEquals(Action.LEAVE_SCREEN, BackNav.action(canLeaveScreen = true, mainSection = false))
        assertEquals(Action.CLOSE_POPUP, BackNav.action(popup = true, canLeaveScreen = true, mainSection = true, atChrome = true))
        assertEquals(Action.EXIT_SELECT, BackNav.action(select = true, mainSection = true, atChrome = true))
        assertEquals(Action.CLOSE_SEARCH, BackNav.action(search = true, mainSection = true, atChrome = true))
        assertEquals(Action.SETTINGS_UP, BackNav.action(settingsDetail = true, canLeaveScreen = true, mainSection = false))
    }

    @Test fun oneBackLeavesThePlayerEvenWithTheInfoBarOrFullscreen() {
        // PlayerBack owns in-player Back; BackNav still treats an open player as "leave the screen"
        // once overlays the user opened on purpose have closed.
        assertEquals(Action.LEAVE_SCREEN, BackNav.action(playerInfo = true, canLeaveScreen = true))
        assertEquals(Action.LEAVE_SCREEN, BackNav.action(playerLandscape = true, canLeaveScreen = true))
        assertEquals(Action.LEAVE_SCREEN, BackNav.action(playerControls = true, canLeaveScreen = true))
        assertEquals(Action.LEAVE_SCREEN, BackNav.action(canLeaveScreen = true))
        assertEquals(Action.CLOSE_PLAYER_PANEL, BackNav.action(playerPanel = true, playerInfo = true, canLeaveScreen = true))
        assertEquals(Action.CLOSE_PLAYER_DIALOG, BackNav.action(playerDialog = true, playerLandscape = true, canLeaveScreen = true))
    }

    @Test fun mainSectionsAreHomeMoviesTvAndLive() {
        assertTrue(BackNav.isMainSection(home = true))
        assertTrue(BackNav.isMainSection(movies = true))
        assertTrue(BackNav.isMainSection(series = true))
        assertTrue(BackNav.isMainSection(live = true))
        assertFalse(BackNav.isMainSection())
    }

    @Test fun chromeIsTheTopMenuOnTvAndTheTopOfThePageOnPhone() {
        assertTrue(BackNav.atChrome(tv = true, topMenuFocused = true, atTop = false))
        assertFalse(BackNav.atChrome(tv = true, topMenuFocused = false, atTop = true))
        assertTrue(BackNav.atChrome(tv = false, topMenuFocused = false, atTop = true))
        assertFalse(BackNav.atChrome(tv = false, topMenuFocused = true, atTop = false))
    }

    @Test fun backOnAMainSectionClosesTheRailThenFocusesTheTopMenu() {
        assertEquals(Action.CLOSE_RAIL, BackNav.action(mainSection = true, railFocused = true, atChrome = false))
        assertEquals(Action.CLOSE_RAIL, BackNav.action(mainSection = true, railFocused = true, atChrome = true))
        assertEquals(Action.FOCUS_TOP, BackNav.action(mainSection = true, atChrome = false))
        assertEquals(Action.SHOW_EXIT, BackNav.action(mainSection = true, atChrome = true))
        // A nested screen still leaves before any of this
        assertEquals(Action.LEAVE_SCREEN, BackNav.action(canLeaveScreen = true, mainSection = true, atChrome = true, railFocused = true))
    }

    @Test fun backOnTheTopMenuAsksToExitAndNeverFinishesByItself() {
        assertEquals(Action.SHOW_EXIT, BackNav.action(mainSection = true, atChrome = true))
        assertEquals(Action.NONE, BackNav.action())
    }

    @Test fun thePressThatOpenedTheExitDialogIsIgnoredThenBackCancelsIt() {
        val opened = 10_000L
        assertFalse(BackNav.exitArmed(opened, opened))
        assertFalse(BackNav.exitArmed(opened + BackNav.EXIT_ARM_MS - 1, opened))
        assertTrue(BackNav.exitArmed(opened + BackNav.EXIT_ARM_MS, opened))
        assertEquals(Action.NONE, BackNav.action(exitConfirmOpen = true, now = opened, exitOpenedAt = opened, mainSection = true, atChrome = true))
        assertEquals(Action.NONE, BackNav.action(exitConfirmOpen = true, now = opened + BackNav.EXIT_ARM_MS - 1, exitOpenedAt = opened))
        assertEquals(Action.CANCEL_EXIT, BackNav.action(exitConfirmOpen = true, now = opened + BackNav.EXIT_ARM_MS, exitOpenedAt = opened))
        // A rapid Back cannot confirm or skip the dialog
        assertEquals(Action.CANCEL_EXIT, BackNav.action(exitConfirmOpen = true, now = opened + 1_000L, exitOpenedAt = opened, mainSection = true, atChrome = true))
    }
}
