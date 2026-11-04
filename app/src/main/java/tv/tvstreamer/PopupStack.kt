package tv.tvstreamer

/**
 * Open popups, topmost last (pure logic, unit tested). Each popup remembers what had focus when it opened (its opener)
 * and what got focus while it was on top. Closing the top popup returns its opener; a popup replaced by another one
 * (a menu page that opens a rename dialog) hands its own opener on, so focus still lands on the item that opened the
 * first menu.
 */
class PopupStack<T : Any> {
    private class Entry<T>(val id: Long, var opener: T?, val owned: MutableSet<T> = HashSet())
    private val list = ArrayList<Entry<T>>()
    private var seq = 0L

    val size get() = list.size
    val isEmpty get() = list.isEmpty()

    /** A popup opens; [lastFocused] is what has focus now. */
    @Synchronized fun open(lastFocused: T?): Long {
        val id = ++seq
        list += Entry(id, lastFocused)
        return id
    }

    /** [x] got focus: it belongs to the top popup (or the screen when none is open). */
    @Synchronized fun focused(x: T) { list.lastOrNull()?.owned?.add(x) }

    @Synchronized fun isTop(id: Long) = list.lastOrNull()?.id == id

    /**
     * Popup [id] closed. Returns what should get focus now: its opener when it was the top popup, else null (another
     * popup is on top and keeps focus). Popups opened from inside it inherit its opener.
     */
    @Synchronized fun close(id: Long): T? {
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return null
        val e = list.removeAt(i)
        for (o in list) if (o.opener != null && o.opener in e.owned) o.opener = e.opener
        return if (i == list.size) e.opener else null
    }
}
