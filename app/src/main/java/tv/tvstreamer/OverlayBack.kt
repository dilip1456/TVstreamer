package tv.tvstreamer

/**
 * One Back press fully closes an overlay (menu, dialog, side menu, search, number-jump box,
 * player controls / info bar). Close on KeyDown only; always consume the matching KeyUp so the
 * press cannot leak to the screen behind and cannot close two layers at once.
 *
 * Phone system Back (no key events) is handled separately by BackHandler.
 */
object OverlayBack {
    data class Decision(val close: Boolean, val consume: Boolean, val expectUp: Boolean)

    /**
     * [isDown] / [isUp] describe this key event, [repeat] is a held-key repeat,
     * [expectUp] is true when a previous KeyDown on this overlay already closed (or started to).
     */
    fun onKey(isDown: Boolean, isUp: Boolean, repeat: Boolean, expectUp: Boolean): Decision {
        if (!isDown && !isUp) return Decision(false, false, expectUp)
        if (repeat) return Decision(close = false, consume = true, expectUp = if (isDown) true else expectUp)
        if (isDown) return Decision(close = true, consume = true, expectUp = true)
        // KeyUp: consume only when we already handled the Down, and never close again
        return Decision(close = false, consume = expectUp, expectUp = false)
    }

    /** True for Back / Escape key names (Compose Key.keyCode is not needed in tests). */
    fun isBackName(name: String): Boolean = name == "Back" || name == "Escape"
}
