package tv.tvstreamer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * Type and language of every live channel, worked out once per channel list (off the main thread), published in
 * chunks so channels appear while sorting runs, and saved to disk so the next start shows them at once.
 */
class LiveIndex(private val store: Store?) {
    enum class State { IDLE, RUNNING, READY, CANCELLED, FAILED }

    var info by mutableStateOf<Map<String, ChInfo>>(emptyMap()); private set
    var state by mutableStateOf(State.IDLE); private set
    var done by mutableStateOf(0); private set
    var total by mutableStateOf(0); private set
    var error by mutableStateOf(""); private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var key = ""

    /** Sort [channels] unless this exact list is already sorted (or being sorted). */
    @Synchronized
    fun ensure(channels: List<Item>, cats: List<Cat>, force: Boolean = false) {
        if (channels.isEmpty()) return
        val k = keyOf(channels, cats)
        if (!force && k == key && (state == State.READY || state == State.RUNNING)) return
        job?.cancel()
        key = k
        state = State.RUNNING; error = ""; done = 0; total = channels.size
        job = scope.launch {
            try {
                // Saved result for the same list: instant
                if (!force) load(k)?.let { m -> info = m; done = channels.size; state = State.READY; return@launch }
                val titles = cats.associate { it.id to it.title }
                val m = withTimeout(TIMEOUT_MS) {
                    build(channels, titles) { partial, n -> info = partial; done = n }
                }
                info = m; done = channels.size; state = State.READY
                save(k, m)
            } catch (e: TimeoutCancellationException) {
                state = State.FAILED; error = "Sorting took too long"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                state = State.FAILED; error = e.message ?: "Could not sort channels"
            }
        }
    }

    /** Stop sorting; what is sorted so far stays on screen. */
    @Synchronized
    fun cancel() {
        if (state != State.RUNNING) return
        job?.cancel(); job = null
        state = State.CANCELLED
        key = ""   // the next ensure() starts again
    }

    private fun load(k: String): Map<String, ChInfo>? = runCatching {
        val o = JSONObject(store?.cacheRead(FILE) ?: return null)
        if (o.optString("k") != k) return null
        val m = o.getJSONObject("m")
        val out = HashMap<String, ChInfo>(m.length() * 2)
        val types = o.getJSONArray("t"); val langs = o.getJSONArray("l")
        for (ck in m.keys()) {
            val v = m.getString(ck); val sep = v.indexOf(',')
            out[ck] = ChInfo(types.getString(v.substring(0, sep).toInt()), langs.getString(v.substring(sep + 1).toInt()))
        }
        out
    }.getOrNull()

    private fun save(k: String, m: Map<String, ChInfo>) {
        val s = store ?: return
        runCatching {
            val types = m.values.map { it.type }.distinct(); val langs = m.values.map { it.lang }.distinct()
            val ti = types.withIndex().associate { it.value to it.index }; val li = langs.withIndex().associate { it.value to it.index }
            val mo = JSONObject()
            for ((ck, v) in m) mo.put(ck, "${ti[v.type]},${li[v.lang]}")
            s.cacheWrite(FILE, JSONObject().put("k", k).put("t", org.json.JSONArray(types)).put("l", org.json.JSONArray(langs)).put("m", mo).toString())
        }
    }

    companion object {
        /** Channels by type; channels not sorted yet are left out (they appear when their chunk is done). */
        fun groupByType(chans: List<Item>, info: Map<String, ChInfo>): Map<String, List<Item>> {
            val out = LinkedHashMap<String, MutableList<Item>>()
            for (c in chans) { val t = info[c.key]?.type ?: continue; out.getOrPut(t) { ArrayList() }.add(c) }
            return out
        }

        const val FILE = "live_index.json"
        const val TIMEOUT_MS = 45_000L
        /** Bump when LiveKind's rules change, so saved results are worked out again. */
        private const val RULES = 1

        /** Identity of a channel list: same channels in the same categories with the same names give the same key. */
        fun keyOf(channels: List<Item>, cats: List<Cat>): String {
            var h = 17L
            for (c in channels) { h = h * 31 + c.key.hashCode(); h = h * 31 + c.cat.hashCode(); h = h * 31 + c.name.hashCode() }
            for (c in cats) { h = h * 31 + c.id.hashCode(); h = h * 31 + c.title.hashCode() }
            return "$RULES:${channels.size}:${cats.size}:$h"
        }

        /**
         * Classify [channels]; each category name is looked at once. [onChunk] gets a snapshot of the result so far
         * every few hundred channels (so the screen can fill in progressively).
         */
        suspend fun build(channels: List<Item>, catTitle: Map<String, String>, chunk: Int = 0,
                          onChunk: (Map<String, ChInfo>, Int) -> Unit = { _, _ -> }): Map<String, ChInfo> {
            val step = if (chunk > 0) chunk else maxOf(400, channels.size / 12)
            val facts = HashMap<String, LiveKind.CatFacts>()
            val out = HashMap<String, ChInfo>(channels.size * 2)
            channels.forEachIndexed { i, c ->
                val f = facts.getOrPut(c.cat) { LiveKind.facts(catTitle[c.cat] ?: "") }
                out[c.key] = LiveKind.classify(f, c.name)
                if ((i + 1) % step == 0 && i + 1 < channels.size) {
                    coroutineContext.ensureActive()
                    onChunk(HashMap(out), i + 1)
                    yield()
                }
            }
            return out
        }
    }
}
