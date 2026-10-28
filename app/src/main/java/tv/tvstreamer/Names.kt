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
}
