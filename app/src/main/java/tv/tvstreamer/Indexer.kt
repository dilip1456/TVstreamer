package tv.tvstreamer

/**
 * Full title index (1.71): walks every page of the selected-language Movies / Series categories in the background,
 * gently, until the whole list is on the device. Series are saved as show entries only (name, id, poster, year,
 * category, cast / director when sent); seasons and episodes load when a show is opened (see Seasons).
 *
 *  - One page at a time, through the portal limiter, with an extra pause between pages ([gap]).
 *  - Resumable: the next page per category ([next]) and finished categories ([ended]) are saved with the catalog,
 *    so it continues after a restart where it stopped.
 *  - Spread over sessions and days: at most [dayMax] index pages per local day (on top of the session cap).
 *  - Pauses (stops this run) when [canRun] says so: Stop pressed, portal asked to slow down, a video playing.
 *  - Only categories in [wanted] (the selected languages and categories, re-read every page): newly selected ones
 *    start, deselected ones simply stop being fetched (their saved titles stay, filtered out of the lists).
 *  - Categories never started go first (every category shows something soon), then the one with the fewest pages.
 */
class CatalogIndexer(
    val next: MutableMap<String, Int>,
    val ended: MutableSet<String>,
    val emptyRuns: MutableMap<String, Int>,
    private val dayMax: Int = DAY_PAGES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tzOffset: (Long) -> Long = { java.util.TimeZone.getDefault().getOffset(it).toLong() }
) {
    enum class Stop { DONE, PAUSED, DAY_LIMIT, FAILED }

    /** Pages fetched today (local day [day]); saved by the caller (see [encodeDay] / [decodeDay]). */
    @Volatile var day = 0L
    @Volatile var dayPages = 0
        private set
    @Volatile var running = false
        private set
    @Volatile var lastStop: Stop? = null
        private set

    private fun today(): Long = CatalogStale.localDay(clock(), tzOffset(clock()))
    private fun rollDay() { val d = today(); if (d != day) { day = d; dayPages = 0 } }
    fun dayLeft(): Int { rollDay(); return (dayMax - dayPages).coerceAtLeast(0) }

    /** Which category to fetch next, or null when every wanted category is complete. */
    fun pick(wanted: List<String>): String? {
        val open = wanted.filter { it !in ended }
        return open.firstOrNull { it !in next } ?: open.minByOrNull { next[it] ?: 1 }
    }

    /** Wanted categories fully indexed / all wanted categories. */
    fun progress(wanted: List<String>): Pair<Int, Int> = wanted.count { it in ended } to wanted.size

    /**
     * Index until done, paused, out of today's pages, or a page fails. [fetch] returns null when the page could not
     * be read (busy / not answering / session cap); [save] stores the page's titles and returns how many were new.
     */
    suspend fun run(
        wanted: () -> List<String>,
        canRun: () -> Boolean,
        fetch: suspend (cat: String, page: Int) -> Stalker.VodPage?,
        save: suspend (cat: String, items: List<Item>) -> Int,
        persist: () -> Unit = {},
        gap: suspend () -> Unit = {}
    ): Stop {
        running = true
        try {
            while (true) {
                if (!canRun()) return Stop.PAUSED.also { lastStop = it }
                if (dayLeft() <= 0) return Stop.DAY_LIMIT.also { lastStop = it }
                val c = pick(wanted()) ?: return Stop.DONE.also { lastStop = it }
                val p = next[c] ?: 1
                val pg = fetch(c, p) ?: return Stop.FAILED.also { lastStop = it }
                rollDay(); dayPages++
                val fresh = save(c, pg.items)
                next[c] = p + 1
                val empty = if (fresh == 0) (emptyRuns[c] ?: 0) + 1 else 0
                emptyRuns[c] = empty
                // Last page, an empty page, or the portal repeating pages (nothing new twice): this category is complete
                if (pg.items.isEmpty() || p >= pg.pages || Paging.endAfterEmpty(empty)) ended += c
                persist()
                gap()
            }
        } finally { running = false }
    }

    fun encodeDay(): String = "$day:$dayPages"
    fun decodeDay(s: String) {
        val parts = s.split(':')
        day = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        dayPages = parts.getOrNull(1)?.toIntOrNull() ?: 0
        rollDay()
    }

    companion object {
        /** Index pages per local day (about 14-50 titles each), across app sessions. */
        const val DAY_PAGES = 400
        /** Extra pause between index pages, on top of the limiter's gap (ms). */
        const val GAP_MIN = 1_500L
        const val GAP_MAX = 3_500L
    }
}
