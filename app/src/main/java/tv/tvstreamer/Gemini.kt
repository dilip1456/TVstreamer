package tv.tvstreamer

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Google Gemini REST API (generativelanguage.googleapis.com). The API key is the user's own, set in Settings,
 * kept in the encrypted Store and synced like the TMDB key. Never hardcoded.
 */
object Gemini {
    const val KEY_URL = "https://aistudio.google.com/apikey"
    /** Current flash models first; older ones are tried when a model is not available to the key (404). */
    val MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-2.5-flash", "gemini-2.0-flash")
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    /** Observable so the Home row and the search bar react when the key is set or synced. */
    var key: String by androidx.compose.runtime.mutableStateOf("")
        private set
    val enabled: Boolean get() = key.isNotBlank()
    @Volatile private var model: String? = null

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun useKey(k: String) { key = k.trim() }

    /** Sends [prompt], returns the model's text (JSON mode). Throws with a readable message. Blocking: call off the main thread. */
    fun generate(prompt: String, k: String = key, json: Boolean = true): String {
        require(k.isNotBlank()) { "No Gemini API key (Settings > AI)" }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0.4).apply { if (json) put("responseMimeType", "application/json") })
            .toString()
        val tryModels = listOfNotNull(model) + MODELS.filter { it != model }
        var last = "Gemini did not answer"
        for (m in tryModels) {
            val req = Request.Builder().url("$BASE$m:generateContent")
                .header("x-goog-api-key", k.trim())
                .post(body.toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.isSuccessful) { model = m; return GeminiParse.responseText(text) }
                last = "Gemini: " + GeminiParse.errorMessage(text).ifEmpty { "HTTP ${r.code}" }
                // Model not offered to this key / region: try the next one. Anything else (bad key, quota) stops here.
                if (r.code != 404 && !(r.code == 400 && "model" in text.lowercase() && "not" in text.lowercase())) throw IllegalStateException(last)
            }
        }
        throw IllegalStateException(last)
    }

    /** Short check for Settings. */
    fun test(k: String): String = runCatching {
        generate("Reply with JSON {\"ok\":true}", k)
        "Gemini key works (${model ?: MODELS.first()})"
    }.getOrElse { it.message ?: "Gemini key test failed" }
}
