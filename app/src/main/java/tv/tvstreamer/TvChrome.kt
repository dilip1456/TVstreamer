package tv.tvstreamer

/**
 * TV chrome (1.99.5). The top bar still hides after you scroll down (Netflix-style).
 * The side menu is no longer a peeking rail that appears only then: it is either fully
 * hidden or fully open (see [SideMenu]), and Left can open it at any scroll position,
 * including from the top menu's first tab.
 */
object TvChrome {
    /** Scroll offset (px) before the page is considered to have left the top. */
    const val HIDE_OFFSET = 48

    /**
     * @param topBarFocused keep the top bar while it still has focus
     */
    fun topBarVisible(firstVisibleIndex: Int, firstVisibleOffset: Int, topBarFocused: Boolean = false,
                      threshold: Int = HIDE_OFFSET): Boolean {
        if (topBarFocused) return true
        return firstVisibleIndex <= 0 && firstVisibleOffset <= threshold
    }

    /** The side menu is available on every TV screen; it does not depend on the top bar. */
    fun sideMenuEnabled(tv: Boolean): Boolean = tv
}
