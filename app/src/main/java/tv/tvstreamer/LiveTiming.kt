package tv.tvstreamer

/** Debug timing of Live TV (1.70): `adb logcat -s TVstreamer-Live` shows how long the saved list and the screen took. */
object LiveTiming {
    private const val TAG = "TVstreamer-Live"
    @Volatile private var opens = 0

    private fun log(msg: String) { runCatching { android.util.Log.d(TAG, msg) } }

    /** Saved channel list read from the device and shown (once per app start). */
    fun loaded(ms: Long, channels: Int) = log("saved list loaded in $ms ms ($channels channels, no portal)")

    /** Live TV opened and the list laid out: the first open after start is "cold", later ones "warm". */
    fun shown(ms: Long, rows: Int) {
        val kind = if (opens++ == 0) "cold" else "warm"
        log("Live TV open ($kind): list on screen after $ms ms ($rows rows)")
    }
}
