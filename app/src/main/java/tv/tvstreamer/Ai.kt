package tv.tvstreamer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Result of asking the assistant. */
sealed class AiOutcome {
    data class Search(val query: String, val message: String) : AiOutcome()
    data class Suggest(val items: List<Item>, val message: String) : AiOutcome()
    data class Failed(val message: String) : AiOutcome()
}

/** Gemini features: "Suggested for you" on Home and the voice / typed assistant in Search. Everything fails quietly without a key. */
object Ai {
    /** Suggested titles (library items) for the Home row. */
    var suggested by mutableStateOf<List<Item>>(emptyList())
        private set
    var lastError by mutableStateOf("")
        private set

    private const val CACHE = "ai_suggest.json"
    private const val TTL_MS = 12 * 3600_000L
    private const val RETRY_MS = 30 * 60_000L
    private const val MAX_LIB = 700
    @Volatile private var busy = false
    @Volatile private var lastTry = 0L
    @Volatile private var lastTrySig = ""

    private fun titleOf(i: Item): String {
        val p = i.parsed
        val t = p.title.ifBlank { Names.title(i.name) }
        val y = p.year.ifEmpty { i.year.take(4) }
        return if (y.isNotEmpty()) "$t ($y)" else t
    }

    /** Compact library for the prompt: one entry per title (best version), movies and series, unlocked only. */
    fun library(repo: Repo): List<LibEntry<Item>> {
        val unlocked = repo.gate(true) == null
        val all = repo.lib.everything().filter { it.kind == "vod" && !it.isEpisode() && (!it.locked || unlocked) }
        val groups = runCatching { repo.lib.cards(all) }.getOrDefault(emptyList())
        val seen = HashSet<String>()
        val out = ArrayList<LibEntry<Item>>()
        for (g in groups) {
            val b = g.best
            val title = b.parsed.title.ifBlank { Names.title(b.name) }
            if (title.isBlank() || !seen.add(Tags.norm(title) + "|" + b.isSeries)) continue
            out += LibEntry(out.size + 1, title, b.parsed.year.ifEmpty { b.year.take(4) }, if (b.isSeries) "series" else "movie", b)
            if (out.size >= MAX_LIB) break
        }
        return out
    }

    private fun watched(repo: Repo): List<String> =
        repo.store.history().filter { it.kind == "vod" }.map { e -> if (e.isEpisodeLike()) (repo.store.parentOf(e)?.let { titleOf(it) } ?: titleOf(e)) else titleOf(e) }.distinct()

    private fun favorites(repo: Repo): List<String> = repo.favs.filter { it.kind == "vod" }.map { titleOf(it) }.distinct()

    /** Loads the cached row (if any) so Home shows it at once. */
    fun loadCache(repo: Repo) {
        if (suggested.isNotEmpty()) return
        val o = repo.store.cacheRead(CACHE)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val a = o.optJSONArray("items") ?: return
        suggested = (0 until a.length()).mapNotNull { runCatching { Item.from(a.getJSONObject(it)) }.getOrNull() }
    }

    /** Asks Gemini for new suggestions when the cache is old or the history / favourites changed. Never throws. */
    suspend fun refreshSuggested(repo: Repo, force: Boolean = false) {
        if (!Gemini.enabled) { suggested = emptyList(); return }
        loadCache(repo)
        if (busy) return
        val w = watched(repo); val f = favorites(repo)
        if (w.isEmpty() && f.isEmpty()) return
        val sig = (w.take(20) + "|" + f.take(20)).hashCode().toString()
        val cache = repo.store.cacheRead(CACHE)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val fresh = cache != null && cache.optString("sig") == sig && System.currentTimeMillis() - cache.optLong("t") < TTL_MS
        if (fresh && !force) return
        if (!force && sig == lastTrySig && System.currentTimeMillis() - lastTry < RETRY_MS) return
        busy = true
        try {
            val lib = withContext(Dispatchers.Default) { library(repo) }
            if (lib.size < 20) return                       // library not loaded yet; tried again when it grows
            val text = withContext(Dispatchers.IO) { Gemini.generate(GeminiParse.suggestPrompt(w, f, lib)) }
            val (ids, titles) = GeminiParse.picksFromText(text)
            val skip = (repo.favs.map { it.key } + repo.store.history().map { it.key }).toSet()
            val items = GeminiParse.map(ids, titles, lib).filter { it.key !in skip }
            if (items.isNotEmpty()) {
                suggested = items
                repo.store.cacheWrite(CACHE, JSONObject().put("t", System.currentTimeMillis()).put("sig", sig)
                    .put("items", JSONArray().also { a -> items.forEach { a.put(it.toJson()) } }).toString())
            }
            lastError = ""
            lastTry = System.currentTimeMillis(); lastTrySig = sig
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: "Gemini request failed"
            lastTry = System.currentTimeMillis(); lastTrySig = sig   // back off; no retry storm
        } finally { busy = false }
    }

    /** Voice / typed request: search words, or titles from the library. */
    suspend fun ask(repo: Repo, request: String): AiOutcome {
        if (!Gemini.enabled) return AiOutcome.Search(request, "")
        return try {
            val lib = withContext(Dispatchers.Default) { library(repo) }
            val text = withContext(Dispatchers.IO) { Gemini.generate(GeminiParse.assistantPrompt(request, watched(repo), lib)) }
            val r = GeminiParse.assistant(text) ?: return AiOutcome.Search(request, "")
            if (r.action == "suggest") {
                val items = GeminiParse.map(r.ids, r.titles, lib, limit = 24)
                if (items.isEmpty()) AiOutcome.Search(r.query.ifEmpty { request }, r.message)
                else AiOutcome.Suggest(items, r.message.ifEmpty { "Suggestions for \"$request\"" })
            } else AiOutcome.Search(r.query, r.message)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AiOutcome.Failed(e.message ?: "Gemini request failed")
        }
    }
}
