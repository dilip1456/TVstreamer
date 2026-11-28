package tv.tvstreamer

/**
 * After an item leaves a list (hide, unfavourite, remove), focus the next item, or the previous
 * one if it was last. Never jumps to the top and never returns a key that has also been removed.
 * The next item lands at the same index as the one that left, so the cursor stays put.
 */
object FocusStay {
    /** The remaining item that should have focus, and the index it occupies after [key] leaves. */
    data class Hop(val key: String, val index: Int)

    /** The key to focus after [key] leaves [keys], or null if nothing remains. */
    fun afterRemove(keys: List<String>, key: String): String? = hopAfterRemove(keys, key)?.key

    /**
     * After [key] leaves [keys], stay at the same index (the next item slides into it).
     * Previous only if it was last. Null if nothing remains or [key] was not in the list.
     */
    fun hopAfterRemove(keys: List<String>, key: String): Hop? {
        val i = keys.indexOf(key)
        if (i < 0) return null
        val left = keys.filterIndexed { idx, _ -> idx != i }
        if (left.isEmpty()) return null
        val ni = i.coerceAtMost(left.lastIndex)
        return Hop(left[ni], ni)
    }

    /**
     * After removing [removed] from [keys], keep [focus] if it is still there; otherwise the
     * next remaining key after the focused (or first removed) item, else the previous one.
     */
    fun afterRemoveMany(keys: List<String>, removed: Set<String>, focus: String = ""): String? {
        if (removed.isEmpty()) return focus.takeIf { it in keys } ?: keys.firstOrNull()
        if (focus.isNotEmpty() && focus !in removed && focus in keys) return focus
        val anchor = keys.indexOf(focus).takeIf { it >= 0 }
            ?: keys.indexOfFirst { it in removed }.takeIf { it >= 0 }
            ?: return keys.firstOrNull { it !in removed }
        for (i in anchor + 1 until keys.size) if (keys[i] !in removed) return keys[i]
        for (i in anchor - 1 downTo 0) if (keys[i] !in removed) return keys[i]
        return null
    }

    /** True once [want] is in the composed list, so focus can be requested after layout commits. */
    fun readyToFocus(keys: List<String>, want: String?): Boolean =
        !want.isNullOrEmpty() && want in keys
}
