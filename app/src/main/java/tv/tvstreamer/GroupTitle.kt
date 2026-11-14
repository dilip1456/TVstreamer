package tv.tvstreamer

/**
 * Display-only labels for catalog groups, category rows and Live TV section headers. Portal ids and keys are
 * unchanged; search and hide/show still use the original title. Strips redundant prefixes that repeat the
 * parent context: "Hindi Actor - Shah Rukh Khan" under By Actor becomes "Shah Rukh Khan", "Movies - Action"
 * under Movies becomes "Action", and a trailing "Channels" word is dropped.
 */
object GroupTitle {
    private val SPACES = Regex("\\s+")
    private val EDGE = Regex("^[\\s|:\\-–—•*#]+|[\\s|:\\-–—•*#]+$")
    private const val LANGS =
        "english|hindi|urdu|tamil|telugu|malayalam|kannada|marathi|bengali|bangla|punjabi|gujarati|" +
        "odia|oriya|assamese|bhojpuri|nepali|sinhala|arabic|french|german|spanish|italian|portuguese|" +
        "turkish|persian|farsi|russian|polish|dutch|greek|albanian|chinese|japanese|korean|thai|" +
        "usa|uk|india|indian|pakistan|pakistani|canada|canadian|australia|australian|ireland|irish|" +
        "american|british"
    // Roles that are also everyday genre words need a separator so "Music Videos" stays intact.
    private const val ROLE_SEP = "music(?:\\s+directors?)?|stars?|artists?|cast|collections?"
    private const val ROLE_ANY = "actors?|actresses?|directors?|singers?|musicians?|$ROLE_SEP"
    private val ROLE_PREFIX = Regex(
        "(?i)^(?:(?:$LANGS)\\s+)?(?:$ROLE_ANY|cinema\\s+by\\s+actor|movies\\s+by\\s+actor)\\s*" +
            "(?:[:|\\-–—]|\\bby\\b)\\s+"
    )
    private val ROLE_SPACE = Regex("(?i)^(?:(?:$LANGS)\\s+)?(?:actors?|actresses?|directors?|singers?|musicians?)\\s+(?=\\S)")
    private val CINEMA_BY = Regex("(?i)^(?:cinema|movies?|films?)\\s+by\\s+(?:actors?|directors?|stars?)\\s*(?:[:|\\-–—]\\s*)?")
    private val MOVIES_LEAD = Regex("(?i)^(?:movies?|films?|cinema)\\s*[:|\\-–—]\\s+")
    private val SERIES_LEAD = Regex("(?i)^(?:tv\\s*shows?|tv\\s*series|series|shows?)\\s*[:|\\-–—]\\s+")
    private val LIVE_LEAD = Regex("(?i)^(?:live\\s*tv|live)\\s*[:|\\-–—]\\s+")
    private val CHANNELS_TAIL = Regex("(?i)\\s+channels?$")

    /**
     * A label shown under [parent] ("Movies", "Series", "Live TV", a type such as "Movies", or empty).
     * [raw] may already have been through [Names.cat].
     */
    fun display(raw: String, parent: String = ""): String {
        val pieces = raw.split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
        if (pieces.size >= 2) {
            val last = pieces.last()
            val shortTag = last.length in 2..3 && last.all { it.isLetter() }
            val head = pieces.dropLast(if (shortTag) 1 else 0).joinToString(" · ")
            val cleaned = cleanOne(head, parent)
            return if (shortTag && !cleaned.contains(last, true)) "$cleaned · $last" else cleaned
        }
        return cleanOne(raw, parent)
    }

    private fun cleanOne(raw: String, parent: String): String {
        val (core, tag) = Names.split(raw)
        var s = stripRole(core)
        s = stripParent(s, parent)
        s = stripChannels(s)
        s = s.replace(EDGE, "").replace(SPACES, " ").trim()
        if (s.isEmpty()) s = core.ifBlank { raw.trim() }
        return if (tag.isNotEmpty() && !s.contains(tag, true)) "$s · $tag" else s
    }

    /** "Hindi Actor - Name", "English Actors: Name", "Actor Name", "Cinema by Actor: Name". */
    fun stripRole(raw: String): String {
        var s = raw.trim()
        CINEMA_BY.find(s)?.let { m ->
            val rest = s.substring(m.range.last + 1).trim()
            if (rest.isNotEmpty()) s = rest
        }
        ROLE_PREFIX.find(s)?.let { m ->
            val rest = s.substring(m.range.last + 1).trim()
            if (rest.isNotEmpty()) s = rest
        }
        ROLE_SPACE.find(s)?.let { m ->
            val rest = s.substring(m.range.last + 1).trim()
            if (rest.isNotEmpty()) s = rest
        }
        return s.replace(EDGE, "").replace(SPACES, " ").trim().ifEmpty { raw.trim() }
    }

    fun stripParent(raw: String, parent: String): String {
        val p = parent.trim()
        val r = when {
            p.equals("Movies", true) || p.equals("Movie", true) -> MOVIES_LEAD
            p.equals("Series", true) || p.equals("TV Shows", true) || p.equals("Shows", true) -> SERIES_LEAD
            p.equals("Live TV", true) || p.equals("Live", true) -> LIVE_LEAD
            else -> null
        }
        if (r != null) r.find(raw)?.let { m ->
            val rest = raw.substring(m.range.last + 1).trim()
            if (rest.isNotEmpty()) return rest
        }
        // A prefix that literally repeats the parent: "Sports - Cricket" under Sports
        if (p.isNotEmpty()) {
            val lit = Regex("(?i)^" + Regex.escape(p) + "\\s*[:|\\-–—]\\s+")
            lit.find(raw)?.let { m ->
                val rest = raw.substring(m.range.last + 1).trim()
                if (rest.isNotEmpty()) return rest
            }
        }
        return raw.trim()
    }

    fun stripChannels(raw: String): String {
        val m = CHANNELS_TAIL.find(raw) ?: return raw.trim()
        val rest = raw.substring(0, m.range.first).trim()
        return rest.ifEmpty { raw.trim() }
    }
}
