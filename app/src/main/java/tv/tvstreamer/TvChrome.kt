package tv.tvstreamer

/**
 * Netflix-style TV chrome (1.99.3). The top menu and the side rail are never on screen together:
 * at the top of the page the top bar is shown and the rail is hidden; once the user scrolls down
 * the top bar hides and the rail becomes available.
 */
object TvChrome {
    /** Scroll offset (px) before the page is considered to have left the top. */
    const val HIDE_OFFSET = 48

    /**
     * @param topBarFocused keep the top bar while it still has focus
     * @param railFocused keep the rail (and hide the top bar) while the user is in the side menu
     */
    fun topBarVisible(firstVisibleIndex: Int, firstVisibleOffset: Int, topBarFocused: Boolean = false,
                      railFocused: Boolean = false, threshold: Int = HIDE_OFFSET): Boolean {
        if (railFocused) return false
        if (topBarFocused) return true
        return firstVisibleIndex <= 0 && firstVisibleOffset <= threshold
    }

    /** The side rail is only offered on TV, and only while the top bar is hidden. */
    fun railAvailable(tv: Boolean, topBarVisible: Boolean): Boolean = tv && !topBarVisible
}
