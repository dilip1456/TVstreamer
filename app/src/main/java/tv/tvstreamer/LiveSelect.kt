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
            if (LiveModes.isOn(ch, mode, f, h) == on) continue
            val r = LiveModes.toggle(ch, mode, f, h); f = r.first; h = r.second
        }
        return f to h
    }

    fun summary(n: Int, on: Boolean, mode: String): String {
        val what = if (n == 1) "1 channel" else "$n channels"
        return if (mode == LiveModes.DEFAULT) (if (on) "Unhidden $what" else "Hidden $what")
            else (if (on) "Added $what to $mode" else "Removed $what from $mode")
    }
}

object DoublePress {
    const val WINDOW_MS = 300L

    /** Is a press at [now] on [key] the second of a double press after one at [lastAt] on [lastKey]? */
    fun isDouble(lastKey: String?, lastAt: Long, key: String, now: Long, window: Long = WINDOW_MS): Boolean =
        lastKey == key && now - lastAt in 0..window
}
