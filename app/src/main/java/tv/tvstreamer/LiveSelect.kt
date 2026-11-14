package tv.tvstreamer

/**
 * Live TV bulk selection and the OK double-press (pure, unit-tested).
 *
 * Double press: the browse list waits [DoublePress.WINDOW_MS] after a single OK before it plays. Playing at once and
 * cancelling on the second press would flash the full-screen player and tear it down again (and spend a portal
 * link), which feels worse than a short wait.
 */
object LiveSelect {
    fun toggle(sel: Set<String>, key: String): Set<String> = if (key in sel) sel - key else sel + key

    /** "Select all in this section": adds them all; when they are all selected already, clears them instead. */
    fun selectAll(sel: Set<String>, keys: List<String>): Set<String> = if (keys.isNotEmpty() && sel.containsAll(keys)) sel - keys.toSet() else sel + keys

    /** Channels that change when the selection is set to [on] (shown / enabled in the mode, or hidden / disabled). */
    fun targets(rows: List<LiveModes.Row>, sel: Set<String>, on: Boolean): List<Item> =
        rows.filter { it.ch.key in sel && it.on != on }.map { it.ch }.distinctBy { it.key }

    /** Apply [on] to every channel in [chs] in [mode] (Default: hidden set; custom mode: its channel list). */
    fun apply(chs: List<Item>, on: Boolean, mode: String, folders: FolderSet, hidden: Set<String>): Pair<FolderSet, Set<String>> {
        var f = folders; var h = hidden
        for (ch in chs) {
            val r = LiveModes.setOn(ch, on, mode, f, h); f = r.first; h = r.second
        }
        return f to h
    }

    /**
     * Apply [on] to the selected rows. Uses each row's type / sub / region so a channel that is only hidden
     * because its group is hidden is actually shown (a show: exception), which [apply] on bare items cannot do.
     */
    fun applyRows(rows: List<LiveModes.Row>, sel: Set<String>, on: Boolean, mode: String,
                  folders: FolderSet, hidden: Set<String>): Pair<FolderSet, Set<String>> {
        var f = folders; var h = hidden
        for (r in rows.filter { it.ch.key in sel && it.on != on }.distinctBy { it.ch.key }) {
            val res = LiveModes.setOn(r.ch, on, mode, f, h, r.type, r.sub, r.region)
            f = res.first; h = res.second
        }
        return f to h
    }

    /**
     * Keys for "Select all": the focused section, or every section of the focused type,
     * or all keys in the current view when no section is focused.
     */
    fun selectScope(focusSec: String, sections: List<LiveModes.Section>, viewKeys: List<String>): List<String> {
        if (focusSec.isNotEmpty()) {
            sections.firstOrNull { it.key == focusSec }?.let { return it.rows.map { r -> r.ch.key } }
            val ofType = sections.filter { LiveCollapse.typeKey(it.type) == focusSec }
                .flatMap { s -> s.rows.map { it.ch.key } }
            if (ofType.isNotEmpty()) return ofType
        }
        return viewKeys
    }

    /** True when [scope] is the whole current view (not one section or type). */
    fun scopeIsView(scope: List<String>, viewKeys: List<String>): Boolean =
        scope.isNotEmpty() && scope.size == viewKeys.size && scope.toSet() == viewKeys.toSet()

    fun selectAllLabel(sel: Set<String>, scope: List<String>, wholeView: Boolean): String =
        if (scope.isNotEmpty() && sel.containsAll(scope)) {
            if (wholeView) "Clear all" else "Clear section"
        } else {
            if (wholeView) "Select all" else "Select section"
        }

    fun summary(n: Int, on: Boolean, mode: String): String {
        val what = if (n == 1) "1 channel" else "$n channels"
        return if (mode == LiveModes.DEFAULT) (if (on) "Showed $what" else "Hid $what")
            else (if (on) "Added $what to $mode" else "Removed $what from $mode")
    }
}

object DoublePress {
    const val WINDOW_MS = 300L

    /** Is a press at [now] on [key] the second of a double press after one at [lastAt] on [lastKey]? */
    fun isDouble(lastKey: String?, lastAt: Long, key: String, now: Long, window: Long = WINDOW_MS): Boolean =
        lastKey == key && now - lastAt in 0..window
}

/**
 * Phone-only: swipe a channel row left to reveal Hide / Show and commit past a threshold.
 * Offset is 0 at rest and negative as the row follows a left swipe. TV / D-pad does not use this.
 */
object LiveSwipe {
    const val REVEAL_DP = 84f
    const val COMMIT_FRAC = 0.55f

    fun clamp(offset: Float, reveal: Float): Float = offset.coerceIn(-reveal, 0f)

    fun shouldCommit(offset: Float, reveal: Float): Boolean = reveal > 0f && offset <= -reveal * COMMIT_FRAC
}
