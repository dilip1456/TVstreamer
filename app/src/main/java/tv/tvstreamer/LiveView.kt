package tv.tvstreamer

/**
 * Where the user was in the Live TV list (1.53, pure logic, unit tested): All-channels view, scroll position and the
 * focused channel. Saved while browsing and when a channel is opened, so Back from the player (and an app restart)
 * lands on the same list, at the same place, on the playing channel. Mode and languages are saved by [Repo] already.
 *
 * Root cause of "Back from a channel shows a different view" (1.52): All channels was a screen-local flag, lost when the
 * player replaced the list, so Back rebuilt the list without hidden channels (a different list; the channel picked from
 * the hidden ones was not in it, so focus fell to the first channel). And the restore always re-scrolled to put the
 * playing channel 2 rows from the top, even when it was already on screen, so the list jumped.
 */
data class LiveView(val view: LiveModes.View = LiveModes.View.ACTIVE, val index: Int = 0, val offset: Int = 0, val focus: String = "") {
    fun encode(): String = listOf(when (view) { LiveModes.View.ACTIVE -> "0"; LiveModes.View.ALL -> "1"; LiveModes.View.HIDDEN -> "2" }, index, offset, focus).joinToString("|")

    companion object {
        fun decode(s: String?): LiveView {
            val p = s.orEmpty().split("|", limit = 4)
            if (p.size < 3) return LiveView()
            return LiveView(when (p[0]) { "1" -> LiveModes.View.ALL; "2" -> LiveModes.View.HIDDEN; else -> LiveModes.View.ACTIVE }, p[1].toIntOrNull()?.coerceAtLeast(0) ?: 0, p[2].toIntOrNull()?.coerceAtLeast(0) ?: 0, p.getOrElse(3) { "" })
        }

        /**
         * Scroll to restore: null = keep the saved position (the target row is already on screen, rows
         * [saved] .. [saved] + [window] - 1), else the index to scroll to so the row shows with a little context above.
         */
        fun scrollFor(saved: Int, target: Int?, window: Int = 6): Int? = when {
            target == null || target < 0 -> null
            target in saved until saved + window -> null
            else -> (target - 2).coerceAtLeast(0)
        }
    }
}
