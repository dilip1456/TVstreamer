package tv.tvstreamer

/**
 * After an item leaves a list (hide, unfavourite, remove), focus the next item, or the previous
 * one if it was last. Never jumps to the top and never returns a key that has also been removed.
 */
object FocusStay {
    /** The key to focus after [key] leaves [keys], or null if nothing remains. */
    fun afterRemove(keys: List<String>, key: String): String? {
        val i = keys.indexOf(key)
        if (i < 0) return null
        return keys.getOrNull(i + 1) ?: keys.getOrNull(i - 1)
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
}
