package tv.tvstreamer

import org.json.JSONArray
import org.json.JSONObject

enum class AiVote { NONE, LIKED, DISLIKED }

data class AiFeedbackEntry(val key: String, val title: String)

data class AiFeedbackState(
    val liked: List<AiFeedbackEntry> = emptyList(),
    val disliked: List<AiFeedbackEntry> = emptyList()
)

/**
 * Local Like / Dislike votes on AI suggestions (and any catalog title).
 * Pure helpers, unit tested: toggle, JSON, hide-disliked, menu labels.
 */
object AiFeedback {
    const val FILE = "ai_feedback.json"
    const val LIKE = "Like"
    const val DISLIKE = "Dislike"
    private val YEAR_SUFFIX = Regex("\\(\\d{4}\\)\\s*$")

    fun normTitle(title: String): String = Tags.norm(title.replace(YEAR_SUFFIX, "").trim())

    fun titleOf(i: Item): String {
        val p = i.parsed
        val t = p.title.ifBlank { Names.title(i.name) }
        val y = p.year.ifEmpty { i.year.take(4) }
        return if (y.isNotEmpty()) "$t ($y)" else t
    }

    fun voteOf(state: AiFeedbackState, key: String, title: String = ""): AiVote {
        val n = if (title.isNotEmpty()) normTitle(title) else ""
        fun hit(list: List<AiFeedbackEntry>) = list.any { it.key == key || (n.isNotEmpty() && normTitle(it.title) == n) }
        if (hit(state.disliked)) return AiVote.DISLIKED
        if (hit(state.liked)) return AiVote.LIKED
        return AiVote.NONE
    }

    fun voteOf(state: AiFeedbackState, item: Item): AiVote = voteOf(state, item.key, titleOf(item))

    fun isDisliked(state: AiFeedbackState, key: String, title: String = ""): Boolean =
        voteOf(state, key, title) == AiVote.DISLIKED

    fun isDisliked(state: AiFeedbackState, item: Item): Boolean = isDisliked(state, item.key, titleOf(item))

    fun visible(items: List<Item>, state: AiFeedbackState): List<Item> =
        items.filter { !isDisliked(state, it) }

    /** Toggle [want]: if already that vote, clear it; otherwise set it (and clear the other). */
    fun toggle(state: AiFeedbackState, key: String, title: String, want: AiVote): AiFeedbackState {
        if (want != AiVote.LIKED && want != AiVote.DISLIKED) return remove(state, key, title)
        val current = voteOf(state, key, title)
        val cleared = remove(state, key, title)
        return if (current == want) cleared
        else when (want) {
            AiVote.LIKED -> cleared.copy(liked = cleared.liked + AiFeedbackEntry(key, title))
            AiVote.DISLIKED -> cleared.copy(disliked = cleared.disliked + AiFeedbackEntry(key, title))
            AiVote.NONE -> cleared
        }
    }

    fun remove(state: AiFeedbackState, key: String, title: String): AiFeedbackState {
        val n = normTitle(title)
        fun List<AiFeedbackEntry>.without() =
            filterNot { it.key == key || (n.isNotEmpty() && normTitle(it.title) == n) }
        return AiFeedbackState(liked = state.liked.without(), disliked = state.disliked.without())
    }

    fun label(want: AiVote, current: AiVote): String {
        val name = if (want == AiVote.LIKED) LIKE else DISLIKE
        return if (want == current) "✓  $name" else name
    }

    fun menuItems(current: AiVote): List<Pair<String, AiVote>> = listOf(
        label(AiVote.LIKED, current) to AiVote.LIKED,
        label(AiVote.DISLIKED, current) to AiVote.DISLIKED
    )

    fun likedTitles(state: AiFeedbackState): List<String> =
        state.liked.map { it.title }.filter { it.isNotBlank() }.distinct()

    fun dislikedTitles(state: AiFeedbackState): List<String> =
        state.disliked.map { it.title }.filter { it.isNotBlank() }.distinct()

    fun toJson(state: AiFeedbackState): String = JSONObject()
        .put("liked", JSONArray().also { a -> state.liked.forEach { a.put(entryJson(it)) } })
        .put("disliked", JSONArray().also { a -> state.disliked.forEach { a.put(entryJson(it)) } })
        .toString()

    fun fromJson(text: String?): AiFeedbackState {
        if (text.isNullOrBlank()) return AiFeedbackState()
        return runCatching {
            val o = JSONObject(text)
            AiFeedbackState(liked = entries(o.optJSONArray("liked")), disliked = entries(o.optJSONArray("disliked")))
        }.getOrDefault(AiFeedbackState())
    }

    private fun entryJson(e: AiFeedbackEntry) = JSONObject().put("key", e.key).put("title", e.title)

    private fun entries(a: JSONArray?): List<AiFeedbackEntry> {
        if (a == null) return emptyList()
        val out = ArrayList<AiFeedbackEntry>(a.length())
        val seen = HashSet<String>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val key = o.optString("key").trim()
            val title = o.optString("title").trim()
            if (key.isEmpty() || !seen.add(key)) continue
            out += AiFeedbackEntry(key, title)
        }
        return out
    }
}
