package tv.tvstreamer

/**
 * D-pad rules for the TV top menu: Left / Right move across tabs and never fall into the page.
 * Only Down leaves the bar (to the hero / content). Up stays on the current tab.
 * Plain functions so the behaviour can be unit-tested without Compose.
 */
object TabNav {
    /** Returned from [move] when Down should leave the bar for the page content. */
    const val CONTENT = -1

    enum class Dir { LEFT, RIGHT, UP, DOWN, OTHER }

    fun dir(name: String): Dir = when (name) {
        "Left", "DirectionLeft" -> Dir.LEFT
        "Right", "DirectionRight" -> Dir.RIGHT
        "Up", "DirectionUp" -> Dir.UP
        "Down", "DirectionDown" -> Dir.DOWN
        else -> Dir.OTHER
    }

    /**
     * Next tab index after a D-pad press. [index] is the focused tab in `0 until count`.
     * Left on the first tab and Right on the last stay put (the key is consumed).
     * Down returns [CONTENT]. Other keys leave the index unchanged.
     */
    fun move(index: Int, count: Int, dir: Dir): Int {
        if (count <= 0) return CONTENT
        val i = index.coerceIn(0, count - 1)
        return when (dir) {
            Dir.LEFT -> if (i <= 0) i else i - 1
            Dir.RIGHT -> if (i >= count - 1) i else i + 1
            Dir.UP -> i
            Dir.DOWN -> CONTENT
            Dir.OTHER -> i
        }
    }

    /** True when Left on the first tab should open the side menu instead of staying put (only when there is one). */
    fun opensRail(index: Int, dir: Dir, hasRail: Boolean): Boolean = hasRail && dir == Dir.LEFT && index == 0

    /** True when the key must be consumed so focus cannot leave the tab bar (Left / Right / Up). */
    fun staysInBar(dir: Dir): Boolean = dir == Dir.LEFT || dir == Dir.RIGHT || dir == Dir.UP
}
