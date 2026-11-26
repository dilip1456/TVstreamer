package tv.tvstreamer

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What the TV home screen row shows (pure logic, unit tested): Continue watching (movies / episodes with a resume
 * point), recent live channels, then Recommended (Gemini suggestions, else newest titles). Locked / adult items are
 * never published. Also the deep link each card opens: tvstreamer://item?k=<kind>&i=<id>&a=play|open.
 */
object HomeRowsPlan {
    const val SCHEME = "tvstreamer"
    const val HOST = "item"

    enum class Section { CONTINUE, LIVE, RECOMMENDED }

    /** A Continue watching record: position / duration in ms, [at] = when it was last played (epoch ms). */
    data class Resume(val item: Item, val pos: Long, val dur: Long, val at: Long)

    data class Entry(val item: Item, val section: Section, val pos: Long = 0L, val dur: Long = 0L, val at: Long = 0L) {
        /** Continue watching and live channels play straight away; a recommended title opens its details. */
        val play: Boolean get() = section != Section.RECOMMENDED || item.kind == "itv" || item.isEpisode()
    }

    data class Link(val kind: String, val id: String, val play: Boolean) {
        val key: String get() = "$kind:$id"
    }

    fun build(
        cont: List<Resume>, recentLive: List<Item>, recommended: List<Item>,
        maxCont: Int = 10, maxLive: Int = 5, maxRec: Int = 10
    ): List<Entry> {
        val seen = HashSet<String>()
        val out = ArrayList<Entry>()
        cont.asSequence()
            .filter { !it.item.locked && it.item.kind == "vod" && it.pos > 30_000 && (it.dur <= 0 || it.pos < it.dur * 0.95) }
            .filter { seen.add(it.item.key) }
            .take(maxCont)
            .forEach { out += Entry(it.item, Section.CONTINUE, it.pos, it.dur, it.at) }
        recentLive.asSequence()
            .filter { !it.locked && it.kind == "itv" && seen.add(it.key) }
            .take(maxLive)
            .forEach { out += Entry(it, Section.LIVE) }
        recommended.asSequence()
            .filter { !it.locked && it.kind == "vod" && seen.add(it.key) }
            .take(maxRec)
            .forEach { out += Entry(it, Section.RECOMMENDED) }
        return out
    }

    /** Changes only when something visible on the card changes (progress in whole minutes). */
    fun signature(entries: List<Entry>): String =
        entries.joinToString("|") { "${it.section}:${it.item.key}:${it.pos / 60_000}:${it.item.name}:${it.item.img}" }
            .hashCode().toString()

    /** "35 min left" / "1 h 5 min left" for a Continue watching card; empty when the length is unknown. */
    fun leftText(pos: Long, dur: Long): String {
        if (dur <= 0 || pos >= dur) return ""
        val min = ((dur - pos) / 60_000).coerceAtLeast(1)
        return if (min >= 60) "${min / 60} h ${min % 60} min left" else "$min min left"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun link(item: Item, play: Boolean): String =
        "$SCHEME://$HOST?k=${enc(item.kind)}&i=${enc(item.id)}&a=${if (play) "play" else "open"}"

    fun parse(s: String?): Link? {
        if (s == null || !s.startsWith("$SCHEME://$HOST?")) return null
        val q = s.substringAfter('?').split('&').mapNotNull {
            val k = it.substringBefore('=', "")
            if (k.isEmpty()) null else k to runCatching { URLDecoder.decode(it.substringAfter('='), "UTF-8") }.getOrDefault("")
        }.toMap()
        val kind = q["k"].orEmpty(); val id = q["i"].orEmpty()
        if (kind !in setOf("vod", "itv") || id.isEmpty()) return null
        return Link(kind, id, q["a"] != "open")
    }
}
