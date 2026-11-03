package tv.tvstreamer

import org.json.JSONObject

/**
 * Remembered audio / subtitle choices. Global choices apply everywhere; a choice made while watching a series is
 * also kept for that series and wins there. Subtitles: "" = the stream's default, "off", or a language code.
 */
object TrackPrefs {
    data class Choice(val audio: String, val sub: String)

    /** The series override (when set) over the global choice, field by field. */
    fun resolve(global: Choice, series: Choice?): Choice =
        Choice(series?.audio?.ifEmpty { null } ?: global.audio, series?.sub?.ifEmpty { null } ?: global.sub)

    fun read(json: String, seriesKey: String): Choice? = runCatching {
        val o = JSONObject(json.ifEmpty { "{}" }).optJSONObject(seriesKey) ?: return null
        Choice(o.optString("a"), o.optString("s"))
    }.getOrNull()

    /** [json] with [seriesKey]'s audio and / or subtitle choice changed (null = keep), at most [max] series kept. */
    fun write(json: String, seriesKey: String, audio: String?, sub: String?, max: Int = 200): String {
        val o = runCatching { JSONObject(json.ifEmpty { "{}" }) }.getOrDefault(JSONObject())
        val cur = o.optJSONObject(seriesKey) ?: JSONObject()
        audio?.let { cur.put("a", it) }
        sub?.let { cur.put("s", it) }
        o.remove(seriesKey); o.put(seriesKey, cur)    // newest last
        val keys = o.keys().asSequence().toList()
        if (keys.size > max) keys.take(keys.size - max).forEach { o.remove(it) }
        return o.toString()
    }

    /** Two- / three-letter codes and names compare equal ("en", "eng", "English"). */
    fun sameLang(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return norm(a) == norm(b)
    }

    fun norm(l: String): String {
        val x = l.trim().lowercase().substringBefore('-').substringBefore('_')
        if (x.length == 2) return x
        return runCatching {
            java.util.Locale.getAvailableLocales().firstOrNull { loc ->
                runCatching { loc.isO3Language }.getOrNull() == x || loc.getDisplayLanguage(java.util.Locale.ENGLISH).lowercase() == x
            }?.language
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: when (x) { "fre" -> "fr"; "ger" -> "de"; "dut" -> "nl"; "chi" -> "zh"; "per" -> "fa"; else -> x }
    }

    /** Subtitle text size steps: fraction of the video height (Media3's default is 0.0533). */
    fun subFraction(size: String): Float = when (size) {
        "small" -> 0.042f
        "large" -> 0.068f
        "huge" -> 0.085f
        else -> 0.0533f
    }
    val SIZES = listOf("small", "normal", "large", "huge")
}

/** What plays after an episode: the next one in the (season-then-episode sorted) list, across seasons. */
object NextEpisode {
    fun after(eps: List<Item>, cur: Item): Item? {
        val sorted = EpisodeOrder.sort(eps)
        val i = sorted.indexOfFirst { it.key == cur.key }
        return if (i < 0) null else sorted.getOrNull(i + 1)
    }

    /** Seconds left in the "Next episode in N s" countdown, or null when it should not show. */
    fun countdown(pos: Long, dur: Long, lead: Long = 10_000L): Int? {
        if (dur <= 60_000 || pos <= 0) return null
        val left = dur - pos
        return if (left in 0..lead) ((left + 999) / 1000).toInt().coerceAtLeast(0) else null
    }
}
