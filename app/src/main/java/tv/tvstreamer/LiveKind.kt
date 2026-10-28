package tv.tvstreamer

/**
 * Live channel classifier (pure logic, unit tested): a type (News, Sports, …) and a language from the portal
 * category name and the channel name. Best guesses from common IPTV naming; anything unknown -> "Other".
 */
/** Type and language of one channel. */
data class ChInfo(val type: String, val lang: String)

object LiveKind {
    private val SPACES = Regex("\\s+")  // first: norm() needs it while the tables below are built
    const val NEWS = "News"
    const val SPORTS = "Sports"
    const val MUSIC = "Music"
    const val MOVIES = "Movies"
    const val ENT = "Entertainment"
    const val KIDS = "Kids"
    const val RELIGIOUS = "Religious"
    const val DOCS = "Documentary & Lifestyle"
    const val REGIONAL = "Regional / General"
    const val OTHER = "Other"

    /** Display order of the type chips. */
    val ORDER = listOf(NEWS, SPORTS, ENT, MOVIES, MUSIC, KIDS, DOCS, RELIGIOUS, REGIONAL, OTHER)

    /** Checked in this order, so "Fox Sports" is Sports and "Sony Max" is Movies before the generic "Sony" (Entertainment). */
    private val ALIASES: List<Pair<String, List<String>>> = listOf(
        KIDS to listOf("kids", "kid", "children", "cartoon", "cartoons", "cartoon network", "nick", "nickelodeon", "nick jr",
            "nicktoons", "disney", "disney junior", "disney jr", "pogo", "hungama", "baby", "babytv", "baby tv", "cbeebies",
            "cbbc", "boomerang", "toons", "sonic", "discovery kids", "kinder", "infantil", "chutti", "kushi tv", "duck tv", "jim jam"),
        RELIGIOUS to listOf("religious", "religion", "devotional", "spiritual", "gospel", "church", "christian", "islam",
            "islamic", "quran", "madani", "peace tv", "aastha", "sanskar", "bhakti", "satsang", "gurbani", "ewtn", "daystar",
            "tbn", "iqraa", "sadhna", "darshan", "god tv", "shubh", "vedic", "jinvani", "paigam"),
        NEWS to listOf("news", "noticias", "nachrichten", "actualites", "actualités", "info", "samachar", "khabar", "akhbar",
            "cnn", "msnbc", "cnbc", "bloomberg", "sky news", "al jazeera", "aljazeera", "euronews", "france 24", "france24",
            "dw", "aaj tak", "aajtak", "ndtv", "times now", "republic", "abp", "news18", "news 18", "wion", "tv9",
            "india today", "headlines", "newsmax", "fox business", "c-span", "cspan", "geo news", "ary news", "dunya",
            "samaa", "zee business", "et now", "mirror now", "rt", "nhk world", "cgtn", "trt world", "i24"),
        SPORTS to listOf("sport", "sports", "espn", "bein", "dazn", "eurosport", "tsn", "sportsnet", "supersport",
            "willow", "ten sports", "sony ten", "ptv sports", "star sports", "sky sports", "fox sports", "bt sport", "tnt sports",
            "nba", "nfl", "nhl", "mlb", "ufc", "wwe", "golf", "cricket", "football", "soccer", "f1", "formula 1", "racing",
            "tennis", "deportes", "futbol", "fútbol", "boxing", "premier league", "laliga", "la liga", "serie a", "bundesliga",
            "ligue 1", "ipl", "astro cricket", "sport tv", "arena sport", "match", "setanta", "dsport", "sports18", "eleven sports"),
        MUSIC to listOf("music", "musik", "musica", "música", "musique", "mtv", "vh1", "9xm", "9x jalwa", "9x tashan",
            "b4u music", "zoom", "mastiii", "mastii", "sangeet", "sangeet bangla", "hits", "trace", "4music", "kiss",
            "box hits", "clubland", "sony mix", "mtv beats", "music india", "music india", "zing", "e24", "radio", "jalwa",
            "isaiaruvi", "sun music", "raj musix", "kappa tv", "mh one", "pitaara", "balle balle"),
        MOVIES to listOf("movie", "movies", "cinema", "cine", "cinemas", "film", "films", "filmy", "hbo", "cinemax",
            "showtime", "starz", "sky cinema", "amc", "tcm", "mgm", "max", "sony max", "star gold", "zee cinema",
            "zee action", "zee bollywood", "&pictures", "and pictures", "utv movies", "utv action", "b4u movies",
            "sony pix", "movies now", "mn+", "romedy", "peliculas", "películas", "cine+", "colors cineplex", "star movies",
            "ktv", "sun movies", "zee talkies", "gemini movies", "manoranjan", "goldmines", "dhinchaak", "epix", "paramount network",
            "flix", "action", "thriller", "classic movies", "hallmark movies", "lifetime movies"),
        DOCS to listOf("documentary", "documentaries", "docu", "docs", "discovery", "national geographic", "nat geo",
            "natgeo", "history", "animal planet", "travel", "travelxp", "food", "foodfood", "food food", "tlc", "lifestyle",
            "living", "hgtv", "fashion", "nature", "science", "investigation", "smithsonian", "viceland", "curiosity",
            "epic", "fox life", "bbc earth", "love nature", "cooking", "home", "outdoor", "wild", "nasa", "knowledge",
            "zee zest", "living foodz", "sony bbc earth"),
        ENT to listOf("entertainment", "ent", "gec", "general entertainment", "drama", "series", "serials", "serial",
            "comedy", "reality", "star plus", "starplus", "colors", "sony", "sony sab", "sab", "zee tv", "&tv", "and tv",
            "star bharat", "dangal", "sun tv", "star vijay", "vijay tv", "zee tamil", "zee telugu", "zee kannada",
            "gemini", "etv", "asianet", "mazhavil", "flowers", "star maa", "colors kannada", "udaya", "surya", "kalaignar",
            "abc", "nbc", "cbs", "fox", "the cw", "cw", "itv", "bbc one", "bbc two", "bbc three", "bbc", "channel 4",
            "channel 5", "bravo", "tnt", "tbs", "usa network", "fx", "syfy", "comedy central", "lifetime",
            "hallmark", "hum tv", "har pal geo", "ary digital", "geo entertainment", "star jalsha", "zee bangla", "colors bangla",
            "star pravah", "zee marathi", "colors marathi", "ptc punjabi", "zee punjabi", "big magic", "shemaroo", "ishara"),
        REGIONAL to listOf("regional", "local", "general", "variety", "generalist", "general tv", "public", "national")
    )
    // Aliases normalized and padded once (" sky news "), so matching is a plain substring test per alias
    private val PHRASES: List<Pair<String, List<String>>> by lazy {
        ALIASES.map { (t, al) -> t to al.map { norm(it) }.filter { it.isNotEmpty() }.map { " $it " } }
    }

    /** Type from a name: the first alias (whole words) found, else "". */
    fun typeOf(text: String): String {
        val n = " " + norm(text) + " "
        for ((t, al) in PHRASES) if (al.any { n.contains(it) }) return t
        return ""
    }

    /**
     * The category decides when it names a specific type ("UK | SPORTS"), else the channel name ("ESPN 2" in "USA"),
     * else a generic category ("ENTERTAINMENT", "REGIONAL"), else Regional / General when only a language / country
     * is known, else Other.
     */
    fun type(cat: String, name: String): String = classify(facts(cat), name).type

    /** What a category name says (type, language word, country), worked out once per category. */
    class CatFacts internal constructor(val type: String, val lang: String, val country: String)
    fun facts(cat: String) = CatFacts(typeOf(cat), Tags.language(cat), country(cat))

    /** Type and language of a channel in a category with [f] (same rules as [type] and [language]). */
    fun classify(f: CatFacts, name: String): ChInfo {
        val lang = f.lang.ifEmpty { Tags.language(name.lowercase()) }.ifEmpty { f.country }.ifEmpty { country(name) }
        val c = f.type
        val type = when {
            c.isNotEmpty() && c != ENT && c != REGIONAL -> c
            else -> typeOf(name).ifEmpty { c.ifEmpty { if (lang.isNotEmpty()) REGIONAL else OTHER } }
        }
        return ChInfo(type, lang.ifEmpty { Tags.OTHER })
    }

    /** Country words in category names, as a language. India has many languages: Hindi unless the channel says otherwise. */
    private val COUNTRIES: List<Pair<String, List<String>>> = listOf(
        "English" to listOf("uk", "usa", "us", "united kingdom", "united states", "america", "american", "british", "canada",
            "canadian", "australia", "australian", "new zealand", "ireland", "irish", "scotland"),
        "Spanish" to listOf("spain", "mexico", "méxico", "argentina", "colombia", "chile", "peru", "perú", "ecuador",
            "venezuela", "paraguay", "uruguay", "bolivia", "latam", "latin", "dominican", "puerto rico", "costa rica",
            "guatemala", "honduras", "el salvador", "panama", "cuba"),
        "Portuguese" to listOf("brazil", "portugal", "brasileiro"),
        "French" to listOf("france", "belgique", "quebec", "québec"),
        "German" to listOf("germany", "deutschland", "austria", "österreich", "schweiz"),
        "Italian" to listOf("italy", "italia"),
        "Dutch" to listOf("netherlands", "holland", "nederland"),
        "Polish" to listOf("poland", "polska"),
        "Turkish" to listOf("turkey", "türkiye", "turkiye"),
        "Urdu" to listOf("pakistan"),
        "Bengali" to listOf("bangladesh"),
        "Arabic" to listOf("saudi", "egypt", "uae", "emirates", "qatar", "kuwait", "lebanon", "iraq", "jordan",
            "morocco", "algeria", "tunisia", "syria", "oman", "bahrain", "libya", "mbc", "osn"),
        "Hindi" to listOf("india", "indian", "desi"),
        "Greek" to listOf("greece", "cyprus"),
        "Russian" to listOf("russia", "ukraine"),
        "Filipino" to listOf("philippines"),
        "Korean" to listOf("korea"),
        "Japanese" to listOf("japan"),
        "Chinese" to listOf("china", "hong kong", "taiwan"),
        "Vietnamese" to listOf("vietnam"),
        "Persian" to listOf("iran"),
        "Albanian" to listOf("albania", "kosovo"),
        "Romanian" to listOf("romania", "moldova"),
        "Swedish" to listOf("sweden", "sverige"),
        "Nepali" to listOf("nepal"),
        "Sinhala" to listOf("sri lanka"),
        "African" to listOf("africa", "nigeria", "ghana", "kenya")
    ).map { (l, al) -> l to al.map { " " + norm(it) + " " } }

    private fun country(text: String): String {
        val n = " " + norm(text) + " "
        for ((l, al) in COUNTRIES) if (al.any { n.contains(it) }) return l
        return ""
    }

    /**
     * Language: a language word in the category ("HINDI", "|TA|"), else a language word in the channel name
     * ("Zee Tamil", "Colors Bangla"; codes are ignored there), else the category's country ("UK", "MEXICO", "INDIA"),
     * else "" (shown as Other).
     */
    fun language(cat: String, name: String): String {
        Tags.language(cat).takeIf { it.isNotEmpty() }?.let { return it }
        Tags.language(name.lowercase()).takeIf { it.isNotEmpty() }?.let { return it }
        country(cat).takeIf { it.isNotEmpty() }?.let { return it }
        return country(name)
    }

    private fun norm(s: String): String = Tags.norm(s.replace("&", " and ").replace("+", " plus ")).replace(SPACES, " ").trim()
}
