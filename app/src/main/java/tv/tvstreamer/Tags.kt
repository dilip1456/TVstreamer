package tv.tvstreamer

import java.text.SimpleDateFormat
import java.util.Locale

/** What the messy portal name says about a title. Every field may be empty / missing. */
data class Parsed(
    val title: String,     // cleaned title for matching ("The Matrix")
    val year: String,      // year hint ("1999") or ""
    val lang: String,      // language name ("Hindi") or ""
    val quality: String,   // quality label ("4K", "FHD", "HD", "CAM" ...) or ""
    val rank: Int,         // quality rank, higher is better (CAM = 0, unknown = 3, 4K = 6)
    val dub: Boolean,      // dubbed / dual audio variant
    val season: Int,       // season number from S01E02 / "Season 2", 0 = none
    val episode: Int       // episode number, 0 = none
)

/**
 * Language / quality / episode parser and title normalizer for portal names like
 * "HINDI CAM", "|EN| The Batman (2022) 4K", "Pathaan (2023) Hindi HDCAM", "Show S01E02".
 * "Dub" / "Dubbed" is a variant tag, not a language. Unknown language -> "" (shown as "Other").
 */
object Tags {
    // Compiled once (these run for every title and channel)
    private val TZ8 = Regex("\\s*[-–:|,]+\\s*$")
    private val TZ1 = Regex("^\\s*[|\\[(]?\\s*([A-Za-z]{2,10})\\s*[|\\])]\\s*|^\\s*([A-Z]{2,3})\\s*[-:–]\\s+|^\\s*([A-Za-z]{2,10})\\s*:\\s+")
    private val TZ2 = Regex("[\\[(|]([^\\])|]{1,30})[\\])|]")
    private val TZ3 = Regex("\\s[-–]\\s*([\\p{L} ]{3,30})$")
    private val TZ4 = Regex("(?i)([\\p{L}]+)\\s+(dubbed|dub)")
    private val TZ5 = Regex("\\s([\\p{L}]{4,12})\\s*$")
    private val TZ6 = Regex("(?i)\\b(4K|UHD|FHD|HD|SD|CAM|HDCAM|HDRip|WEB-?DL|1080p|720p)\\s*$")
    private val TZ7 = Regex("(?i)\\s[-–]\\s*([\\p{L} ]{3,30})$")
    private val RX1 = Regex("[^\\p{L}\\p{N}-]+")
    private val RX2 = Regex("\\w\\.\\w+\\.\\w")
    private val RX3 = Regex("\\[[^]]*]")
    private val RX4 = Regex("\\((?:[^)]*)\\)")
    private val RX5 = Regex("\\|[^|]{0,14}\\|")
    private val RX6 = Regex("^\\s*[|\\-–:]+\\s*")
    private val RX7 = Regex("^\\s*(?:[A-Z0-9+]{2,4}|[A-Za-z]{3,10})\\s*[|:»]\\s*")
    private val RX8 = Regex("^\\s*[A-Z0-9+]{2,4}\\s+[-–]\\s+")
    private val RX9 = Regex("^\\s*(4K|UHD|FHD|HD|SD)\\s*[-–]\\s*", RegexOption.IGNORE_CASE)
    private val RX10 = Regex("(?i)(?<![\\p{L}])([\\p{L}]+)\\s+(dubbed|dub)(?![\\p{L}])")
    private val RX11 = Regex("\\s+")
    private val RX12 = Regex("^\\s*[-–:|,]+\\s*")
    private val RX13 = Regex("\\p{M}+")
    private val RX14 = Regex("[^\\p{L}\\p{N}]+")
    private val RX15 = Regex("^(the|a|an) ")
    internal val RX16 = Regex("\\d+(\\.\\d+)?")
    const val OTHER = "Other"

    /** Language -> lowercase full-word aliases (matched as whole words, any case). */
    private val LANGS: Map<String, List<String>> = linkedMapOf(
        "English" to listOf("english", "eng", "hollywood"),
        "Hindi" to listOf("hindi", "bollywood"),
        "Tamil" to listOf("tamil", "kollywood"),
        "Telugu" to listOf("telugu", "tollywood"),
        "Malayalam" to listOf("malayalam", "mollywood"),
        "Kannada" to listOf("kannada", "sandalwood"),
        "Punjabi" to listOf("punjabi", "panjabi"),
        "Bengali" to listOf("bengali", "bangla"),
        "Marathi" to listOf("marathi"),
        "Gujarati" to listOf("gujarati"),
        "Bhojpuri" to listOf("bhojpuri"),
        "Odia" to listOf("odia", "oriya"),
        "Urdu" to listOf("urdu", "pakistani"),
        "Nepali" to listOf("nepali"),
        "Sinhala" to listOf("sinhala", "sinhalese"),
        "Arabic" to listOf("arabic", "arab", "عربي"),
        "Persian" to listOf("persian", "farsi"),
        "Turkish" to listOf("turkish", "türk", "turk"),
        "Spanish" to listOf("spanish", "español", "espanol", "latino", "castellano"),
        "Portuguese" to listOf("portuguese", "português", "portugues", "brazilian", "brasil"),
        "French" to listOf("french", "français", "francais", "vostfr"),
        "German" to listOf("german", "deutsch"),
        "Italian" to listOf("italian", "italiano"),
        "Dutch" to listOf("dutch", "nederlands"),
        "Polish" to listOf("polish", "polski"),
        "Russian" to listOf("russian"),
        "Greek" to listOf("greek"),
        "Korean" to listOf("korean", "k-drama", "kdrama"),
        "Japanese" to listOf("japanese"),
        "Chinese" to listOf("chinese", "mandarin", "cantonese"),
        "Thai" to listOf("thai"),
        "Vietnamese" to listOf("vietnamese"),
        "Indonesian" to listOf("indonesian"),
        "Filipino" to listOf("filipino", "tagalog", "pinoy"),
        "Swedish" to listOf("swedish"),
        "Albanian" to listOf("albanian"),
        "Romanian" to listOf("romanian"),
        "Hungarian" to listOf("hungarian"),
        "Hebrew" to listOf("hebrew"),
        "Kurdish" to listOf("kurdish"),
        "Afghan" to listOf("afghan", "pashto", "dari"),
        "African" to listOf("african", "swahili", "nollywood")
    )

    /** Upper-case codes, only trusted as standalone ALL-CAPS tokens ("EN|", "[HIN]", "HI -"). */
    private val CODES: Map<String, String> = mapOf(
        "EN" to "English", "ENG" to "English", "US" to "English", "UK" to "English", "USA" to "English",
        "HI" to "Hindi", "HIN" to "Hindi", "TA" to "Tamil", "TAM" to "Tamil", "TE" to "Telugu", "TEL" to "Telugu",
        "ML" to "Malayalam", "MAL" to "Malayalam", "KN" to "Kannada", "KAN" to "Kannada", "PA" to "Punjabi",
        "PB" to "Punjabi", "PUN" to "Punjabi", "BN" to "Bengali", "BEN" to "Bengali", "MR" to "Marathi",
        "MAR" to "Marathi", "GU" to "Gujarati", "GUJ" to "Gujarati", "UR" to "Urdu", "URD" to "Urdu",
        "AR" to "Arabic", "ARA" to "Arabic", "FA" to "Persian", "TR" to "Turkish", "TUR" to "Turkish",
        "ES" to "Spanish", "ESP" to "Spanish", "SPA" to "Spanish", "LAT" to "Spanish", "PT" to "Portuguese",
        "POR" to "Portuguese", "BR" to "Portuguese", "FR" to "French", "FRE" to "French", "FRA" to "French",
        "VF" to "French", "DE" to "German", "GER" to "German", "DEU" to "German", "IT" to "Italian",
        "ITA" to "Italian", "NL" to "Dutch", "PL" to "Polish", "POL" to "Polish", "RU" to "Russian",
        "RUS" to "Russian", "GR" to "Greek", "KO" to "Korean", "KR" to "Korean", "KOR" to "Korean",
        "JP" to "Japanese", "JPN" to "Japanese", "ZH" to "Chinese", "CN" to "Chinese", "CHI" to "Chinese",
        "TH" to "Thai", "VN" to "Vietnamese", "ID" to "Indonesian", "PH" to "Filipino", "SE" to "Swedish",
        "AL" to "Albanian", "RO" to "Romanian", "HU" to "Hungarian", "IL" to "Hebrew", "NP" to "Nepali",
        "IN" to "Hindi", "IND" to "Hindi", "SIN" to "Sinhala", "PK" to "Urdu"
    )

    /** Codes that are also ordinary English words / country codes: trusted only as a tag ("IN|", "[IT]", "US: "), never mid-text. */
    private val AMBIGUOUS = setOf("IN", "IT", "US", "ID", "AL", "SE", "TH", "PH", "IL", "BR", "TE", "TA", "HI", "DE", "ES", "SIN", "PK", "IND", "LAT", "PT")

    /** Film-industry nicknames: a language hint, but a real language word in the same name wins ("HOLLYWOOD HINDI" = Hindi). */
    private val INDUSTRY = setOf("hollywood", "bollywood", "kollywood", "tollywood", "mollywood", "sandalwood", "nollywood", "pakistani")

    private val ALIAS: Map<String, String> = LANGS.flatMap { (l, al) -> al.filter { !it.contains('-') }.map { it to l } }.toMap()
    private val TOKEN = Regex("[\\p{L}\\p{N}]+")
    private val DUBBED_IN = Regex("(?i)(?<![\\p{L}])([\\p{L}]{2,12})[\\s._-]*(?:dubbed|dub)(?![\\p{L}])|(?<![\\p{L}])dub(?:bed)?\\s+(?:in\\s+)?([\\p{L}]{2,12})")

    /** A code at a tag position: start of the name before | : - ] or inside [..] / |..| / (..). */
    private fun tagCode(text: String, range: IntRange): Boolean {
        val before = text.substring(0, range.first).trim()
        val after = text.substring(range.last + 1).trimStart()
        val open = before.isEmpty() || before.endsWith("[") || before.endsWith("(") || before.endsWith("|")
        val close = after.isEmpty() || after[0] in "|]):-–»/"
        return open && close
    }

    /** One token -> language, or "" (case matters for codes). */
    private fun tokenLang(tok: String, text: String, range: IntRange): Pair<String, Boolean>? {
        ALIAS[tok.lowercase()]?.let { return it to (tok.lowercase() in INDUSTRY) }
        if (tok.length in 2..3 && tok.all { it.isUpperCase() }) {
            val l = CODES[tok] ?: return null
            if (tok in AMBIGUOUS && !tagCode(text, range)) return null
            return l to false
        }
        return null
    }

    private data class Q(val label: String, val rank: Int, val re: Regex)
    private fun q(label: String, rank: Int, pattern: String, ignoreCase: Boolean = true) =
        Q(label, rank, Regex("(?<![\\p{L}\\p{N}])(?:$pattern)(?![\\p{L}\\p{N}])", if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()))

    /** Checked in order: the worst (CAM, TS) first so "HDCAM" is CAM, not HD. */
    private val QUALITY = listOf(
        q("CAM", 0, "HD-?CAM|CAM-?RIP|CAM|HQ-?CAM"),
        q("TS", 1, "HD-?TS|TELESYNC|HD-?TC|TELECINE|PRE-?DVD|DVD-?SCR|SCREENER"),
        q("TS", 1, "TS|TC", ignoreCase = false),
        q("4K", 6, "4K|UHD|2160p|8K"),
        q("FHD", 5, "FHD|1080p|1080i|BLU-?RAY|BD-?RIP|BRRIP"),
        q("HD", 4, "WEB-?DL|WEB-?RIP|WEB|HDTV|720p|AMZN|NF|DSNP"),
        q("HD", 4, "HD", ignoreCase = false),
        q("HDRip", 3, "HD-?RIP|DVD-?RIP|DVD"),
        q("SD", 2, "SD|480p|360p", ignoreCase = false)
    )
    private val DUB = Regex("(?<![\\p{L}])(dubbed|dub|dual[ -]?audio|multi[ -]?audio|hin[ -]?dub)(?![\\p{L}])", RegexOption.IGNORE_CASE)
    private val SXE = Regex("(?i)(?<![\\p{L}\\p{N}])S(\\d{1,2})\\s*[-.]?\\s*E(\\d{1,3})(?!\\d)")
    private val XFORM = Regex("(?<![\\p{N}])(\\d{1,2})x(\\d{2,3})(?![\\p{N}])")
    private val SEASON = Regex("(?i)(?<![\\p{L}])(?:season|saison|temporada|staffel|seizoen)\\s*(\\d{1,2})|(?<![\\p{L}\\p{N}])S(\\d{1,2})(?![\\p{L}\\p{N}])")
    private val EPISODE = Regex("(?i)(?<![\\p{L}])(?:episode|ep|épisode|episodio|folge)\\.?\\s*(\\d{1,4})")
    private val YEAR_BR = Regex("[(\\[]\\s*((?:19|20)\\d{2})\\s*[)\\]]")
    private val YEAR_ANY = Regex("(?<![\\p{N}])((?:19[3-9]|20[0-4])\\d)(?![\\p{N}])")

    private fun words(s: String) = s.split(RX1).filter { it.isNotEmpty() }

    /** Language named in [raw]. strict = only tag positions (prefix, brackets, "- Hindi", "Hindi Dubbed"), for item titles. */
    fun language(raw: String, strict: Boolean = false): String {
        val text = (if (strict) tagZone(raw) else raw).replace('_', ' ')
        if (text.isBlank()) return ""
        // 1. The dub language is the audio language: "HOLLYWOOD HINDI DUBBED", "Avatar Tamil Dub", "Dubbed in Telugu"
        for (m in DUBBED_IN.findAll(text)) {
            val w = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val l = ALIAS[w.lowercase()] ?: (if (w.length in 2..3) CODES[w.uppercase()] else null)
            if (l != null && w.lowercase() !in INDUSTRY) return l
        }
        // 2. The first real language word / tag code; industry nicknames only when nothing else names a language
        var industry = ""
        for (m in TOKEN.findAll(text)) {
            val (l, ind) = tokenLang(m.value, text, m.range) ?: continue
            if (!ind) return l
            if (industry.isEmpty()) industry = l
        }
        if (industry.isNotEmpty()) return industry
        val low = text.lowercase()
        for ((lang, al) in LANGS) if (al.any { it.contains('-') && low.contains(it) }) return lang
        return ""
    }

    /** Language for grouping and filtering: never empty, unknown = [OTHER]. */
    fun languageOrOther(raw: String, strict: Boolean = false): String = language(raw, strict).ifEmpty { OTHER }

    /**
     * The parts of a title that usually carry tags: a leading "XX|" / "XX -" prefix, any [..] / (..) / |..| group,
     * a trailing " - Hindi" and whatever follows the year. Titles such as "Hindi Medium" are not tags.
     */
    private fun tagZone(raw: String): String {
        val parts = mutableListOf<String>()
        TZ1.find(raw)?.let { parts += it.value }
        TZ2.findAll(raw).forEach { parts += it.groupValues[1] }
        TZ3.find(raw)?.let { parts += it.groupValues[1] }
        YEAR_ANY.findAll(raw).lastOrNull()?.let { parts += raw.substring(it.range.last + 1) }
        TZ4.findAll(raw).forEach { parts += it.groupValues[0] }
        // A trailing language word: "Pathaan Hindi", "Leo (2023) Tamil" (full names only, not codes)
        TZ5.find(raw.replace(TZ6, "").trimEnd())
            ?.let { m -> if (ALIAS[m.groupValues[1].lowercase()] != null) parts += m.groupValues[1] }
        return parts.joinToString(" | ")
    }

    fun quality(raw: String): Pair<String, Int> {
        val s = raw.replace('_', ' ').replace('.', ' ')
        for (x in QUALITY) if (x.re.containsMatchIn(s)) return x.label to x.rank
        return "" to 3
    }

    fun isDub(raw: String) = DUB.containsMatchIn(raw)

    /** (season, episode) from "S01E02", "1x02", "Season 2 Episode 5"; zeros when absent. */
    fun episodeOf(raw: String): Pair<Int, Int> {
        SXE.find(raw)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        XFORM.find(raw)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        val s = SEASON.find(raw)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() } ?: 0
        val e = EPISODE.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return s to e
    }

    /** Name looks like a single episode (S01E02, 1x02, "Episode 5"). */
    fun hasEpisodeMarker(raw: String): Boolean = SXE.containsMatchIn(raw) || XFORM.containsMatchIn(raw) || EPISODE.containsMatchIn(raw)

    private val JUNK = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(4K|8K|UHD|FHD|HDR10|HDR|DV|1080p|1080i|720p|2160p|480p|HEVC|H\\.?265|H\\.?264|x264|x265|10bit|WEB-?DL|WEB-?RIP|BLU-?RAY|" +
        "BRRIP|BDRIP|DVDRIP|DVDSCR|HD-?RIP|HD-?CAM|CAM-?RIP|CAM|HD-?TS|HD-?TC|TELESYNC|PRE-?DVD|HDTV|AAC|DDP?5\\.?1|ATMOS|MULTI-?SUBS?|MULTI|" +
        "SUBBED|DUBBED|DUB|DUAL[ -]?AUDIO|ESUBS?|VOSTFR|AMZN|DSNP|HMAX|NF|PROPER|REPACK|UNCUT|EXTENDED)(?![\\p{L}\\p{N}])")

    /** Full parse of a raw portal name. */
    fun parse(raw: String): Parsed {
        val (ql, rank) = quality(raw)
        val (season, ep) = episodeOf(raw)
        var s = raw.replace('_', ' ')
        // Dots used as spaces ("Avatar.2009.1080p") but keep "Mr. Robot", "U.S."
        if (RX2.containsMatchIn(s) && !s.contains(' ')) s = s.replace('.', ' ')
        var year = YEAR_BR.find(s)?.groupValues?.get(1) ?: ""
        s = s.replace(RX3, " ").replace(RX4, " ")
        s = s.replace(RX5, " ")
        // Leading tag prefixes: "EN - ", "4K: ", "NF| ", "HINDI - "
        repeat(3) {
            s = s.replace(RX6, "")
                .replace(RX7, { m -> if (isTagWord(m.value)) "" else m.value })
                .replace(RX8, { m -> if (isTagWord(m.value)) "" else m.value })
                .replace(RX9, "")
        }
        // Trailing " - Hindi", " Hindi Dubbed", "(Hindi)" already gone
        s = s.replace(TZ7) { m -> if (language(m.groupValues[1]).isNotEmpty() || JUNK.containsMatchIn(m.groupValues[1])) " " else m.value }
        s = s.replace(RX10) { m -> if (language(m.groupValues[1]).isNotEmpty()) " " else m.value }
        // Everything after a bare year is usually tags: "Avatar 2009 1080p BluRay"
        YEAR_ANY.findAll(s).lastOrNull()?.let { m ->
            val before = s.substring(0, m.range.first).trim()
            if (before.length >= 2) { if (year.isEmpty()) year = m.groupValues[1]; s = before }
        }
        s = s.replace(SXE, " ").replace(XFORM, " ").replace(SEASON, " ").replace(EPISODE, " ")
            .replace(JUNK, " ")
        // A trailing language word left over: "Pathaan Hindi"
        val ws = s.trim().split(RX11).toMutableList()
        while (ws.size > 1 && language(ws.last()).isNotEmpty()) ws.removeAt(ws.size - 1)
        s = ws.joinToString(" ")
            .replace(TZ8, "")
            .replace(RX12, "")
            .replace(RX11, " ").trim()
        if (s.length < 2) s = Names.title(raw)
        return Parsed(s, year, language(raw, strict = true), ql, rank, isDub(raw), season, ep)
    }

    private fun isTagWord(prefix: String): Boolean {
        val w = prefix.trim().trimEnd('|', ':', '»', '-', '–').trim()
        return CODES.containsKey(w.uppercase()) && w.all { !it.isLetter() || it.isUpperCase() } ||
            language(w).isNotEmpty() || quality(w).first.isNotEmpty() || w.length in 2..4 && w.all { it.isUpperCase() || it.isDigit() || it == '+' }
    }

    /** Lowercase letters / digits only, for comparing titles. */
    fun norm(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(RX13, "").replace("&", " and ").replace(RX14, " ").trim()
        .replace(RX15, "")

    /** 0..1 similarity of two titles (token overlap blended with edit distance). */
    fun similarity(a: String, b: String): Double {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val tx = x.split(' ').distinct(); val ty = y.split(' ').distinct()
        // Fuzzy token overlap: "pathaan" ~ "pathan"
        fun close(a: String, b: String) = a == b || a.length > 3 && b.length > 3 && 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length) >= 0.75
        val common = tx.count { a -> ty.any { close(a, it) } }
        val jac = common.toDouble() / (tx.size + ty.size - common)
        val lev = 1.0 - levenshtein(x, y).toDouble() / maxOf(x.length, y.length)
        return 0.5 * jac + 0.5 * lev
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private val DATE_FORMATS = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd", "dd.MM.yyyy", "dd/MM/yyyy")

    /** Portal "added" text -> epoch millis; 0 when missing or unreadable. */
    fun parseDate(s: String): Long {
        val t = s.trim()
        if (t.isEmpty()) return 0
        t.toLongOrNull()?.let { n -> return if (n > 100_000_000_000L) n else if (n > 100_000_000L) n * 1000 else 0 }
        for (f in DATE_FORMATS) {
            runCatching { SimpleDateFormat(f, Locale.US).apply { isLenient = false }.parse(t)?.time }.getOrNull()?.let { return it }
        }
        return 0
    }
}

// ---- Item helpers (portal data only; any of these may be missing -> such items sort last) ----

val Item.parsed: Parsed get() = ParsedCache.get(name)

private object ParsedCache {
    private val m = java.util.concurrent.ConcurrentHashMap<String, Parsed>()
    fun get(n: String): Parsed = m[n] ?: Tags.parse(n).also { if (m.size < 20000) m[n] = it }
}

/** Series content: marked as a series by the portal, a dug-out episode, or a name with S01E02 / Episode markers. */
val Item.seriesLike: Boolean get() = isSeries || isEpisode() || (kind == "vod" && Tags.hasEpisodeMarker(name))

val Item.addedMs: Long get() = Tags.parseDate(added)
val Item.ratingNum: Double get() = rating.replace(',', '.').let { Tags.RX16.find(it)?.value?.toDoubleOrNull() } ?: 0.0
val Item.yearNum: Int get() = year.toIntOrNull() ?: parsed.year.toIntOrNull() ?: 0
