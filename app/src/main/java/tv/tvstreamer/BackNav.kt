package tv.tvstreamer

/**
 * One Back press pops only the top-most layer (pure, unit tested). Never "focus the tab bar first"
 * (that wasted a press and made leaving Live / Home / Movies take two Backs).
 *
 * Order, highest first: popup → search → select mode → player dialog / side panel / info / controls /
 * landscape → settings subsection → leave the screen.
 */
object BackNav {
    enum class Action {
        CLOSE_POPUP, CLOSE_SEARCH, EXIT_SELECT, CLOSE_PLAYER_DIALOG, CLOSE_PLAYER_PANEL,
        SETTINGS_UP, LEAVE_SCREEN, NONE
    }

    fun action(
        popup: Boolean = false,
        search: Boolean = false,
        select: Boolean = false,
        playerDialog: Boolean = false,
        playerPanel: Boolean = false,
        playerInfo: Boolean = false,
        playerControls: Boolean = false,
        playerLandscape: Boolean = false,
        settingsDetail: Boolean = false,
        canLeaveScreen: Boolean = false,
    ): Action = when {
        popup -> Action.CLOSE_POPUP
        search -> Action.CLOSE_SEARCH
        select -> Action.EXIT_SELECT
        playerDialog -> Action.CLOSE_PLAYER_DIALOG
        playerPanel -> Action.CLOSE_PLAYER_PANEL
        // 1.68: the live info bar, the player controls and phone fullscreen are NOT layers any more: one Back leaves the
        // player straight to the list it came from (they used to eat 1-3 presses first). [playerInfo], [playerControls]
        // and [playerLandscape] are kept as inputs so callers / tests can state that they make no difference.
        settingsDetail -> Action.SETTINGS_UP
        canLeaveScreen -> Action.LEAVE_SCREEN
        else -> Action.NONE
    }
}
