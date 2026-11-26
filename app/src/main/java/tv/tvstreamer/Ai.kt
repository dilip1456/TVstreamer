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
    /** Suggested titles (library items) for the Home row. Disliked titles are never kept here. */
    var suggested by mutableStateOf<List<Item>>(emptyList())
        private set
    var lastError by mutableStateOf("")
        private set
    /** Local Like / Dislike votes. Observable so the Home row and menus update at once. */
    var feedback by mutableStateOf(AiFeedbackState())
        private set

    private const val CACHE = "ai_suggest.json"
    private const val TTL_MS = 12 * 3600_000L
    private const val RETRY_MS = 30 * 60_000L
    private const val MAX_LIB = 700
    @Volatile private var busy = false
    @Volatile private var lastTry = 0L
    @Volatile private var lastTrySig = ""
    @Volatile private var feedbackLoaded = false

    private fun titleOf(i: Item): String = AiFeedback.titleOf(i)

    /** Compact library for the prompt: one entry per title (best version), movies and series, respecting the Show adult setting. */
    fun library(repo: Repo): List<LibEntry<Item>> {
        val all = repo.lib.everything().filter { it.kind == "vod" && !it.isEpisode() && repo.listOk(it.locked) }
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

    fun voteOf(item: Item): AiVote = AiFeedback.voteOf(feedback, item)

    fun loadFeedback(repo: Repo) {
        if (feedbackLoaded) return
        feedback = AiFeedback.fromJson(repo.store.cacheRead(AiFeedback.FILE))
        feedbackLoaded = true
    }

    /**
     * Toggle Like or Dislike on [item]. Disliked titles leave the suggestion row immediately
     * and stay out of later Gemini picks; both lists are sent as preference signals in the prompt.
     */
    fun toggleVote(repo: Repo, item: Item, want: AiVote) {
        loadFeedback(repo)
        feedback = AiFeedback.toggle(feedback, item.key, titleOf(item), want)
        repo.store.cacheWrite(AiFeedback.FILE, AiFeedback.toJson(feedback))
        val kept = AiFeedback.visible(suggested, feedback)
        if (kept != suggested) {
            suggested = kept
            // Stale timestamp so the next refresh (triggered by the new feedback sig) asks Gemini again.
            persistSuggested(repo, sig(repo), stamped = false)
        }
    }

    private fun sig(repo: Repo): String {
        val w = watched(repo); val f = favorites(repo)
        val liked = AiFeedback.likedTitles(feedback)
        val disliked = AiFeedback.dislikedTitles(feedback)
        return (w.take(20) + "|" + f.take(20) + "|" + liked.take(20) + "|" + disliked.take(20)).hashCode().toString()
    }

    private fun persistSuggested(repo: Repo, signature: String, stamped: Boolean) {
        repo.store.cacheWrite(CACHE, JSONObject()
            .put("t", if (stamped) System.currentTimeMillis() else 0L)
            .put("sig", signature)
            .put("items", JSONArray().also { a -> suggested.forEach { a.put(it.toJson()) } })
            .toString())
    }

    /** Loads the cached row (if any) so Home shows it at once. Disliked titles are dropped. */
    fun loadCache(repo: Repo) {
        loadFeedback(repo)
        if (suggested.isNotEmpty()) {
            suggested = AiFeedback.visible(suggested, feedback)
            return
        }
        val o = repo.store.cacheRead(CACHE)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val a = o.optJSONArray("items") ?: return
        suggested = AiFeedback.visible((0 until a.length()).mapNotNull { runCatching { Item.from(a.getJSONObject(it)) }.getOrNull() }, feedback)
    }

    /** Asks Gemini for new suggestions when the cache is old or the history / favourites / votes changed. Never throws. */
    suspend fun refreshSuggested(repo: Repo, force: Boolean = false) {
        if (!Gemini.enabled) { suggested = emptyList(); return }
        loadCache(repo)
        if (busy) return
        val w = watched(repo); val f = favorites(repo)
        val liked = AiFeedback.likedTitles(feedback)
        val disliked = AiFeedback.dislikedTitles(feedback)
        if (w.isEmpty() && f.isEmpty() && liked.isEmpty()) return
        val signature = sig(repo)
        val cache = repo.store.cacheRead(CACHE)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val fresh = cache != null && cache.optString("sig") == signature && System.currentTimeMillis() - cache.optLong("t") < TTL_MS
        if (fresh && !force) return
        if (!force && signature == lastTrySig && System.currentTimeMillis() - lastTry < RETRY_MS) return
        busy = true
        try {
            val lib = withContext(Dispatchers.Default) { library(repo) }
            if (lib.size < 20) return                       // library not loaded yet; tried again when it grows
            val text = withContext(Dispatchers.IO) { Gemini.generate(GeminiParse.suggestPrompt(w, f, lib, liked = liked, disliked = disliked)) }
            val (ids, titles) = GeminiParse.picksFromText(text)
            val skip = (repo.favs.map { it.key } + repo.store.history().map { it.key }).toSet()
            val items = GeminiParse.map(ids, titles, lib).filter { it.key !in skip && !AiFeedback.isDisliked(feedback, it) }
            if (items.isNotEmpty()) {
                suggested = items
                persistSuggested(repo, signature, stamped = true)
            }
            lastError = ""
            lastTry = System.currentTimeMillis(); lastTrySig = signature
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: "Gemini request failed"
            lastTry = System.currentTimeMillis(); lastTrySig = signature   // back off; no retry storm
        } finally { busy = false }
    }

    /** Voice / typed request: search words, or titles from the library. */
    suspend fun ask(repo: Repo, request: String): AiOutcome {
        if (!Gemini.enabled) return AiOutcome.Search(request, "")
        return try {
            loadFeedback(repo)
            val liked = AiFeedback.likedTitles(feedback)
            val disliked = AiFeedback.dislikedTitles(feedback)
            val lib = withContext(Dispatchers.Default) { library(repo) }
            val text = withContext(Dispatchers.IO) { Gemini.generate(GeminiParse.assistantPrompt(request, watched(repo), lib, liked = liked, disliked = disliked)) }
            val r = GeminiParse.assistant(text) ?: return AiOutcome.Search(request, "")
            if (r.action == "suggest") {
                val items = GeminiParse.map(r.ids, r.titles, lib, limit = 24).filter { !AiFeedback.isDisliked(feedback, it) }
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
