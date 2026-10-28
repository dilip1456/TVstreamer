package tv.tvstreamer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Details for a movie or series from TMDB (+ IMDb rating from OMDb). found = false is a cached "no match". */
data class Meta(
    val tmdbId: Int = 0,
    val type: String = "",          // "movie" or "tv"
    val title: String = "",
    val poster: String = "",
    val backdrop: String = "",
    val plot: String = "",
    val year: String = "",
    val released: String = "",      // "2023-01-25" (TMDB release / first air date)
    val rating: String = "",        // TMDB vote average
    val imdbRating: String = "",
    val genres: String = "",
    val cast: String = "",
    val runtime: String = "",
    val imdbId: String = "",
    val omdbOnly: Boolean = false
) {
    val found: Boolean get() = tmdbId != 0 || omdbOnly

    fun toJson(): JSONObject = JSONObject().put("id", tmdbId).put("ty", type).put("t", title).put("p", poster).put("b", backdrop)
        .put("o", plot).put("y", year).put("rd", released).put("r", rating).put("ir", imdbRating).put("g", genres).put("c", cast)
        .put("rt", runtime).put("im", imdbId).put("oo", omdbOnly)

    companion object {
        fun from(o: JSONObject) = Meta(o.optInt("id"), o.optString("ty"), o.optString("t"), o.optString("p"), o.optString("b"),
            o.optString("o"), o.optString("y"), o.optString("rd"), o.optString("r"), o.optString("ir"), o.optString("g"),
            o.optString("c"), o.optString("rt"), o.optString("im"), o.optBoolean("oo"))
    }
}

/** TMDB season data for one episode. */
data class EpMeta(val name: String, val still: String, val overview: String, val airDate: String)

/** One TMDB search result. */
data class Cand(val id: Int, val type: String, val title: String, val original: String, val year: String,
                val popularity: Double, val poster: String = "", val overview: String = "")

/** Picks the TMDB result for a cleaned portal title. Pure logic (unit tested). */
object Matcher {
    /** Name similarity (0..1) plus a year bonus / penalty. */
    fun score(c: Cand, title: String, year: String): Double {
        val sim = maxOf(Tags.similarity(title, c.title), Tags.similarity(title, c.original))
        val y = year.toIntOrNull(); val cy = c.year.toIntOrNull()
        val adj = when {
            y == null || cy == null -> 0.0
            y == cy -> 0.15
            kotlin.math.abs(y - cy) == 1 -> 0.05
            else -> -0.25
        }
        return sim + adj
    }

    /** Best by similarity (in 0.05 steps), then popularity. null when nothing is close enough. */
    fun pick(results: List<Cand>, title: String, year: String, min: Double = 0.55): Cand? =
        results.map { it to score(it, title, year) }
            .filter { it.second >= min }
            .sortedWith(compareByDescending<Pair<Cand, Double>> { Math.round(it.second * 20) }.thenByDescending { it.first.popularity })
            .firstOrNull()?.first
}

/**
 * TMDB v3 (primary) + OMDb (IMDb rating) with a disk cache (hits and misses), user overrides ("Fix match")
 * and at most 3 lookups at a time. Without keys nothing is looked up and the portal's own data is used.
 */
object Tmdb {
    @Volatile var key: String = ""
    @Volatile var omdbKey: String = ""
    /** Bumped when keys / overrides change so cards look again. */
    var version by mutableIntStateOf(0)

    val enabled: Boolean get() = key.isNotBlank() || omdbKey.isNotBlank()

    private var dir: File? = null
    private val mem = ConcurrentHashMap<String, Meta>()
    private val seasons = ConcurrentHashMap<String, Map<Int, EpMeta>>()
    private var overrides = ConcurrentHashMap<String, String>()   // cache key -> "movie:123" / "tv:45" / "none"
    private val gate = Semaphore(3)
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    const val IMG = "https://image.tmdb.org/t/p/"
    private const val HIT_TTL = 30L * 24 * 3600 * 1000
    private const val MISS_TTL = 3L * 24 * 3600 * 1000

    fun init(ctx: Context, tmdbKey: String, omdb: String) {
        dir = File(ctx.filesDir, "meta").apply { mkdirs() }
        runCatching {
            val f = File(dir, "overrides.json")
            if (f.exists()) JSONObject(f.readText()).let { o -> o.keys().forEach { k -> overrides[k] = o.getString(k) } }
        }
        setKeys(tmdbKey, omdb)
    }

    fun setKeys(tmdbKey: String, omdb: String) {
        val changed = tmdbKey.trim() != key || omdb.trim() != omdbKey
        key = tmdbKey.trim(); omdbKey = omdb.trim()
        if (changed) { mem.clear(); version++ }
    }

    fun clearCache() {
        mem.clear(); seasons.clear()
        dir?.listFiles()?.forEach { if (it.name != "overrides.json") it.delete() }
        version++
    }

    /** Lookups are for movies and series (also single-episode entries, which look up their show); not channels. */
    fun applies(item: Item) = enabled && item.kind == "vod" && !item.isEpisode() && item.name.isNotBlank()

    fun typeOf(item: Item) = if (item.seriesLike) "tv" else "movie"

    /** Cache key: normalized title + year + type. */
    fun cacheKey(item: Item): String {
        val p = item.parsed
        return "${typeOf(item)}|${Tags.norm(p.title)}|${p.year.ifEmpty { item.year.take(4) }}"
    }

    private fun file(k: String): File? {
        val d = dir ?: return null
        val h = MessageDigest.getInstance("MD5").digest(k.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(d, "$h.json")
    }

    fun cached(item: Item): Meta? = if (!applies(item)) null else mem[cacheKey(item)]

    /** Memory or disk cache only; no network. */
    fun peek(item: Item): Meta? {
        if (!applies(item)) return null
        val k = cacheKey(item)
        mem[k]?.let { return it }
        return readDisk(k)
    }

    private fun readDisk(k: String): Meta? = file(k)?.takeIf { it.exists() }?.let { f ->
        runCatching {
            val o = JSONObject(f.readText())
            val m = Meta.from(o.getJSONObject("m"))
            val age = System.currentTimeMillis() - o.optLong("at")
            if (o.optBoolean("fixed") || age < if (m.found) HIT_TTL else MISS_TTL) { mem[k] = m; m } else null
        }.getOrNull()
    }

    private fun writeDisk(k: String, m: Meta, fixed: Boolean = false) {
        runCatching { file(k)?.writeText(JSONObject().put("at", System.currentTimeMillis()).put("fixed", fixed).put("m", m.toJson()).toString()) }
    }

    suspend fun get(item: Item): Meta? {
        if (!applies(item)) return null
        val k = cacheKey(item)
        mem[k]?.let { return it }
        return withContext(Dispatchers.IO) {
            readDisk(k)?.let { return@withContext it }
            gate.withPermit {
                mem[k]?.let { return@withPermit it }
                // Network or key problems are not cached, so they are tried again later
                val m = runCatching { lookup(item, overrides[k]) }.getOrElse { return@withPermit null }
                mem[k] = m
                writeDisk(k, m, overrides.containsKey(k))
                m
            }
        }
    }

    // ---- Fix match ----

    suspend fun search(query: String, type: String): List<Cand> = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext emptyList()
        val (title, year) = Tags.parse(query).let { it.title to it.year }
        val yp = if (year.isNotEmpty()) (if (type == "tv") "&first_air_date_year=$year" else "&primary_release_year=$year") else ""
        val a = runCatching { tmdb("/search/$type?query=${enc(title)}&include_adult=false$yp").optJSONArray("results") }.getOrNull()
        cands(a, type)
    }

    /** Use this TMDB result for the item from now on; null = "no match, use portal data". */
    suspend fun setOverride(item: Item, c: Cand?) = withContext(Dispatchers.IO) {
        val k = cacheKey(item)
        val v = if (c == null) "none" else "${c.type}:${c.id}"
        overrides[k] = v
        runCatching { File(dir, "overrides.json").writeText(JSONObject(overrides as Map<*, *>).toString()) }
        mem.remove(k)
        file(k)?.delete()
        val m = runCatching { lookup(item, v) }.getOrNull()
        if (m != null) { mem[k] = m; writeDisk(k, m, true) }
        version++
        onChange?.invoke()
        m
    }

    /** Forget the cached result for one title and look it up again. */
    suspend fun clearFor(item: Item) {
        val k = cacheKey(item)
        mem.remove(k); withContext(Dispatchers.IO) { file(k)?.delete() }
        get(item); version++
    }

    fun hasOverride(item: Item) = overrides.containsKey(cacheKey(item))

    /** Called after a Fix match change (cloud sync). */
    @Volatile var onChange: (() -> Unit)? = null

    fun overridesSnapshot(): Map<String, String> = HashMap(overrides)

    /** Replaces all Fix match choices (from cloud sync); changed titles are looked up again. */
    fun replaceOverrides(m: Map<String, String>) {
        if (m == overridesSnapshot()) return
        val changed = (overrides.keys + m.keys).filter { overrides[it] != m[it] }
        overrides.clear(); overrides.putAll(m)
        runCatching { File(dir, "overrides.json").writeText(JSONObject(overrides as Map<*, *>).toString()) }
        changed.forEach { k -> mem.remove(k); file(k)?.delete() }
        version++
    }

    // ---- Series: TMDB episode names / stills / overviews by season + episode number ----

    suspend fun season(tvId: Int, n: Int): Map<Int, EpMeta> {
        if (key.isBlank() || tvId <= 0 || n < 0) return emptyMap()
        val k = "season|$tvId|$n"
        seasons[k]?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = file(k)
            val txt = f?.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < 7L * 24 * 3600 * 1000 }?.readText()
                ?: gate.withPermit { runCatching { tmdb("/tv/$tvId/season/$n?language=en-US").toString() }.getOrNull() }?.also { runCatching { f?.writeText(it) } }
                ?: return@withContext emptyMap()
            val a = runCatching { JSONObject(txt).optJSONArray("episodes") }.getOrNull() ?: JSONArray()
            val m = (0 until a.length()).map { a.getJSONObject(it) }.associate { e ->
                e.optInt("episode_number") to EpMeta(e.optString("name"),
                    e.optString("still_path").takeIf { it.startsWith("/") }?.let { "${IMG}w300$it" } ?: "",
                    e.optString("overview"), e.optString("air_date"))
            }
            seasons[k] = m
            m
        }
    }

    // ---- HTTP ----

    private class AuthError(msg: String) : Exception(msg)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun tmdb(path: String, k: String = key): JSONObject {
        val v4 = k.startsWith("eyJ")
        val sep = if (path.contains("?")) "&" else "?"
        val url = "https://api.themoviedb.org/3$path" + (if (v4) "" else "${sep}api_key=${enc(k)}")
        val req = Request.Builder().url(url).header("Accept", "application/json")
            .apply { if (v4) header("Authorization", "Bearer $k") }.build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401) throw AuthError("TMDB key rejected")
            if (!r.isSuccessful) throw IllegalStateException("TMDB HTTP ${r.code}")
            return JSONObject(r.body?.string() ?: "{}")
        }
    }

    private fun omdb(query: String, k: String = omdbKey): JSONObject {
        val r = Request.Builder().url("https://www.omdbapi.com/?$query&apikey=${enc(k)}").build()
        http.newCall(r).execute().use { resp ->
            if (resp.code == 401) throw AuthError("OMDb key rejected")
            if (!resp.isSuccessful) throw IllegalStateException("OMDb HTTP ${resp.code}")
            return JSONObject(resp.body?.string() ?: "{}")
        }
    }

    /** Check keys from Settings. Returns a short report. */
    fun test(tmdbKey: String, omdb: String): String {
        val parts = mutableListOf<String>()
        if (tmdbKey.isNotBlank()) parts += runCatching { tmdb("/configuration", tmdbKey.trim()); "TMDB key works" }.getOrElse { "TMDB: ${it.message}" }
        else parts += "No TMDB key (posters / plots from the portal only)"
        if (omdb.isNotBlank()) parts += runCatching {
            val o = omdb("i=tt0133093", omdb.trim())
            if (o.optString("Response") == "True") "OMDb key works" else "OMDb: ${o.optString("Error", "rejected")}"
        }.getOrElse { "OMDb: ${it.message}" }
        return parts.joinToString(". ")
    }

    private fun cands(a: JSONArray?, type: String): List<Cand> {
        if (a == null) return emptyList()
        return (0 until minOf(a.length(), 20)).map { a.getJSONObject(it) }.map { o ->
            Cand(o.optInt("id"), type, o.optString("title").ifEmpty { o.optString("name") },
                o.optString("original_title").ifEmpty { o.optString("original_name") },
                o.optString("release_date").ifEmpty { o.optString("first_air_date") }.take(4),
                o.optDouble("popularity", 0.0),
                o.optString("poster_path").takeIf { it.startsWith("/") }?.let { "${IMG}w185$it" } ?: "",
                o.optString("overview"))
        }
    }

    private fun lookup(item: Item, override: String?): Meta {
        if (override == "none") return Meta()
        if (override != null && override.contains(':') && key.isNotBlank()) {
            val (t, id) = override.split(':', limit = 2)
            return details(t, id.toInt())
        }
        val p = item.parsed
        val title = p.title
        if (title.length < 2) return Meta()
        val year = p.year.ifEmpty { item.year.take(4) }
        val type = typeOf(item)
        if (key.isBlank()) return omdbOnly(title, year, type)
        fun search(t: String, withYear: Boolean): List<Cand> {
            val yp = if (withYear && year.isNotEmpty()) (if (t == "tv") "&first_air_date_year=$year" else "&primary_release_year=$year") else ""
            return cands(tmdb("/search/$t?query=${enc(title)}&include_adult=false$yp").optJSONArray("results"), t)
        }
        var best = if (year.isNotEmpty()) Matcher.pick(search(type, true), title, year) else null
        if (best == null) best = Matcher.pick(search(type, false), title, year)
        // Portals sometimes file series as movies and the other way round
        if (best == null) best = Matcher.pick(search(if (type == "tv") "movie" else "tv", false), title, year, min = 0.8)
        return best?.let { details(it.type, it.id) } ?: Meta()
    }

    private fun omdbOnly(title: String, year: String, type: String): Meta {
        val o = omdb("t=${enc(title)}" + (if (year.isNotEmpty()) "&y=$year" else "") + "&type=" + (if (type == "tv") "series" else "movie"))
        if (o.optString("Response") != "True") return Meta()
        fun v(k: String) = o.optString(k).takeIf { it.isNotEmpty() && it != "N/A" } ?: ""
        return Meta(type = type, title = v("Title"), poster = v("Poster"), plot = v("Plot"), year = v("Year").take(4),
            released = "", imdbRating = v("imdbRating"), genres = v("Genre"), cast = v("Actors"), runtime = v("Runtime"),
            imdbId = v("imdbID"), omdbOnly = true)
    }

    private fun details(type: String, id: Int): Meta {
        val o = tmdb("/$type/$id?append_to_response=credits,external_ids&language=en-US")
        val genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }?.joinToString(", ") ?: ""
        val cast = o.optJSONObject("credits")?.optJSONArray("cast")?.let { a ->
            (0 until minOf(a.length(), 8)).map { a.getJSONObject(it).optString("name") }
        }?.joinToString(", ") ?: ""
        val runtime = o.optInt("runtime").takeIf { it > 0 }
            ?: o.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }
        val imdbId = o.optString("imdb_id").ifEmpty { o.optJSONObject("external_ids")?.optString("imdb_id") ?: "" }.takeIf { it.startsWith("tt") } ?: ""
        val vote = o.optDouble("vote_average", 0.0)
        var imdbRating = ""
        if (omdbKey.isNotBlank() && imdbId.isNotEmpty()) {
            imdbRating = runCatching { omdb("i=$imdbId").optString("imdbRating") }.getOrDefault("").takeIf { it.isNotEmpty() && it != "N/A" } ?: ""
        }
        val date = o.optString("release_date").ifEmpty { o.optString("first_air_date") }
        return Meta(
            tmdbId = id, type = type,
            title = o.optString("title").ifEmpty { o.optString("name") },
            poster = o.optString("poster_path").takeIf { it.startsWith("/") }?.let { "${IMG}w342$it" } ?: "",
            backdrop = o.optString("backdrop_path").takeIf { it.startsWith("/") }?.let { "${IMG}w1280$it" } ?: "",
            plot = o.optString("overview"),
            year = date.take(4), released = date,
            rating = if (vote > 0) "%.1f".format(java.util.Locale.US, vote) else "",
            imdbRating = imdbRating, genres = genres, cast = cast,
            runtime = runtime?.let { m -> if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" } ?: "",
            imdbId = imdbId
        )
    }
}

/** Metadata for a visible card / screen. Loads lazily: only items that are composed (on screen) are looked up. */
@Composable
fun rememberMeta(item: Item): Meta? {
    val ver = Tmdb.version
    val m by produceState(Tmdb.cached(item), item.key, item.name, ver) {
        value = Tmdb.cached(item)
        if (value == null && Tmdb.applies(item)) value = Tmdb.get(item)
    }
    return m?.takeIf { it.found }
}

/** Portal item with TMDB / OMDb details filled in. IMDb rating wins, then the portal rating, then TMDB's. */
fun Item.withMeta(m: Meta?): Item = if (m == null || !m.found) this else copy(
    img = m.poster.ifEmpty { img },
    desc = m.plot.ifEmpty { desc },
    year = year.ifEmpty { m.year },
    rating = when {
        m.imdbRating.isNotEmpty() -> m.imdbRating
        rating.isNotEmpty() -> rating
        else -> m.rating
    },
    genre = m.genres.ifEmpty { genre },
    length = length.ifEmpty { m.runtime }
)
