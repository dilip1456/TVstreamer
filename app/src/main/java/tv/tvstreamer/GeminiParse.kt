package tv.tvstreamer

import org.json.JSONArray
import org.json.JSONObject

/** One library title sent to Gemini: a short id, the clean title, year and kind ("movie" / "series"). */
data class LibEntry<T>(val id: Int, val title: String, val year: String, val kind: String, val ref: T)

/** What the assistant wants: run a search, or show picked titles. */
data class AssistantReply(val action: String, val query: String, val ids: List<Int>, val titles: List<String>, val message: String)

/**
 * Pure helpers for the Gemini features (no Android, unit tested): the compact library list in the prompt,
 * reading the model's JSON (also when wrapped in ``` fences or prose), and mapping picks back to library items.
 */
object GeminiParse {
    /** Text of the first candidate of a generateContent response ("" when blocked / empty). */
    fun responseText(body: String): String = runCatching {
        val parts = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        (0 until parts.length()).mapNotNull { i -> parts.optJSONObject(i)?.takeIf { !it.optBoolean("thought") }?.optString("text") }
            .joinToString("")
    }.getOrDefault("")

    /** Error message from an error response body, or "". */
    fun errorMessage(body: String): String = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
    }.getOrDefault("")

    /** The JSON object in [text]: as is, inside ```json fences, or the first {...} span. A bare array becomes {"ids": [...]}. */
    fun jsonIn(text: String): JSONObject? {
        val t = text.trim().removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
        runCatching { return JSONObject(t) }
        runCatching { return JSONObject().put("ids", JSONArray(t)) }
        val s = t.indexOf('{'); val e = t.lastIndexOf('}')
        if (s >= 0 && e > s) runCatching { return JSONObject(t.substring(s, e + 1)) }
        val a = t.indexOf('['); val b = t.lastIndexOf(']')
        if (a >= 0 && b > a) runCatching { return JSONObject().put("ids", JSONArray(t.substring(a, b + 1))) }
        return null
    }

    /** Ids and titles from "ids" / "picks" / "titles" / "suggestions" (numbers, numeric strings, or {"id":..,"title":..}). */
    fun picks(o: JSONObject): Pair<List<Int>, List<String>> {
        val ids = mutableListOf<Int>(); val titles = mutableListOf<String>()
        for (k in listOf("ids", "picks", "titles", "suggestions", "items")) {
            val a = o.optJSONArray(k) ?: continue
            for (i in 0 until a.length()) {
                when (val v = a.opt(i)) {
                    is Number -> ids += v.toInt()
                    is String -> v.trim().toIntOrNull()?.let { ids += it } ?: v.trim().takeIf { it.isNotEmpty() }?.let { titles += it }
                    is JSONObject -> {
                        val id = v.opt("id")
                        when (id) { is Number -> ids += id.toInt(); is String -> id.trim().toIntOrNull()?.let { ids += it } }
                        if (id == null || (id is String && id.trim().toIntOrNull() == null)) v.optString("title").trim().takeIf { it.isNotEmpty() }?.let { titles += it }
                    }
                }
            }
        }
        return ids.distinct() to titles.distinct()
    }

    /** Picks from a model reply text (empty when it is not JSON). */
    fun picksFromText(text: String): Pair<List<Int>, List<String>> = jsonIn(text)?.let { picks(it) } ?: (emptyList<Int>() to emptyList())

    /** Assistant reply: {"action":"search","query":"..."} or {"action":"suggest","ids":[..],"message":".."}. */
    fun assistant(text: String): AssistantReply? {
        val o = jsonIn(text) ?: return null
        val (ids, titles) = picks(o)
        val query = o.optString("query").trim()
        val action = o.optString("action").trim().lowercase().ifEmpty { if (ids.isNotEmpty() || titles.isNotEmpty()) "suggest" else "search" }
        val a = when (action) { "suggest", "suggestions", "recommend" -> "suggest"; else -> "search" }
        if (a == "search" && query.isEmpty() && (ids.isNotEmpty() || titles.isNotEmpty())) return AssistantReply("suggest", "", ids, titles, o.optString("message").trim())
        if (a == "search" && query.isEmpty()) return null
        return AssistantReply(a, query, ids, titles, o.optString("message").trim())
    }

    /** Maps picked ids (and titles, when the model answered with names) back to library entries, in pick order, no duplicates. */
    fun <T> map(ids: List<Int>, titles: List<String>, lib: List<LibEntry<T>>, limit: Int = 30): List<T> {
        val byId = lib.associateBy { it.id }
        val byTitle = lib.groupBy { Tags.norm(it.title) }
        val out = LinkedHashMap<Int, T>()
        ids.forEach { id -> byId[id]?.let { out.putIfAbsent(it.id, it.ref) } }
        titles.forEach { t ->
            val clean = Tags.norm(t.replace(Regex("\\(\\d{4}\\)\\s*$"), ""))
            byTitle[clean]?.firstOrNull()?.let { out.putIfAbsent(it.id, it.ref) }
        }
        return out.values.take(limit)
    }

    /** One line per title: "12|The Matrix|1999|movie". Pipes in titles are replaced. */
    fun libraryLines(lib: List<LibEntry<*>>): String =
        lib.joinToString("\n") { e -> "${e.id}|${e.title.replace('|', '/')}|${e.year}|${e.kind}" }

    /** Prompt for "Suggested for you". */
    fun suggestPrompt(watched: List<String>, favorites: List<String>, lib: List<LibEntry<*>>, count: Int = 20): String = buildString {
        append("You recommend movies and TV series for a TV app. Pick up to $count titles the user is likely to enjoy, ")
        append("similar in genre, tone, cast or era to what they watched and liked. ")
        append("Pick ONLY from the LIBRARY list below and do not pick titles they already watched or favorited. ")
        append("Answer with JSON only: {\"ids\":[<library id>, ...]} ordered best first.\n\n")
        append("WATCHED:\n").append(watched.take(40).joinToString("\n").ifEmpty { "(none)" }).append("\n\n")
        append("FAVORITES:\n").append(favorites.take(40).joinToString("\n").ifEmpty { "(none)" }).append("\n\n")
        append("LIBRARY (id|title|year|kind):\n").append(libraryLines(lib))
    }

    /** Prompt for the voice / typed assistant. */
    fun assistantPrompt(request: String, watched: List<String>, lib: List<LibEntry<*>>): String = buildString {
        append("You are the search assistant of a TV app (movies, TV series and live channels). The user said: \"")
        append(request.replace("\"", "'")).append("\"\n")
        append("If they ask for a specific title, person, channel or keyword, answer {\"action\":\"search\",\"query\":\"<short search words>\"}. ")
        append("If they ask for ideas or a kind of thing (e.g. 'something funny for tonight', 'movies like Inception'), answer ")
        append("{\"action\":\"suggest\",\"ids\":[<library id>, ...],\"message\":\"<one short sentence>\"} with up to 24 ids picked ONLY from the LIBRARY list. ")
        append("Answer with JSON only.\n\n")
        append("RECENTLY WATCHED:\n").append(watched.take(20).joinToString("\n").ifEmpty { "(none)" }).append("\n\n")
        append("LIBRARY (id|title|year|kind):\n").append(libraryLines(lib))
    }
}
