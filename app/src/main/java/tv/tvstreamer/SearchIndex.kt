package tv.tvstreamer

/**
 * Search matching (1.69, pure logic, unit tested). Used on the whole saved catalog and the channel list, offline.
 *
 *  - Text is folded: lower case, accents removed ("Amélie" = "amelie"), punctuation = space, "&" = "and",
 *    and the portal's decorations are ignored because titles are matched on [Item.parsed] (no "|EN|", "Movies –",
 *    language tags, quality or year in the title).
 *  - Every query word must match a word of the title (or of the cast / director / category text):
 *    a word that starts with it ("bat" -> "batman"), the words run together ("spiderman" = "spider man"),
 *    or for longer words one typo ("pathan" ~ "pathaan", "intersteller" ~ "interstellar").
 *  - A 4-digit year in the query is a filter (the title's year, or the year in the name), never a required title word.
 */
object SearchIndex {
    private val WORD = Regex("[^\\p{L}\\p{N}]+")
    private val MARKS = Regex("\\p{M}+")
    private val YEAR = Regex("^(19|20)\\d{2}$")
    /** Query words that carry no meaning for matching (kept short on purpose: "the" alone still finds "The"). */
    private val NOISE = setOf("the", "a", "an", "of", "and", "movie", "movies", "film", "films", "series", "show", "tv")

    fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(MARKS, "").replace("ß", "ss").replace("æ", "ae").replace("œ", "oe").replace("ø", "o").replace("đ", "d")
        .replace("&", " and ").replace(WORD, " ").trim()

    fun words(s: String): List<String> = fold(s).split(' ').filter { it.isNotEmpty() }

    private val memo = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    /** [words], remembered: the catalog's titles / categories are folded once, so later searches are instant. */
    fun cachedWords(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        memo[s]?.let { return it }
        if (memo.size > 250_000) memo.clear()
        return words(s).also { memo[s] = it }
    }

    /** The query split into words to match and an optional year filter. Noise words are dropped unless they are all there is. */
    class Query(val words: List<String>, val year: String, val raw: String) {
        val empty get() = words.isEmpty() && year.isEmpty()
        val squashed: String = words.joinToString("")
        val spaced: String = words.joinToString(" ")
    }

    fun query(q: String): Query {
        val all = words(q)
        val year = all.firstOrNull { YEAR.matches(it) }.orEmpty()
        val rest = all.filter { it != year }
        val core = rest.filter { it !in NOISE }
        return Query(core.ifEmpty { rest }, year, q)
    }

    /** Edit distance with an early exit above [max]. */
    fun within(a: String, b: String, max: Int): Boolean {
        if (kotlin.math.abs(a.length - b.length) > max) return false
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var best = i
            for (j in 1..b.length) {
                val v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                cur[j] = v
                if (v < best) best = v
            }
            if (best > max) return false
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length] <= max
    }

    /** One typo: same first letter (typos there are rare, and it keeps this fast on big catalogs). */
    private fun typo(q: String, h: String): Boolean {
        if (q[0] != h[0]) return false
        if (q.length >= 5 && h.length >= 4) {
            if (within(q, h, 1)) return true
            if (h.length > q.length && within(q, h.substring(0, q.length), 1)) return true
        }
        return q.length >= 8 && within(q, h, 2)
    }

    /** How well one query word matches the words of a text: 3 exact, 2 prefix, 1 typo, 0 none. */
    fun wordScore(q: String, hay: List<String>): Int {
        var best = 0
        for (h in hay) {
            val s = when {
                h == q -> 3
                h.startsWith(q) -> 2
                best == 0 && h.isNotEmpty() && typo(q, h) -> 1
                else -> 0
            }
            if (s > best) best = s
            if (best == 3) break
        }
        return best
    }

    /** A title's words, folded once: words, the words run together, and the words joined by spaces. */
    class Folded(val words: List<String>) {
        val joined: String = words.joinToString("")
        val spaced: String = words.joinToString(" ")
    }
    private val foldedMemo = java.util.concurrent.ConcurrentHashMap<String, Folded>()
    fun folded(s: String): Folded {
        foldedMemo[s]?.let { return it }
        if (foldedMemo.size > 250_000) foldedMemo.clear()
        return Folded(cachedWords(s)).also { foldedMemo[s] = it }
    }

    /**
     * Score of a title for the query, 0 = no match. [title] is the cleaned title, [extra] the other searchable text
     * (cast, director, category / language, the full portal name), [year] the title's year if known.
     */
    fun score(q: Query, title: String, extra: List<String> = emptyList(), year: String = ""): Int =
        score(q, folded(title), { extra.flatMap { cachedWords(it) } }, year)

    /** [score] on a title folded ahead of time (the search snapshot); [extra] words are only computed when needed. */
    fun score(q: Query, f: Folded, extra: () -> List<String>, year: String): Int {
        if (q.empty) return 0
        val tw = f.words
        if (q.year.isNotEmpty()) {
            val y = year.ifEmpty { tw.lastOrNull { YEAR.matches(it) }.orEmpty() }
            if (y.isNotEmpty() && y != q.year) return 0
            if (q.words.isEmpty()) return if (y == q.year) 10 else 0
        }
        val joined = f.joined
        // Whole query equals the title: best
        if (f.spaced == q.spaced || joined == q.squashed) return 100
        var total = 0
        var inTitle = true
        var ew: List<String>? = null
        for (w in q.words) {
            var s = wordScore(w, tw)
            if (s == 0 && w.length >= 3 && joined.contains(w)) s = 2          // "spiderman" in "spider man"
            if (s == 0) {
                inTitle = false
                val e = ew ?: extra().also { ew = it }
                s = if (e.any { it == w }) 2 else if (e.any { it.startsWith(w) }) 1 else 0   // cast / category: exact or prefix
            }
            if (s == 0) return 0
            total += s
        }
        if (inTitle && q.squashed.length >= 4 && joined.startsWith(q.squashed)) total += 10
        else if (inTitle && f.spaced.startsWith(q.spaced)) total += 8
        return total + if (inTitle) 20 else 0
    }

    /** "shah rukh khan" -> "Shah Rukh Khan" (for a person typed in lower case). */
    fun titleCase(s: String): String = s.trim().split(Regex("\\s+")).joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }

    /** Title + year key for matching a filmography (from TMDB / Cinemeta) to the portal's titles. */
    private val ARTICLES = setOf("the", "a", "an")
    /** Leftover quality / rip tags some portal names keep after the year ("Jawan (2023) HD"). */
    private val TAGS = setOf("hd", "fhd", "uhd", "sd", "4k", "8k", "hq", "hevc", "x264", "x265", "cam", "hdcam", "ts", "hdts",
        "hdrip", "webrip", "web", "dl", "bluray", "brrip", "dvdrip", "1080p", "720p", "480p", "2160p", "dubbed", "dual", "audio", "multi")
    fun titleKey(title: String): String = titleKey(cachedWords(title))
    fun titleKey(words: List<String>): String {
        val w = words.filter { it !in ARTICLES }
        return w.dropLastWhile { it in TAGS }.ifEmpty { w }.joinToString(" ")
    }

    /** Same film: same folded title and the years agree when both are known (one year apart allowed). */
    fun sameTitle(aTitle: String, aYear: String, bTitle: String, bYear: String): Boolean {
        val a = titleKey(aTitle); val b = titleKey(bTitle)
        if (a.isEmpty() || a != b) return false
        val ya = aYear.take(4).toIntOrNull(); val yb = bYear.take(4).toIntOrNull()
        return ya == null || yb == null || kotlin.math.abs(ya - yb) <= 1
    }

    /**
     * Cinemeta answers a title search too (with look-alike titles), so whether the query is a person is decided from
     * the saved catalog: [creditHits] titles from the credits (not named like the query) are saved, [titleHits] saved
     * titles carry the query in their own title. A person when at least 2 credits are found and they outnumber the
     * title matches ("pathaan" finds the film Pathaan itself, so no person row). A trusted source (TMDB person
     * search with a matching name, or the portal's actors field) needs just 1.
     */
    fun personLikely(creditHits: Int, titleHits: Int, trusted: Boolean): Boolean =
        if (trusted) creditHits >= 1 else creditHits >= 2 && creditHits > titleHits
}
