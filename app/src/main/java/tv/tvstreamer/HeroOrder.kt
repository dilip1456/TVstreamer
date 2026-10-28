package tv.tvstreamer

/** What the hero banner rotates through, and how it steps. Plain functions (unit-tested). */
object HeroOrder {
    /** Seconds between slides, and how long a key press in the banner pauses the rotation. */
    const val ROTATE_MS = 7_000L
    const val PAUSE_MS = 12_000L
    const val MAX = 12

    /**
     * Recently played first (newest first, so the last played item leads), then favourites; each title once
     * (by [key], e.g. episodes of one series share the series key), at most [max].
     */
    fun <T> build(history: List<T>, favs: List<T>, key: (T) -> String, max: Int = MAX): List<T> {
        val seen = HashSet<String>()
        val out = ArrayList<T>()
        for (x in history.asSequence() + favs.asSequence()) {
            if (out.size >= max) break
            if (seen.add(key(x))) out += x
        }
        return out
    }

    /** The next slide, wrapping around. */
    fun next(index: Int, size: Int): Int = if (size <= 0) 0 else (index + 1).mod(size)

    /** Rotate now? Only with more than one slide, and not within [PAUSE_MS] of the last key press in the banner. */
    fun shouldRotate(size: Int, now: Long, lastInteraction: Long): Boolean = size > 1 && now - lastInteraction >= PAUSE_MS
}
