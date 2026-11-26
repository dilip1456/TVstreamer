package tv.tvstreamer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue


/**
 * One Back press pops only the top-most layer (pure, unit tested).
 *
 * Order, highest first: exit confirm (Back cancels it once armed) → popup → search →
 * select mode → player dialog / side panel → settings subsection → leave a nested
 * screen → close the TV side rail → scroll to the top and focus the top menu →
 * "Exit Bluey?" confirm. The app never finishes from Back alone.
 *
 * On a main section (Home, Movies, TV Shows, Live TV) Back no longer leaves the tab
 * or the app in one press. Player Back is handled separately by [PlayerBack].
 */
object BackNav {
    enum class Action {
        CLOSE_POPUP, CLOSE_SEARCH, EXIT_SELECT, CLOSE_PLAYER_DIALOG, CLOSE_PLAYER_PANEL,
        SETTINGS_UP, CLOSE_RAIL, FOCUS_TOP, SHOW_EXIT, CANCEL_EXIT, LEAVE_SCREEN, NONE
    }

    /**
     * Ignore a Back that opened the confirm dialog (or a key-repeat burst) so that
     * press cannot cancel or confirm it. Same idea as [PlayerBack.MIN_SECOND_MS].
     */
    const val EXIT_ARM_MS = 250L

    fun isMainSection(home: Boolean = false, movies: Boolean = false, series: Boolean = false, live: Boolean = false): Boolean =
        home || movies || series || live

    /** TV: the next Back asks to exit only when the top menu already has focus. Phone: when the page is already at the top. */
    fun atChrome(tv: Boolean, topMenuFocused: Boolean, atTop: Boolean): Boolean =
        if (tv) topMenuFocused else atTop

    fun exitArmed(now: Long, openedAt: Long): Boolean =
        openedAt > 0L && now - openedAt >= EXIT_ARM_MS

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
        railFocused: Boolean = false,
        mainSection: Boolean = false,
        atChrome: Boolean = false,
        exitConfirmOpen: Boolean = false,
        now: Long = 0L,
        exitOpenedAt: Long = 0L,
    ): Action = when {
        exitConfirmOpen -> if (exitArmed(now, exitOpenedAt)) Action.CANCEL_EXIT else Action.NONE
        popup -> Action.CLOSE_POPUP
        search -> Action.CLOSE_SEARCH
        select -> Action.EXIT_SELECT
        playerDialog -> Action.CLOSE_PLAYER_DIALOG
        playerPanel -> Action.CLOSE_PLAYER_PANEL
        // 1.68: the live info bar, the player controls and phone fullscreen are NOT layers any more.
        // [playerInfo], [playerControls] and [playerLandscape] are kept as inputs so callers / tests
        // can state that they make no difference. The player itself uses [PlayerBack].
        settingsDetail -> Action.SETTINGS_UP
        canLeaveScreen -> Action.LEAVE_SCREEN
        railFocused -> Action.CLOSE_RAIL
        mainSection && !atChrome -> Action.FOCUS_TOP
        mainSection && atChrome -> Action.SHOW_EXIT
        else -> Action.NONE
    }
}

/** "Exit Bluey?" confirm. [show] from Back or from an Exit menu; only [finishApp] actually leaves. */
object AppExit {
    var open by mutableStateOf(false)
        private set
    var openedAt by mutableLongStateOf(0L)
        private set

    fun show() {
        open = true
        openedAt = android.os.SystemClock.elapsedRealtime()
    }

    fun cancel() {
        open = false
        openedAt = 0L
    }
}

/**
 * The current main-section screen (Home / Movies / TV / Live) reports its chrome so
 * [App] can handle Back without a full refresh. Cleared when that screen leaves composition.
 */
object MainBack {
    var isTv: Boolean = false
    var railFocused: Boolean = false
    var topMenuFocused: Boolean = false
    var atTop: Boolean = true
    var closeRail: () -> Unit = {}
    var focusTop: () -> Unit = {}
    val atChrome: Boolean get() = BackNav.atChrome(isTv, topMenuFocused, atTop)

    fun reset() {
        isTv = false
        railFocused = false
        topMenuFocused = false
        atTop = true
        closeRail = {}
        focusTop = {}
    }
}
