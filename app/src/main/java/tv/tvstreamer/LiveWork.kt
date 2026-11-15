package tv.tvstreamer

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live TV's own worker thread (1.72). Reading the saved channel list, sorting channels and laying the Live TV list
 * out used to run on the shared background pool (Dispatchers.Default) together with every Home / Movies / Series
 * shelf rebuild. With the 1.71 title index every indexed page re-ran those rebuilds over the whole catalog, so on a
 * Fire TV the Live TV list waited in line behind them for minutes. Live TV now never queues behind catalog work.
 */
object LiveWork {
    val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tvstreamer-live").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 2 }
    }.asCoroutineDispatcher()
}

/**
 * When the background title indexer may fetch a page (1.72). Live TV goes first: no index pages until the channel
 * list is saved on the device, while it is being fetched, while Live TV is on screen, or while a video plays.
 */
object IndexGate {
    fun canRun(stopped: Boolean, playing: Boolean, portalPaused: Boolean, channelsSaved: Boolean,
               channelsFetching: Boolean, liveOpen: Boolean): Boolean =
        !stopped && !playing && !portalPaused && channelsSaved && !channelsFetching && !liveOpen
}

/**
 * Coalesces "the catalog changed" signals while the indexer runs (1.72): a page every few seconds used to rebuild
 * every Home / Movies / Series shelf each time. Now at most once per [minGapMs], plus a final one at the end.
 */
class BumpThrottle(private val minGapMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile private var last = Long.MIN_VALUE / 2
    private val pending = AtomicBoolean(false)

    /** Something changed: true when the screens should be told now. */
    fun changed(): Boolean {
        val t = clock()
        if (t - last >= minGapMs) { last = t; pending.set(false); return true }
        pending.set(true); return false
    }

    /** The run ended: true when a held-back change still has to be told. */
    fun flush(): Boolean { val p = pending.getAndSet(false); if (p) last = clock(); return p }
}

/** What Live TV says while it has no channels to list (1.72). */
object LiveStatus {
    fun empty(readingSaved: Boolean, status: String): String = when {
        readingSaved -> "Opening saved channels…"
        status.isNotEmpty() -> status
        else -> "No channels loaded yet"
    }
}
