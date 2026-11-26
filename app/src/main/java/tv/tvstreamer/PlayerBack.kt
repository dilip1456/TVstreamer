package tv.tvstreamer

/**
 * Back in the player (movies, series, live; TV and phone). Pure and unit-tested.
 *
 * A visible overlay (control bar, track/subtitle picker, channel list / schedule panel,
 * live info bar, "Up next" card) is dismissed first; playback keeps going. With nothing
 * open, the first Back shows a short "Press Back again to exit" hint; a second Back
 * within [EXIT_WINDOW_MS] leaves the player and returns to the screen it was opened from
 * (not the main menu). Key repeats / a held Back are ignored so a long press is not
 * read as a double Back. Elsewhere in the app, Back stays one-press.
 */
object PlayerBack {
    const val EXIT_WINDOW_MS = 2_000L
    /** Shorter than a deliberate second press, longer than a key-repeat burst from a held Back. */
    const val MIN_SECOND_MS = 250L
    const val HINT = "Press Back again to exit"

    enum class Action { HIDE_OVERLAY, HINT, EXIT, IGNORE }

    data class Result(val action: Action, val armedAt: Long)

    fun overlayOpen(
        controls: Boolean = false,
        dialog: Boolean = false,
        panel: Boolean = false,
        info: Boolean = false,
        upNext: Boolean = false,
    ): Boolean = controls || dialog || panel || info || upNext

    fun press(now: Long, overlay: Boolean, armedAt: Long, repeat: Boolean = false): Result {
        if (repeat) return Result(Action.IGNORE, armedAt)
        if (overlay) return Result(Action.HIDE_OVERLAY, 0L)
        if (armedAt > 0L) {
            val dt = now - armedAt
            if (dt < MIN_SECOND_MS) return Result(Action.IGNORE, armedAt)
            if (dt <= EXIT_WINDOW_MS) return Result(Action.EXIT, 0L)
        }
        return Result(Action.HINT, now)
    }
}
