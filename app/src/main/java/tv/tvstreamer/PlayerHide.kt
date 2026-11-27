package tv.tvstreamer

/**
 * Player overlay after a channel change or a Hide from the info bar (pure, unit tested).
 *
 * A normal change (zap, Ch+/−, number jump, pick from the overlay list) closes the overlay.
 * Hide removes the playing channel from the current list, plays the next one (or the previous
 * if it was last), and keeps the info bar open with focus on Hide so the next OK hides again.
 * The info bar then auto-hides on its normal timeout, even while Hide still has focus.
 */
object PlayerHide {
    enum class Change { ZAP, PICK, NUMBER, HIDE }

    /** Keep the info overlay open only when the change came from Hide. */
    fun keepOverlay(change: Change): Boolean = change == Change.HIDE

    /**
     * The list and index to play after hiding the item at [index].
     * Null when the list would be empty (caller should just hide and leave playback as-is,
     * or close the player).
     */
    fun afterHide(list: List<Item>, index: Int): Pair<List<Item>, Int>? {
        if (index !in list.indices) return null
        val next = list.filterIndexed { i, _ -> i != index }
        if (next.isEmpty()) return null
        val i = if (index < next.size) index else next.lastIndex
        return next to i
    }

    /** After Hide, the info bar should time out even if the Hide button still has focus. */
    fun timeoutWhileFocused(hideHold: Boolean, infoFocused: Boolean): Boolean =
        hideHold || !infoFocused
}
