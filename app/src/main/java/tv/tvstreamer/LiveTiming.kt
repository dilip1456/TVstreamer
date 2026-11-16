package tv.tvstreamer

/** Debug timing of Live TV (1.70): `adb logcat -s TVstreamer-Live` shows how long the saved list and the screen took. */
object LiveTiming {
    private const val TAG = "TVstreamer-Live"
    @Volatile private var opens = 0

    private fun log(msg: String) { runCatching { android.util.Log.d(TAG, msg) } }

    /** Saved channel list read from the device and shown (once per app start). */
    fun loaded(ms: Long, channels: Int) = log("saved list loaded in $ms ms ($channels channels, no portal)")

    /** 1.73: where start-up time goes: file read, JSON parse, display names, saved sort, until channels are in UI state. */
    fun startup(readMs: Long, parseMs: Long, namesMs: Long, indexMs: Long, totalMs: Long, channels: Int, sortedFromDisk: Boolean) =
        log("start: file read $readMs ms, parse $parseMs ms, names $namesMs ms, saved sort $indexMs ms " +
            "(${if (sortedFromDisk) "from disk" else "not saved: sorting in background"}), first emit after $totalMs ms ($channels channels)")

    /** 1.73: one Live TV list layout (mode / view) worked out off the main thread. */
    fun layout(ms: Long, mode: String, view: String, rows: Int, why: String) =
        log("layout ${mode.ifEmpty { "Default" }} / $view: $ms ms ($rows rows, $why)")

    /** 1.73: the channel list was downloaded because nothing was saved (or a refresh). */
    fun fetched(ms: Long, channels: Int) = log("channel list downloaded in $ms ms ($channels channels)")

    /** Live TV opened and the list laid out: the first open after start is "cold", later ones "warm". */
    fun shown(ms: Long, rows: Int) {
        val kind = if (opens++ == 0) "cold" else "warm"
        log("Live TV open ($kind): list on screen after $ms ms ($rows rows)")
    }
}
