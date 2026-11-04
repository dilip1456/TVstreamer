package tv.tvstreamer

/**
 * Readable Live TV channel names. Portals often put the channel number into the name ("123 | BBC One"), use
 * superscript quality tags ("BBC Oneᴴᴰ"), country tags in odd forms ("|UK| BBC One", "[UK] BBC One"), or give only a
 * number as the name. [pick] chooses the best of the portal's fields, [display] is applied to every channel (also cached
 * ones) and falls back to "Channel <number> · <group>" when no real name exists. The number itself is shown as a badge.
 */
object ChannelTitle {
    private val SUP = mapOf('ᴬ' to 'A', 'ᴮ' to 'B', 'ᶜ' to 'C', 'ᴰ' to 'D', 'ᴱ' to 'E', 'ᶠ' to 'F', 'ᴳ' to 'G', 'ᴴ' to 'H',
        'ᴵ' to 'I', 'ᴶ' to 'J', 'ᴷ' to 'K', 'ᴸ' to 'L', 'ᴹ' to 'M', 'ᴺ' to 'N', 'ᴼ' to 'O', 'ᴾ' to 'P', 'ᴿ' to 'R', 'ˢ' to 'S',
        'ᵀ' to 'T', 'ᵁ' to 'U', 'ⱽ' to 'V', 'ᵂ' to 'W', 'ʰ' to 'H', 'ᵈ' to 'D', 'ᵏ' to 'K', '⁰' to '0', '¹' to '1', '²' to '2',
        '³' to '3', '⁴' to '4', '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9', '⁺' to '+')
    private val DECOR = Regex("[★☆●•◉▶►◆◇■□▪▫♦✪✦✧⭐✔✓❤♥]")
    private val SEP_LEAD = Regex("^#?(\\d{1,5})\\s*(?:\\||:|\\.|\\)|-|–|—)+\\s*")
    private val COUNTRY = listOf(
        Regex("^\\|\\s*([A-Za-z]{2,4})\\s*\\|\\s*"),          // |UK| BBC
        Regex("^\\[\\s*([A-Za-z]{2,4})\\s*\\]\\s*"),          // [UK] BBC
        Regex("^\\(\\s*([A-Z]{2,4})\\s*\\)\\s*"),             // (UK) BBC
        Regex("^([A-Z]{2,4})\\s*\\|\\s*"),                    // UK | BBC
        Regex("^([A-Z]{2,4})\\s*(?::|-|–)\\s+")              // UK: BBC, UK - BBC
    )
    private val EDGE = Regex("^[\\s|#=*_.:\\-–—~>]+|[\\s|#=*_.:\\-–—~<]+$")
    private val SPACES = Regex("\\s+")
    private val STOP = setOf("live", "play", "playlist", "index", "stream", "streams", "ch", "channel", "mpegts", "tv", "iptv",
        "localhost", "hls", "video", "m3u8", "ts", "get", "php", "load", "media", "mono")

    /** Superscript tags -> normal text with a space before ("BBC Oneᴴᴰ" -> "BBC One HD"). */
    private fun unSup(s: String): String {
        val b = StringBuilder()
        var inSup = false
        for (c in s) {
            val m = SUP[c]
            if (m != null) {
                if (!inSup && b.isNotEmpty() && b.last() != ' ') b.append(' ')
                b.append(m); inSup = true
            } else {
                if (inSup && c.isLetterOrDigit()) b.append(' ')
                b.append(c); inSup = false
            }
        }
        return b.toString()
    }

    /** Cleaned name: no leading numbering ("123 | ", "12. ", or [num] itself), tidy country tag ("UK: "), plain quality tags. */
    fun clean(raw: String, num: Int = 0): String {
        var s = unSup(raw).replace(DECOR, " ").replace('\u00A0', ' ').replace(SPACES, " ").trim()
        s = s.replace(EDGE, "")
        repeat(2) {
            SEP_LEAD.find(s)?.let { m -> if (m.range.last + 1 < s.length) s = s.substring(m.range.last + 1).trim() }
            if (num > 0) {
                val p = "$num "
                if (s.startsWith(p) && !numeric(s.substring(p.length))) s = s.substring(p.length).trim()
            }
        }
        for (r in COUNTRY) {
            val m = r.find(s) ?: continue
            val rest = s.substring(m.range.last + 1).trim()
            if (rest.isNotEmpty() && !numeric(rest)) { s = m.groupValues[1].uppercase() + ": " + rest; break }
        }
        return s.replace(EDGE, "").replace(SPACES, " ").trim()
    }

    /** True when the text has no letters (only digits, spaces, punctuation), e.g. "123", "#45", "12 - 3". */
    fun numeric(s: String): Boolean = s.none { it.isLetter() }

    /** "BBCOne.uk" / "bbc_one.uk" -> "UK: BBC One"; "" when it does not look like a name. */
    fun fromXmltv(id: String): String {
        var s = id.substringBefore('@').trim()
        if (s.isEmpty()) return ""
        var country = ""
        Regex("\\.([A-Za-z]{2,3})$").find(s)?.let { country = it.groupValues[1].uppercase(); s = s.substring(0, it.range.first) }
        s = s.replace(Regex("[._\\-]+"), " ")
            .replace(Regex("(?<=[a-z])(?=[A-Z0-9])|(?<=[A-Z])(?=[A-Z][a-z])"), " ").trim()
        if (numeric(s)) return ""
        s = s.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w ->
            when {
                w.length <= 3 && w.all { it.isLowerCase() } -> w.uppercase()
                w.all { it.isLowerCase() } -> w.replaceFirstChar { it.uppercase() }
                else -> w
            }
        }
        return if (country.isNotEmpty()) "$country: $s" else s
    }

    /** A channel name hidden in the stream address ("http://h/live/BBC_One.m3u8", "...?stream=Sky_News"), else "". */
    fun fromCmd(cmd: String): String {
        val url = cmd.trim().substringAfterLast(' ')
        val q = Regex("(?i)[?&](?:stream|channel|name|ch)=([^&]+)").find(url)?.groupValues?.get(1)
        val seg = q ?: url.substringBefore('?').trimEnd('/').substringAfterLast('/').substringBeforeLast('.')
        val s = runCatching { java.net.URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg).replace('_', ' ').replace('+', ' ').trim()
        if (s.count { it.isLetter() } < 3 || s.length > 40 || s.lowercase() in STOP) return ""
        if (Regex("[0-9a-fA-F]{16,}").containsMatchIn(s) || (' ' !in s && s.length > 20)) return ""   // token / hash
        return clean(s)
    }

    /** The best portal name: name, o_name, the xmltv id, then a name in the stream address; "" when all are only numbers. */
    fun pick(name: String, oName: String = "", xmltv: String = "", cmd: String = "", num: Int = 0): String {
        for (c in listOf(clean(name, num), clean(oName, num), fromXmltv(xmltv), fromCmd(cmd))) if (c.isNotEmpty() && !numeric(c)) return c
        return ""
    }

    /** The name shown everywhere: cleaned, or "Channel <number> · <group>" when there is no real name. */
    fun display(name: String, num: Int, group: String): String {
        val c = clean(name, num)
        if (c.isNotEmpty() && !numeric(c)) return c
        val n = num.takeIf { it > 0 } ?: Regex("\\d+").find(name)?.value?.toIntOrNull() ?: 0
        val g = clean(group)
        return listOfNotNull(if (n > 0) "Channel $n" else "Channel", g.takeIf { it.isNotEmpty() && !numeric(it) }).joinToString(" · ")
    }

    /** Two-letter initials for a channel without a logo, skipping a country tag ("UK: BBC One" -> "BO"). */
    fun initials(name: String): String {
        val core = name.substringAfter(": ", name).substringBefore(" · ").ifBlank { name }
        val words = core.split(' ', '-', '+').filter { w -> w.any { it.isLetterOrDigit() } }
        return when {
            words.size >= 2 -> words.take(2).joinToString("") { w -> w.first { it.isLetterOrDigit() }.toString() }
            else -> core.filter { it.isLetterOrDigit() }.take(2)
        }.uppercase()
    }

    // ---- Tidy names for lists (v1.52) ----
    private val TAG_ANY = Regex("[\\[(]\\s*[A-Za-z]{2,4}(?:[-_/][A-Za-z]{2,3})?\\s*[\\])]|\\|[A-Za-z]{2,3}\\|")
    private val LEAD_TAG = Regex("^(?:[A-Z]{2,3}(?:[-_/][A-Z]{2,3})?)\\s*(?::|\\||-|–)\\s*")
    private val QUALITY = Regex("(?i)(?<![\\p{L}\\d+])(?:FHD|UHD|HD|SD|4K|8K|HEVC|H\\.?26[45]|1080[pi]?|720[pi]?|2160p|576[pi]?|\\d{2}\\s?FPS|HDR|VIP|RAW|BACKUP|BACK ?UP|LQ|HQ|MULTI|DIRECT|ORIG|24/7)(?![\\p{L}\\d])")
    private val BRACKETS_EMPTY = Regex("[\\[(]\\s*[\\])]")
    private val NUM_LEAD = Regex("^\\d{1,5}\\s+(?=\\p{L})")
    private val NUM_TRAIL_TAG = Regex("\\s+#\\d+$")
    private val PIPES = Regex("\\s*[|¦]+\\s*")
    private val CALLSIGN = Regex("^[KW][A-Z]{2,3}(?:-(?:TV|DT|CD|LD|HD))?$")
    private val NETWORKS = setOf("ABC", "NBC", "CBS", "FOX", "CW", "PBS", "MNT", "MYTV", "MY", "ION", "TELEMUNDO", "UNIVISION",
        "UNIMAS", "IND", "US", "USA", "INDEPENDENT", "NEWS", "LOCAL", "TV", "THE", "CHANNEL", "CH", "NET", "NETWORK", "ME", "METV", "ANTENNA")
    private val STATES = setOf("AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS",
        "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK",
        "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY")

    /** A word in normal casing: "BBC" and "ESPN2" stay, "SPORTS" -> "Sports", "new york" -> "New York". */
    private fun caseWord(w: String, allWords: Boolean): String {
        val letters = w.filter { it.isLetter() }
        if (letters.isEmpty()) return w
        val upper = letters.all { it.isUpperCase() }
        val lower = letters.all { it.isLowerCase() }
        return when {
            upper && !allWords && (letters.length <= 4 && letters !in COMMON || w.any { it.isDigit() }) -> w
            upper || (lower && w.length > 1) -> w.lowercase().split('-').joinToString("-") { it.replaceFirstChar { c -> c.uppercase() } }
            else -> w
        }
    }

    /** Title casing only when the name is shouted (mostly capitals) or all lower case; mixed names are kept. */
    private fun tidyCase(s: String): String {
        val letters = s.filter { it.isLetter() }
        if (letters.isEmpty()) return s
        val upperShare = letters.count { it.isUpperCase() }.toFloat() / letters.length
        if (upperShare < 0.8f && letters.any { it.isUpperCase() }) return s
        return s.split(' ').joinToString(" ") { caseWord(it, false) }
    }

    /**
     * The name shown in lists and the player: no country / language tags ("UK:", "|IN|", "[AR]"), no numbering, pipes
     * or quality tags (HD, FHD, 4K, 1080p, backup, VIP…), single spaces, tidy casing. With [local] (US local stations)
     * the callsign and city are kept: "US: ABC 7 WABC NEW YORK HD" -> "WABC New York". The number is shown as a badge.
     */
    fun pretty(raw: String, local: Boolean = false): String {
        var s = clean(raw)
        s = s.replace(LEAD_TAG, "")
        s = s.replace(TAG_ANY) { m -> if (m.value.trim('[', ']', '(', ')', '|', ' ').uppercase() in KEEP_TAGS) m.value else " " }
        s = s.replace(QUALITY, " ").replace(BRACKETS_EMPTY, " ").replace(PIPES, " ").replace(NUM_TRAIL_TAG, "")
        s = s.replace(EDGE, "").replace(SPACES, " ").trim()
        s = s.replace(NUM_LEAD, "")
        if (local) localName(s)?.let { return it }
        val out = tidyCase(s).replace(SPACES, " ").trim()
        return if (out.isEmpty() || numeric(out)) clean(raw) else out
    }
    private val COMMON = setOf("ONE", "TWO", "NEWS", "MAX", "GOLD", "LIFE", "PLUS", "LIVE", "STAR", "SKY", "SONY", "ZEE", "MAIN",
        "THE", "AND", "OF", "WEST", "EAST", "HITS", "KIDS", "FOOD", "HOME", "CINE", "FILM", "BOX", "ZONE", "CITY", "ARENA",
        "TIME", "NOW", "GOLF", "BEST", "PRO", "ART", "FUN", "JAZZ", "ROCK", "POP", "TOP", "WILD", "DRAMA", "ASIA", "PRIME", "MOVIE")
    private val KEEP_TAGS = setOf("+1", "PLUS")

    /** "ABC 7 WABC New York, NY" -> "WABC New York"; null when there is no callsign. */
    private fun localName(s: String): String? {
        val words = s.replace(Regex("[(),/:|]"), " ").split(' ').filter { it.isNotBlank() }
        val ci = words.indexOfFirst { CALLSIGN.matches(it) }
        if (ci < 0) return null
        val call = words[ci].substringBefore('-')
        fun cityOf(ws: List<String>) = ws.filter { w ->
            val u = w.uppercase()
            w.any { it.isLetter() } && u !in NETWORKS && !CALLSIGN.matches(w) && w.none { it.isDigit() }
        }
        var city = cityOf(words.drop(ci + 1))
        if (city.isEmpty()) city = cityOf(words.take(ci))
        if (city.size > 1 && city.last().uppercase() in STATES && city.last().length == 2) city = city.dropLast(1)
        val c = city.joinToString(" ") { caseWord(it, true) }
        return if (c.isEmpty()) call else "$call $c"
    }

    /** Category name for a section header: no tags, pipes or numbering, tidy casing ("UK | ENTERTAINMENT" -> "Entertainment"). */
    fun category(raw: String): String {
        val p = pretty(raw).ifBlank { raw.trim() }
        val letters = p.filter { it.isLetter() }
        return if (letters.isNotEmpty() && letters.all { it.isUpperCase() }) p.split(' ').joinToString(" ") { caseWord(it, true) } else p
    }
}
