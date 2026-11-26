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
    /** A Gemini request for the Home row is running (the Refresh Suggestions tile shows it). */
    var refreshing by mutableStateOf(false)
        private set
    /** Keys suggested before (newest last, capped): a refresh asks for titles not shown yet. */
    private var shownKeys: List<String> = emptyList()
    private var shownTitles: List<String> = emptyList()
    private const val MAX_SHOWN = 200
    /** The row had suggestions at some point (so an empty row means "all reviewed", not "nothing yet"). */
    var hadAny by mutableStateOf(false)
        private set
    /** After a Like / Dislike / Already Watched / Add to Watchlist, the row moves focus here. Cleared once consumed. */
    var pendingFocus by mutableStateOf<AiSuggest.FocusAfter?>(null)
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

    /** Titles in any watchlist (My List and the user's lists). */
    private fun favorites(repo: Repo): List<String> = repo.watchLists.flatMap { it.items }.filter { it.kind == "vod" }.map { titleOf(it) }.distinct()

    /**
     * What the Home row shows: suggestions not liked or disliked, not in a watchlist and not watched yet. When this is
     * empty but suggestions were shown, the row offers Refresh Suggestions (it does not auto-refresh).
     */
    fun visibleFor(repo: Repo, items: List<Item> = suggested): List<Item> {
        val seen = runCatching { repo.store.history().map { it.key }.toSet() }.getOrDefault(emptySet())
        return AiSuggest.remaining(items, feedback, repo.listedKeys(), seen)
    }

    /** Record where focus should go after [item] leaves the row that [visibleBefore] describes. */
    fun noteRemoval(visibleBefore: List<Item>, item: Item) {
        pendingFocus = AiSuggest.focusAfterRemoval(visibleBefore.map { it.key }, item.key)
    }

    /**
     * Run [action] (Like, Already Watched, add to a list, …) and take [item] out of the row in place.
     * Does not ask Gemini and does not invalidate the cached suggestions.
     */
    fun review(repo: Repo, item: Item, moveFocus: Boolean = false, action: () -> Unit) {
        val before = visibleFor(repo)
        action()
        if (moveFocus) noteRemoval(before, item)
        drop(repo, item)
    }

    /** Already Watched on a suggestion: marked watched (watch history, so Gemini counts it) and out of the row. */
    fun markWatched(repo: Repo, item: Item, moveFocus: Boolean = true) {
        review(repo, item, moveFocus) { repo.setWatched(item, true, bumpLibrary = false) }
    }

    /** Takes [item] out of the saved row (watched, added to a list, liked or disliked). Keeps the cache fresh. */
    fun drop(repo: Repo, item: Item) {
        val kept = suggested.filter { it.key != item.key }
        if (kept.size != suggested.size) { suggested = kept; persistSuggested(repo, keepStamp = true) }
    }

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
    fun toggleVote(repo: Repo, item: Item, want: AiVote, moveFocus: Boolean = false) {
        loadFeedback(repo)
        review(repo, item, moveFocus) {
            feedback = AiFeedback.toggle(feedback, item.key, titleOf(item), want)
            repo.store.cacheWrite(AiFeedback.FILE, AiFeedback.toJson(feedback))
        }
    }

    private fun sig(repo: Repo): String {
        val w = watched(repo); val f = favorites(repo)
        val liked = AiFeedback.likedTitles(feedback)
        val disliked = AiFeedback.dislikedTitles(feedback)
        return (w.take(20) + "|" + f.take(20) + "|" + liked.take(20) + "|" + disliked.take(20)).hashCode().toString()
    }

    /**
     * Writes the current row to disk. [keepStamp] preserves the existing timestamp and signature so a Like /
     * Already Watched cannot look like a stale cache and trigger a Gemini call. A real refresh passes [signature]
     * and keepStamp = false to stamp a new time.
     */
    private fun persistSuggested(repo: Repo, signature: String? = null, keepStamp: Boolean = false) {
        val prev = repo.store.cacheRead(CACHE)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val t = if (keepStamp) (prev?.optLong("t") ?: 0L) else System.currentTimeMillis()
        val sig = if (keepStamp) (prev?.optString("sig") ?: "") else (signature ?: sig(repo))
        repo.store.cacheWrite(CACHE, JSONObject()
            .put("t", t)
            .put("sig", sig)
            .put("items", JSONArray().also { a -> suggested.forEach { a.put(it.toJson()) } })
            .put("shown", JSONArray(shownKeys))
            .put("shownTitles", JSONArray(shownTitles))
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
        o.optJSONArray("shown")?.let { s -> shownKeys = (0 until s.length()).map { s.optString(it) }.filter { it.isNotEmpty() } }
        o.optJSONArray("shownTitles")?.let { s -> shownTitles = (0 until s.length()).map { s.optString(it) }.filter { it.isNotEmpty() } }
        if (shownKeys.isNotEmpty()) hadAny = true
        val a = o.optJSONArray("items") ?: return
        suggested = AiFeedback.visible((0 until a.length()).mapNotNull { runCatching { Item.from(a.getJSONObject(it)) }.getOrNull() }, feedback)
        if (suggested.isNotEmpty()) hadAny = true
    }

    /**
     * Asks Gemini for new suggestions. Automatic calls only run when the cache is older than [TTL_MS] or missing;
     * a Like, Dislike, Already Watched or Add to Watchlist never triggers this. Pass [force] from Refresh Suggestions.
     * Never throws.
     */
    suspend fun refreshSuggested(repo: Repo, force: Boolean = false) {
        if (!Gemini.enabled) { suggested = emptyList(); return }
        loadCache(repo)
        if (busy) return
        val w = watched(repo); val f = favorites(repo)
        val liked = AiFeedback.likedTitles(feedback)
        val disliked = AiFeedback.dislikedTitles(feedback)
        if (w.isEmpty() && f.isEmpty() && liked.isEmpty() && !force) return
        val signature = sig(repo)
        val cache = repo.store.cacheRead(CACHE)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val age = if (cache != null) System.currentTimeMillis() - cache.optLong("t") else Long.MAX_VALUE
        val fresh = cache != null && cache.optLong("t") > 0L && age < TTL_MS && suggested.isNotEmpty()
        if (fresh && !force) return
        if (!force && lastTrySig.isNotEmpty() && System.currentTimeMillis() - lastTry < RETRY_MS) return
        busy = true; refreshing = true
        try {
            val lib = withContext(Dispatchers.Default) { library(repo) }
            if (lib.size < 20) return                       // library not loaded yet; tried again when it grows
            // A refresh asks for titles not suggested before (the current row counts as shown)
            val before = if (force) (shownTitles + suggested.map { titleOf(it) }).distinct().takeLast(80) else emptyList()
            val text = withContext(Dispatchers.IO) { Gemini.generate(GeminiParse.suggestPrompt(w, f, lib, liked = liked, disliked = disliked, shown = before)) }
            val (ids, titles) = GeminiParse.picksFromText(text)
            val skip = repo.listedKeys() + repo.store.history().map { it.key } + (if (force) shownKeys + suggested.map { it.key } else emptyList())
            val items = AiSuggest.remaining(GeminiParse.map(ids, titles, lib), feedback, skip, emptySet())
            if (items.isNotEmpty()) {
                val old = suggested
                suggested = items
                val (k, t) = AiSuggest.remember(shownKeys, shownTitles, old + items, ::titleOf, MAX_SHOWN)
                shownKeys = k; shownTitles = t; hadAny = true
                persistSuggested(repo, signature, keepStamp = false)
                lastError = ""
            } else lastError = if (force) "No new suggestions right now. Try again later." else ""
            lastTry = System.currentTimeMillis(); lastTrySig = signature
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: "Gemini request failed"
            lastTry = System.currentTimeMillis(); lastTrySig = signature   // back off; no retry storm
        } finally { busy = false; refreshing = false }
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
