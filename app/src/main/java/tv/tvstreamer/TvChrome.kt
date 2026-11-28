package tv.tvstreamer

/**
 * TV chrome (1.99.7). The top bar is a thin, semi-transparent strip that stays
 * on screen (it is not scrolled away), so Up from the hero or the top of a page
 * can always reach it. The side menu stays a full overlay; Left opens it at any
 * scroll position, including from the first top-bar tab.
 */
object TvChrome {
    /** Kept for older call sites; the floating bar no longer hides after this offset. */
    const val HIDE_OFFSET = 48

    /**
     * The floating top bar is always shown. [topBarFocused] and scroll position
     * are accepted so existing call sites keep compiling.
     */
    fun topBarVisible(firstVisibleIndex: Int, firstVisibleOffset: Int, topBarFocused: Boolean = false,
                      threshold: Int = HIDE_OFFSET): Boolean {
        if (firstVisibleIndex < 0 && firstVisibleOffset < 0 && !topBarFocused) return false
        return true
    }

    /** The side menu is available on every TV screen; it does not depend on the top bar. */
    fun sideMenuEnabled(tv: Boolean): Boolean = tv
}
