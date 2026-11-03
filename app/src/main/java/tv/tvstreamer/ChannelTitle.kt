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
}
