package tv.tvstreamer

/** Where focus goes when Search closes (1.55, pure). */
object SearchReturn {
    fun itemKey(screen: String) = "$screen#item"
    /** The poster / channel focused last on [screen] before Search, or null (then the Search tab keeps focus). */
    fun target(lastFocus: Map<String, String>, screen: String): String? = lastFocus[itemKey(screen)]
    /** Live TV: the channel focused last, else the one playing, else none. */
    fun liveTarget(focusKey: String, nowPlaying: String): String = focusKey.ifEmpty { nowPlaying }
}
