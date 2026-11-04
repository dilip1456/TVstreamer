package tv.tvstreamer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Subtitle downloads with a persistent cache and a fallback chain (1.54):
 *   1. the on-device cache (per title / episode / language; replays and later sessions never download again),
 *   2. OpenSubtitles.com (user's API key, optionally their login for a bigger daily allowance), unless today's quota
 *      is known to be used up,
 *   3. SubDL (user's own free key), unless its quota is used up.
 * A source that answers "quota exceeded" is skipped until its reset time; the player shows a short bottom message
 * and the next source is tried. One request at a time, no retries in a loop.
 */
object Subtitles {
    class Found(val file: File, val source: String, val cached: Boolean)

    /** Cache key: the title's ids (or cleaned title + year), season / episode and language. Stable across sessions. */
    fun key(q: OpenSubs.Query): String {
        val id = when {
            q.tmdbId > 0 -> "tmdb${q.tmdbId}"
            q.imdbId.isNotBlank() -> "imdb" + q.imdbId.removePrefix("tt").trimStart('0')
            else -> "t_" + q.title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(60) + (if (q.year.length == 4) "_${q.year}" else "")
        }
        val ep = if (q.isEpisode) "_s${q.season}e${q.episode}" else ""
        return "$id${ep}_${q.lang.lowercase()}"
    }

    /** Title key for the remembered choice (no language). */
    fun titleKey(q: OpenSubs.Query): String = key(q.copy(lang = "")).removeSuffix("_")

    /** Persistent store: files in [dir] named "<key>.<ext>", newest kept (at most [max]); choices in choices.json. */
    class Cache(private val dir: File, private val max: Int = 300) {
        fun get(q: OpenSubs.Query): File? = dir.listFiles()?.firstOrNull { it.nameWithoutExtension == key(q) && it.length() > 0 }
            ?.also { it.setLastModified(System.currentTimeMillis()) }

        fun put(q: OpenSubs.Query, ext: String, bytes: ByteArray): File {
            dir.mkdirs()
            dir.listFiles()?.filter { it.nameWithoutExtension == key(q) }?.forEach { it.delete() }
            val f = File(dir, key(q) + "." + ext.lowercase().takeIf { it in EXTS }.orEmpty().ifEmpty { "srt" })
            f.writeBytes(bytes)
            dir.listFiles()?.filter { it.extension in EXTS }?.sortedByDescending { it.lastModified() }?.drop(max)?.forEach { it.delete() }
            return f
        }

        private val choiceFile get() = File(dir, "choices.json")
        private fun choices(): JSONObject = runCatching { JSONObject(choiceFile.readText()) }.getOrElse { JSONObject() }
        /** The subtitle language picked last for this title / episode ("" = none). */
        fun choice(q: OpenSubs.Query): String = choices().optString(titleKey(q))
        fun remember(q: OpenSubs.Query, lang: String) {
            dir.mkdirs()
            val o = choices()
            if (lang.isEmpty()) o.remove(titleKey(q)) else o.put(titleKey(q), lang)
            // Keep the newest 500 choices
            val keys = o.keys().asSequence().toList()
            if (keys.size > 500) keys.take(keys.size - 500).forEach { o.remove(it) }
            choiceFile.writeText(o.toString())
        }
    }
    val EXTS = setOf("srt", "vtt", "ass", "ssa")

    /** Per-source "quota used up until" (epoch ms), kept in prefs so a new session does not ask again. */
    object Quota {
        fun parseReset(body: String, now: Long): Long {
            val o = runCatching { JSONObject(body) }.getOrNull()
            val utc = o?.optString("reset_time_utc").orEmpty()
            runCatching { if (utc.isNotBlank()) return java.time.Instant.parse(utc).toEpochMilli() }
            // Unknown: next midnight UTC (OpenSubtitles resets at midnight UTC)
            val day = 24 * 3600_000L
            return (now / day + 1) * day
        }
        fun available(until: Long, now: Long) = until <= now
    }

    /** Runs the chain. [status] gets short messages for the bottom of the player. Throws OpenSubs.Failure with all reasons. */
    suspend fun fetch(store: Store, cache: Cache, q: OpenSubs.Query, status: (String) -> Unit): Found = withContext(Dispatchers.IO) {
        cache.get(q)?.let { return@withContext Found(it, "saved", true) }
        val why = mutableListOf<String>()
        val now = System.currentTimeMillis()
        // OpenSubtitles
        if (store.osKey.isBlank()) why += "OpenSubtitles: no API key"
        else if (!Quota.available(store.osQuotaUntil, now)) why += "OpenSubtitles: daily limit used"
        else try {
            val (bytes, ext) = OpenSubs.fetchBytes(store, q)
            return@withContext Found(cache.put(q, ext, bytes), "OpenSubtitles", false)
        } catch (e: OpenSubs.Quota) {
            store.osQuotaUntil = e.until; why += "OpenSubtitles: daily limit used"
            if (store.subdlKey.isNotBlank()) status("OpenSubtitles limit reached · trying SubDL…")
        } catch (e: OpenSubs.Failure) { why += e.message.orEmpty() }
        // SubDL
        if (store.subdlKey.isBlank()) why += "SubDL: no key"
        else if (!Quota.available(store.subdlQuotaUntil, now)) why += "SubDL: daily limit used"
        else try {
            val (bytes, ext) = SubDl.fetch(store.subdlKey, q)
            return@withContext Found(cache.put(q, ext, bytes), "SubDL", false)
        } catch (e: OpenSubs.Quota) { store.subdlQuotaUntil = e.until; why += "SubDL: daily limit used" }
        catch (e: OpenSubs.Failure) { why += e.message.orEmpty() }
        throw OpenSubs.Failure(summary(why, q))
    }

    /** One short line: "No English subtitles found" / "Daily limits used (OpenSubtitles, SubDL)" / setup hint. */
    fun summary(why: List<String>, q: OpenSubs.Query): String {
        val noKeys = why.count { it.endsWith("no API key") || it.endsWith("no key") }
        if (noKeys == 2) return "Add an OpenSubtitles or SubDL key in Settings > API keys to download subtitles."
        val limited = why.filter { it.endsWith("daily limit used") }.map { it.substringBefore(':') }
        val other = why.filterNot { it.endsWith("daily limit used") || it.endsWith("no API key") || it.endsWith("no key") }
        return when {
            other.isNotEmpty() && limited.isEmpty() -> other.joinToString(" · ")
            other.isNotEmpty() -> other.joinToString(" · ") + " · daily limit used: " + limited.joinToString(", ")
            limited.isNotEmpty() -> "Daily subtitle limit used (${limited.joinToString(", ")}). Try again tomorrow."
            else -> "No ${OpenSubs.langName(q.lang)} subtitles found."
        }
    }
}

/**
 * SubDL (subdl.com) with the user's own free API key (their terms allow "bring your own key"; free: 2,000 searches and
 * 50 downloads a day). Search: api.subdl.com/api/v1/subtitles; download: dl.subdl.com + url (a zip).
 */
object SubDl {
    const val KEY_URL = "https://subdl.com/panel/api"
    private const val API = "https://api.subdl.com/api/v1/subtitles"
    private const val DL = "https://dl.subdl.com"

    data class Hit(val url: String, val name: String, val lang: String, val season: Int, val episode: Int, val hi: Boolean, val fullSeason: Boolean)

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** Query string without the key. SubDL language codes are upper case ("EN"). */
    fun searchParams(q: OpenSubs.Query): String {
        val m = linkedMapOf<String, String>()
        val imdb = q.imdbId.takeIf { it.isNotBlank() }?.let { if (it.startsWith("tt")) it else "tt$it" }
        when {
            q.tmdbId > 0 -> m["tmdb_id"] = q.tmdbId.toString()
            imdb != null -> m["imdb_id"] = imdb
            else -> { m["film_name"] = q.title; if (q.year.length == 4) m["year"] = q.year }
        }
        m["type"] = if (q.isEpisode) "tv" else "movie"
        if (q.isEpisode) { m["season_number"] = q.season.toString(); m["episode_number"] = q.episode.toString() }
        m["languages"] = q.lang.uppercase()
        m["subs_per_page"] = "30"
        m["client"] = "custom_integration"
        return m.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
    }

    fun parse(json: String): List<Hit> {
        val o = JSONObject(json)
        val a = o.optJSONArray("subtitles") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val s = a.optJSONObject(i) ?: return@mapNotNull null
            val url = s.optString("url").ifEmpty { return@mapNotNull null }
            Hit(url, s.optString("release_name").ifEmpty { s.optString("name") }, s.optString("language").ifEmpty { s.optString("lang") },
                s.optInt("season"), s.optInt("episode"), s.optBoolean("hi"), s.optBoolean("full_season"))
        }
    }

    /** Right episode first (season packs last), then not hearing-impaired. */
    fun best(hits: List<Hit>, q: OpenSubs.Query): Hit? = hits
        .filter { !q.isEpisode || it.fullSeason || it.episode == 0 || (it.season == q.season && it.episode == q.episode) }
        .sortedWith(compareBy<Hit>({ it.fullSeason }, { it.hi })).firstOrNull()

    private val EP = Regex("(?i)s(\\d{1,2})[ ._-]?e(\\d{1,3})|(\\d{1,2})x(\\d{2,3})")

    /** The subtitle inside a zip: for episodes the file named with the right SxxEyy, else the largest one. */
    fun pickFromZip(zip: ByteArray, q: OpenSubs.Query): Pair<ByteArray, String>? {
        val files = mutableListOf<Triple<String, ByteArray, String>>()
        java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val ext = e.name.substringAfterLast('.', "").lowercase()
                if (!e.isDirectory && ext in Subtitles.EXTS) files += Triple(e.name, z.readBytes().also { if (it.size > 5_000_000) return null }, ext)
            }
        }
        if (files.isEmpty()) return null
        val pick = if (q.isEpisode) files.firstOrNull { (n, _, _) ->
            EP.find(n)?.let { m -> val s = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toIntOrNull(); val ep = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toIntOrNull(); s == q.season && ep == q.episode } == true
        } ?: files.takeIf { it.size == 1 }?.first() else null
        val f = pick ?: if (q.isEpisode) return null else files.maxBy { it.second.size }
        return f.second to f.third
    }

    private val http by lazy { Net.portal.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }
    private const val UA = "TVstreamer v${BuildConfig.VERSION_NAME}"

    suspend fun fetch(key: String, q: OpenSubs.Query): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        if (q.tmdbId <= 0 && q.imdbId.isBlank() && q.title.isBlank()) throw OpenSubs.Failure("SubDL: not enough info")
        val hits = try {
            http.newCall(Request.Builder().url("$API?api_key=${enc(key)}&${searchParams(q)}").header("User-Agent", UA).build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (r.code == 429 || body.contains("quota_exceeded")) throw OpenSubs.Quota(Subtitles.Quota.parseReset(body, System.currentTimeMillis()))
                if (r.code == 401 || r.code == 403) throw OpenSubs.Failure("SubDL refused the key")
                if (!r.isSuccessful) throw OpenSubs.Failure("SubDL error ${r.code}")
                parse(body)
            }
        } catch (e: OpenSubs.Failure) { throw e } catch (e: OpenSubs.Quota) { throw e } catch (e: Exception) { throw OpenSubs.Failure("SubDL not reachable") }
        val hit = best(hits, q) ?: throw OpenSubs.Failure("SubDL: no ${OpenSubs.langName(q.lang)} subtitles")
        val zip = try {
            http.newCall(Request.Builder().url(DL + (if (hit.url.startsWith("/")) "" else "/") + hit.url).header("User-Agent", UA).build()).execute().use { r ->
                if (r.code == 429) throw OpenSubs.Quota(Subtitles.Quota.parseReset("", System.currentTimeMillis()))
                if (!r.isSuccessful) throw OpenSubs.Failure("SubDL download failed (${r.code})")
                r.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: OpenSubs.Failure) { throw e } catch (e: OpenSubs.Quota) { throw e } catch (e: Exception) { throw OpenSubs.Failure("SubDL download failed") }
        pickFromZip(zip, q) ?: throw OpenSubs.Failure("SubDL: no matching file in the download")
    }
}
