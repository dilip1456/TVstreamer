package tv.tvstreamer

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
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
    /** 1.73: the download failed or timed out while nothing is saved (Live TV then offers Retry). */
    const val FAILED = "Could not download the channel list: "
    fun failed(status: String) = status.startsWith(FAILED)
    fun empty(readingSaved: Boolean, status: String): String = when {
        readingSaved -> "Opening saved channels…"
        status.isNotEmpty() -> status
        else -> "No channels loaded yet"
    }
}

/**
 * Every Live TV list layout (mode x view) for the current channel list (1.73), so a mode or view switch is a map
 * look-up published in the same frame instead of a re-layout of the whole list. Entries for an older channel list,
 * sort or language choice are dropped as soon as a layout for the new one arrives.
 */
class LiveModelCache(private val max: Int = 30) {
    private val map = LinkedHashMap<String, LiveModel>(16, 0.75f, true)
    private fun k(i: LiveInputs) = i.mode + '\u0000' + i.view.name

    @Synchronized fun get(i: LiveInputs): LiveModel? = map[k(i)]?.takeIf { i.same(it.inputs) }

    @Synchronized fun put(m: LiveModel) {
        val i = m.inputs ?: return
        map.values.removeAll { !LiveModel.sameBase(it.inputs, i) }
        map[k(i)] = m
        while (map.size > max) map.remove(map.keys.first())
    }

    /** A layout of the same channels, sort and languages (its sorted channel list is reused). */
    @Synchronized fun base(i: LiveInputs): LiveModel? = map.values.lastOrNull { LiveModel.sameBase(it.inputs, i) }

    @Synchronized fun size() = map.size
}

/** The portal did not answer a channel-list request in time (1.73). */
class LiveTimeoutException(val step: LiveSource.Step, val limitMs: Long) :
    Exception("the portal did not answer within ${limitMs / 1000} s (${step.label})")

/**
 * get_genres + get_all_channels with a hard time limit per request (1.73). The download runs on its own thread; when a
 * request is over its limit the app stops waiting for it and [LiveTimeoutException] is thrown at once, so the screen
 * shows an error and Retry instead of waiting forever. [onStep] names each request as it starts.
 * 1.74: the request itself is never interrupted (it ends by itself within the HTTP timeouts and its list is still saved):
 * interrupting it in the middle of the portal sign-in left the session on a wrong address, so channels did not play
 * until the app was restarted.
 * Rate limiter is skipped for the channel list fetch so there is no artificial delay; local filtering is instantaneous.
 */
object LiveFetch {
    const val STEP_MS = 45_000L    // portal login + categories (slightly more room)
    const val LIST_MS = 120_000L   // the whole channel list (large portals send several MB; no client delay)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    suspend fun run(live: LiveSource, stepMs: Long = STEP_MS, listMs: Long = LIST_MS,
                    onStep: (LiveSource.Step) -> Unit = {}): LiveSource.Snapshot {
        val cur = java.util.concurrent.atomic.AtomicReference<Pair<LiveSource.Step, Long>?>(null)
        val job = scope.async { live.fetch { s -> cur.set(s to System.nanoTime()); runCatching { onStep(s) } } }
        while (!job.isCompleted) {
            val c = cur.get()
            if (c != null) {
                val limit = if (c.first == LiveSource.Step.CHANNELS) listMs else stepMs
                if ((System.nanoTime() - c.second) / 1_000_000 > limit) throw LiveTimeoutException(c.first, limit)
            }
            kotlinx.coroutines.delay(25)
        }
        return job.await()
    }
}
