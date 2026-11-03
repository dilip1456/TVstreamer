package tv.tvstreamer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * External subtitles from OpenSubtitles.com (REST API v1). Needs the user's own free API key (Settings); nothing is
 * built in. Search by TMDB / IMDb id (episodes: the show's id + season + episode), falling back to the title.
 */
object OpenSubs {
    const val KEY_URL = "https://www.opensubtitles.com/en/consumers"
    private const val API = "https://api.opensubtitles.com/api/v1"
    private const val UA = "TVstreamer v${BuildConfig.VERSION_NAME}"

    data class Query(
        val tmdbId: Int = 0, val imdbId: String = "", val title: String = "", val year: String = "",
        val season: Int = 0, val episode: Int = 0, val lang: String = "en"
    ) { val isEpisode get() = season > 0 && episode > 0 }

    data class Hit(val fileId: Long, val fileName: String, val lang: String, val downloads: Int, val hearingImpaired: Boolean,
                   val machine: Boolean, val seasonNo: Int = 0, val episodeNo: Int = 0)

    class Failure(msg: String) : Exception(msg)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** The search URL's query string (parameters sorted alphabetically, as the API recommends to avoid redirects). */
    fun searchParams(q: Query): String {
        val m = sortedMapOf<String, String>()
        val imdb = q.imdbId.removePrefix("tt").trimStart('0')
        if (q.isEpisode) {
            when {
                q.tmdbId > 0 -> m["parent_tmdb_id"] = q.tmdbId.toString()
                imdb.isNotEmpty() -> m["parent_imdb_id"] = imdb
                else -> m["query"] = q.title.lowercase()
            }
            m["season_number"] = q.season.toString()
            m["episode_number"] = q.episode.toString()
        } else {
            when {
                q.tmdbId > 0 -> m["tmdb_id"] = q.tmdbId.toString()
                imdb.isNotEmpty() -> m["imdb_id"] = imdb
                else -> { m["query"] = q.title.lowercase(); if (q.year.length == 4) m["year"] = q.year }
            }
        }
        m["languages"] = q.lang.lowercase()
        return m.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
    }

    fun parseSearch(json: String): List<Hit> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<Hit>()
        for (i in 0 until data.length()) {
            val a = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val f = a.optJSONArray("files")?.optJSONObject(0) ?: continue
            val det = a.optJSONObject("feature_details")
            out += Hit(f.optLong("file_id"), f.optString("file_name"), a.optString("language"), a.optInt("download_count"),
                a.optBoolean("hearing_impaired"), a.optBoolean("machine_translated") || a.optBoolean("ai_translated"),
                det?.optInt("season_number") ?: 0, det?.optInt("episode_number") ?: 0)
        }
        return out.filter { it.fileId > 0 }
    }

    /** Best hit: right episode (when known), human-made, not hearing-impaired, most downloaded. */
    fun best(hits: List<Hit>, q: Query): Hit? = hits
        .filter { !q.isEpisode || it.episodeNo == 0 || (it.seasonNo == q.season && it.episodeNo == q.episode) }
        .sortedWith(compareBy<Hit>({ it.machine }, { it.hearingImpaired }, { -it.downloads }))
        .firstOrNull()

    /** Friendly message for an API status code. */
    fun errorFor(code: Int, body: String): String = when (code) {
        401, 403 -> "OpenSubtitles refused the API key. Check it in Settings."
        406, 429 -> "OpenSubtitles download limit reached for today. Try again later." +
            (runCatching { JSONObject(body).optString("reset_time").takeIf { it.isNotBlank() }?.let { " (resets in $it)" } }.getOrNull() ?: "")
        in 500..599 -> "OpenSubtitles is not responding right now. Try again later."
        else -> "OpenSubtitles error $code"
    }

    private val http: OkHttpClient by lazy {
        Net.portal.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    private fun req(key: String, url: String) = Request.Builder().url(url)
        .header("Api-Key", key).header("User-Agent", UA).header("Accept", "application/json")

    /** Searches, downloads the best match into [dir] and returns the file (".srt" or ".vtt" etc.). Throws [Failure]. */
    suspend fun fetch(key: String, q: Query, dir: File): File = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw Failure("Add your free OpenSubtitles API key in Settings to download subtitles.")
        if (q.tmdbId <= 0 && q.imdbId.isBlank() && q.title.isBlank()) throw Failure("Not enough info to search subtitles for this title.")
        val hits = try {
            http.newCall(req(key, "$API/subtitles?${searchParams(q)}").get().build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw Failure(errorFor(r.code, body))
                parseSearch(body)
            }
        } catch (e: Failure) { throw e } catch (e: Exception) { throw Failure("Could not reach OpenSubtitles (${e.message ?: "network error"})") }
        val hit = best(hits, q) ?: throw Failure("No ${langName(q.lang)} subtitles found on OpenSubtitles.")
        try {
            val link = http.newCall(req(key, "$API/download").post(JSONObject().put("file_id", hit.fileId).toString()
                .toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw Failure(errorFor(r.code, body))
                JSONObject(body).optString("link").ifEmpty { throw Failure("OpenSubtitles did not return a download link.") }
            }
            val ext = hit.fileName.substringAfterLast('.', "srt").lowercase().takeIf { it in setOf("srt", "vtt", "ass", "ssa") } ?: "srt"
            dir.mkdirs()
            val out = File(dir, "os_${hit.fileId}.$ext")
            http.newCall(Request.Builder().url(link).header("User-Agent", UA).build()).execute().use { r ->
                if (!r.isSuccessful) throw Failure("Subtitle download failed (${r.code}).")
                out.writeBytes(r.body?.bytes() ?: ByteArray(0))
            }
            if (out.length() == 0L) throw Failure("The subtitle file was empty.")
            dir.listFiles()?.filter { it.name.startsWith("os_") }?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
            out
        } catch (e: Failure) { throw e } catch (e: Exception) { throw Failure("Subtitle download failed (${e.message ?: "network error"})") }
    }

    fun mimeFor(f: File): String = when (f.extension.lowercase()) {
        "vtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
        "ass", "ssa" -> androidx.media3.common.MimeTypes.TEXT_SSA
        else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
    }

    fun langName(code: String): String =
        java.util.Locale(code).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { code }

    /** Languages offered in the download picker (common ones; preferred first). */
    val LANGS = listOf("en", "es", "fr", "de", "it", "pt", "nl", "ar", "hi", "ta", "te", "ml", "tr", "pl", "ru", "zh", "ja", "ko", "sv", "el", "he", "fa", "ur", "bn")
}
