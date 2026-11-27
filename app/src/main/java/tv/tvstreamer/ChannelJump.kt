package tv.tvstreamer

/**
 * Number-pad jump: digits accumulate for [TIMEOUT_MS], then the typed number is matched
 * against channel numbers. Prefers an exact match in the current category, then in the
 * full list (caller should switch to All), then a prefix match the same way.
 */
object ChannelJump {
    const val TIMEOUT_MS = 1_500L
    const val MAX_DIGITS = 5

    fun digitOfCode(code: Long, name: String = ""): Int? {
        // Compose Key.Zero / Key.NumPad0 are Android key codes 7-16 and 144-153
        val n = when {
            name.startsWith("NumPad") -> name.removePrefix("NumPad").toIntOrNull()
            name.length == 1 && name[0] in '0'..'9' -> name[0] - '0'
            else -> null
        }
        if (n != null && n in 0..9) return n
        // KeyEvent.KEYCODE_0 = 7 … KEYCODE_9 = 16; KEYCODE_NUMPAD_0 = 144 … 153
        val c = code.toInt()
        return when (c) {
            in 7..16 -> c - 7
            in 144..153 -> c - 144
            else -> null
        }
    }

    fun append(buf: String, digit: Int): String {
        val d = digit.coerceIn(0, 9)
        val next = (buf + d).takeLast(MAX_DIGITS)
        return next.trimStart('0').ifEmpty { "0" }
    }

    data class Hit(val key: String, val switchToAll: Boolean)

    fun find(typed: String, current: List<LiveModes.Row>, all: List<LiveModes.Row>): Hit? {
        if (typed.isEmpty()) return null
        val n = typed.toIntOrNull() ?: return null
        fun exact(rows: List<LiveModes.Row>) = rows.firstOrNull { it.ch.num == n }
        fun prefix(rows: List<LiveModes.Row>) = rows.firstOrNull { it.ch.num > 0 && it.ch.num.toString().startsWith(typed) }
        exact(current)?.let { return Hit(it.ch.key, false) }
        exact(all)?.let { return Hit(it.ch.key, it.ch.key !in current.map { r -> r.ch.key }.toSet()) }
        prefix(current)?.let { return Hit(it.ch.key, false) }
        prefix(all)?.let { return Hit(it.ch.key, true) }
        return null
    }
}
