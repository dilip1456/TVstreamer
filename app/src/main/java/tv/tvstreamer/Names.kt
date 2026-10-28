package tv.tvstreamer

/**
 * Display-only tidying of portal names. Raw ids / names are still used for every portal request.
 *  "EN| Action Movies" -> "Action Movies" (tag "EN"),  "4K-Avatar" -> "Avatar",  "|NF| Dune [4K]" -> "Dune"
 */
object Names {
    private const val QUALITY = "4K|8K|UHD|FHD|HD|SD|HEVC|H265|H\\.265|HDR|1080P|720P|2160P"
    // A short ALL-CAPS tag (language / provider / quality) followed by a separator: "EN|", "EN -", "[EN]", "NF:", "4K-"
    private val prefix = Regex(
        "^\\s*(?:" +
            "[\\[(|]\\s*([A-Z0-9+]{2,4}|$QUALITY)\\s*[\\])|]" +                       // [EN] (EN) |EN|
            "|([A-Z]{2,3}|$QUALITY|[A-Z]{2,3}\\+)\\s*[|:]" +                            // EN|  EN:  NF:
            "|([A-Z]{2,3}|[A-Z]{2,3}\\+)\\s+[-–]\\s+" +                                 // EN - x
            "|($QUALITY)\\s*[-–]" +                                                     // 4K-x  FHD - x
        ")\\s*[-–:|]*\\s*"
    )
    private val bracketTag = Regex("\\s*[\\[(]\\s*(?:$QUALITY|MULTI[A-Z]*|VIP|DUAL|SUB|DUB)\\s*[\\])]", RegexOption.IGNORE_CASE)
    private val trailingTag = Regex("(?:\\s+(?:$QUALITY))+\\s*$", RegexOption.IGNORE_CASE)
    private val edges = Regex("^[\\s|:\\-–•*#]+|[\\s|:\\-–•*#]+$")

    /** Clean label and the stripped tag (e.g. "EN"), if any. */
    fun split(raw: String): Pair<String, String> {
        var s = raw.trim()
        var tag = ""
        repeat(3) {
            val m = prefix.find(s) ?: return@repeat
            val t = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
            val rest = s.substring(m.range.last + 1)
            if (rest.isBlank()) return@repeat
            if (tag.isEmpty() && !t.matches(Regex(QUALITY, RegexOption.IGNORE_CASE))) tag = t
            s = rest
        }
        s = bracketTag.replace(s, "")
        val t2 = trailingTag.replace(s, "")
        if (t2.isNotBlank()) s = t2
        s = edges.replace(s, "").replace(Regex("\\s{2,}"), " ")
        if (s.isBlank()) return raw.trim() to ""
        return titleCase(s) to tag
    }

    fun title(raw: String): String = split(raw).first

    /** Category label with its language tag kept small at the end: "Action · EN". */
    fun cat(raw: String): String = split(raw).let { (c, t) -> if (t.isNotEmpty() && !c.contains(t)) "$c · $t" else c }

    /** ALL-CAPS labels become Title Case; short words (USA, HBO, TV) stay as they are. */
    private fun titleCase(s: String): String {
        val letters = s.filter { it.isLetter() }
        if (letters.length < 4 || letters.any { it.isLowerCase() }) return s
        return s.split(" ").joinToString(" ") { w ->
            if (w.count { it.isLetter() } <= 3) w else w.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    // ---- Seasons / episodes (display only) ----

    private val seasonNum = listOf(
        Regex("(?i)\\bS(?:eason)?\\s*[-_.:#]?\\s*0*(\\d{1,3})(?!\\d)"),                 // S01, Season 01, Season-1, S 1
        Regex("(?i)\\b(?:series|saison|temporada|staffel|stagione|sezon)\\s*[-_.:#]?\\s*0*(\\d{1,3})(?!\\d)"),
        Regex("^\\s*0*(\\d{1,3})\\s*$")                                                     // bare "1"
    )
    private val epMarker = Regex("(?i)\\bS\\d{1,3}\\s*[-_.]?\\s*E(?:p)?\\s*0*(\\d{1,4})\\b|\\b\\d{1,3}x0*(\\d{1,4})\\b")
    private val epWord = Regex("(?i)^(?:episode|episodio|épisode|folge|ep|e)\\s*[-_.:#]?\\s*0*(\\d{1,4})\\b")
    private val seps = Regex("^[\\s|:\\-–—_.,•*#/]+|[\\s|:\\-–—_.,•*#/]+$")

    /** Season number from a label ("Koffee With Karan Season 1", "S01", "Season-1", "Series 1"). 0 = none. */
    fun seasonNumber(raw: String): Int {
        for (r in seasonNum) r.find(raw)?.let { m -> m.groupValues[1].toIntOrNull()?.let { if (it in 0..999) return it } }
        return 0
    }

    /** Removes the show's name (and separators after it) from the start of a season / episode name. */
    fun stripShow(raw: String, shows: List<String>): String {
        var s = raw.trim()
        for (show in shows.map { title(it).trim() }.filter { it.length >= 2 }.sortedByDescending { it.length }) {
            val words = show.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            val re = Regex("^\\W*" + words.joinToString("[^\\p{L}\\p{N}]*") { Regex.escape(it) } + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            val m = re.find(s) ?: continue
            val rest = seps.replace(s.substring(m.range.last + 1), "")
            if (rest.isNotEmpty()) { s = rest; break }
        }
        return seps.replace(s, "").ifEmpty { raw.trim() }
    }

    /** "Koffee With Karan Season 1" -> "Season 1"; "S01" / "Season-01" / "Series 1" -> "Season 1"; else the name without the show. */
    fun seasonLabel(raw: String, shows: List<String> = emptyList()): String {
        if (raw.isBlank()) return raw
        val n = seasonNumber(stripShow(raw, shows)).takeIf { it > 0 } ?: seasonNumber(raw)
        if (n > 0) return "Season $n"
        return title(stripShow(raw, shows))
    }

    /**
     * Episode display name: show name, season and "S01E02" markers removed. A real title is kept as "2. Title";
     * a name that was only numbering becomes "Episode 2".
     */
    fun episodeLabel(raw: String, shows: List<String> = emptyList(), number: Int = 0): String {
        var s = title(raw)
        s = stripShow(s, shows)
        var n = number
        epMarker.find(s)?.let { m ->
            if (n <= 0) n = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() ?: 0
            s = seps.replace(s.removeRange(m.range), " ").trim()
        }
        // leading "Season 1" left over
        Regex("(?i)^(?:season|series|saison|temporada)\\s*[-_.:#]?\\s*\\d{1,3}\\b").find(s)?.let { s = seps.replace(s.removeRange(it.range), "") }
        epWord.find(s)?.let { m ->
            if (n <= 0) n = m.groupValues[1].toIntOrNull() ?: 0
            s = seps.replace(s.removeRange(m.range), "")
        }
        s = seps.replace(s, "").replace(Regex("\\s{2,}"), " ")
        if (s.matches(Regex("\\d{1,4}"))) { if (n <= 0) n = s.toInt(); s = "" }
        // the remaining text is just the show name again -> no real title
        if (s.isNotEmpty() && shows.any { Tags.norm(title(it)) == Tags.norm(s) }) s = ""
        return when {
            s.isEmpty() && n > 0 -> "Episode $n"
            s.isEmpty() -> title(raw)
            n > 0 -> "$n. $s"
            else -> s
        }
    }
}
