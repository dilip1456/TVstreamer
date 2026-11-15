package tv.tvstreamer

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Person search (1.69): "shah rukh khan", "christopher nolan" -> the titles of the saved catalog they are in.
 *
 * Sources, no secrets added:
 *  - the portal's own actors / director fields, when it sends them (matched offline in [Item.cast] / [Item.director]);
 *  - TMDB person credits, only when the user already entered a TMDB key in Settings (accurate: cast + directing);
 *  - otherwise Cinemeta v3 (Stremio's free public catalog, no key): its search also matches cast and director names.
 *    Limits: about 20 movies + 20 series per name, most popular first, no paging, and the results carry no cast list,
 *    and a title query also returns look-alike titles; [SearchIndex.personLikely] decides from what matches locally.
 * Results are cached for 30 minutes; one lookup per query, only when the user searches.
 */
object People {
    data class Credit(val title: String, val year: String, val series: Boolean)
    data class Person(val name: String, val credits: List<Credit>, val directed: Boolean = false, val source: String = "")

    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val cache = TtlCache<String, Person>(30, 30 * 60_000)
    private val miss = TtlCache<String, Boolean>(60, 30 * 60_000)
    private const val CINEMETA = "https://v3-cinemeta.strem.io"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Cinemeta catalog JSON -> credits. */
    fun parseCinemeta(body: String, series: Boolean): List<Credit> = runCatching {
        val a = JSONObject(body).optJSONArray("metas") ?: return emptyList()
        (0 until a.length()).mapNotNull { i ->
            val m = a.optJSONObject(i) ?: return@mapNotNull null
            val n = m.optString("name").trim()
            if (n.isEmpty()) null else Credit(n, m.optString("releaseInfo").take(4).filter { it.isDigit() }, series)
        }
    }.getOrDefault(emptyList())

    /** TMDB /person/{id}/combined_credits -> acting credits plus directing ones. */
    fun parseTmdbCredits(body: String): Pair<List<Credit>, Boolean> = runCatching {
        val o = JSONObject(body)
        val out = mutableListOf<Credit>()
        var directed = 0
        fun add(x: JSONObject) {
            val tv = x.optString("media_type") == "tv"
            val t = (if (tv) x.optString("name") else x.optString("title")).ifEmpty { x.optString("original_title") }
            val d = if (tv) x.optString("first_air_date") else x.optString("release_date")
            if (t.isNotBlank()) out += Credit(t, d.take(4), tv)
        }
        o.optJSONArray("cast")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(::add) }
        o.optJSONArray("crew")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("job") == "Director" }?.let { directed++; add(it) }
        }
        out.distinctBy { SearchIndex.titleKey(it.title) + "|" + it.year } to (directed > 0 && directed * 2 >= (o.optJSONArray("cast")?.length() ?: 0))
    }.getOrDefault(emptyList<Credit>() to false)

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()

    /** Looks the query up as a person; null when it does not look like one (or offline). Blocking: call off the main thread. */
    fun lookup(q: String): Person? {
        val query = SearchIndex.query(q)
        if (query.words.isEmpty() || query.squashed.length < 4) return null
        val k = query.words.joinToString(" ")
        cache[k]?.let { return it }
        if (miss[k] == true) return null
        val p = (if (Tmdb.key.isNotBlank()) runCatching { tmdb(k) }.getOrNull() else null) ?: cinemeta(query, k)
        if (p == null) miss[k] = true else cache[k] = p
        return p
    }

    private fun tmdb(k: String): Person? {
        val res = Tmdb.get("/search/person?query=${enc(k)}").optJSONArray("results") ?: return null
        val top = res.optJSONObject(0) ?: return null
        val name = top.optString("name")
        // Only a real name match: "nolan" alone or a title-like query should not turn into a person
        val nw = SearchIndex.words(name)
        if (!SearchIndex.query(k).words.all { w -> nw.any { it.startsWith(w) } }) return null
        val body = runCatching { Tmdb.get("/person/${top.optInt("id")}/combined_credits").toString() }.getOrNull() ?: return null
        val (credits, directed) = parseTmdbCredits(body)
        return if (credits.isEmpty()) null else Person(name, credits, directed, "TMDB")
    }

    private fun cinemeta(query: SearchIndex.Query, k: String): Person? {
        val movies = get("$CINEMETA/catalog/movie/top/search=${enc(k)}.json")?.let { parseCinemeta(it, false) }.orEmpty()
        val shows = get("$CINEMETA/catalog/series/top/search=${enc(k)}.json")?.let { parseCinemeta(it, true) }.orEmpty()
        val all = movies + shows
        // Titles named like the query (documentaries about the person, "X: the story") are not their films
        val credits = all.filterNot { c -> val w = SearchIndex.words(c.title); query.words.all { qw -> w.any { it.startsWith(qw) } } }
        return if (credits.isEmpty()) null else Person(SearchIndex.titleCase(k), credits, source = "Cinemeta")
    }
}
