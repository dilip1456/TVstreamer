package tv.tvstreamer

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Thrown with a short, user-readable message. Never contains tokens. */
class GoogleError(msg: String, val code: String = "", val authLost: Boolean = false) : Exception(msg)

/**
 * Google sign-in with the OAuth 2.0 "device" flow (works on Fire TV, no Play Services) and the user's private
 * Drive app folder. Tokens are never logged.
 */
object Google {
    const val SCOPES = "openid email profile https://www.googleapis.com/auth/drive.appdata"
    const val DEVICE_URL = "https://oauth2.googleapis.com/device/code"
    const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
    const val VERIFY_URL = "https://www.google.com/device"
    const val CONSOLE_URL = "https://console.cloud.google.com/apis/credentials"
    const val FILE_NAME = "tvstreamer-sync.json"
    private const val DRIVE = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val expiresAt: Long, val interval: Int)
    data class Tokens(val access: String, val expiresAt: Long, val refresh: String?, val email: String?, val name: String? = null, val photo: String? = null)

    private fun call(req: Request): Pair<Int, String> = try {
        http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
    } catch (e: java.io.IOException) {
        throw GoogleError("No connection to Google (${e.javaClass.simpleName}). Changes are kept and sync later.", "offline")
    }

    private fun errorOf(body: String): Pair<String, String> = runCatching {
        val o = JSONObject(body)
        val e = o.opt("error")
        if (e is JSONObject) (e.optString("status").ifEmpty { e.optInt("code").toString() }) to e.optString("message")
        else o.optString("error") to o.optString("error_description")
    }.getOrDefault("" to "")

    /** Step 1: ask Google for a code the user types at google.com/device. */
    fun startDevice(clientId: String): DeviceCode {
        val (code, body) = call(Request.Builder().url(DEVICE_URL).post(FormBody.Builder().add("client_id", clientId).add("scope", SCOPES).build()).build())
        if (code != 200) {
            val (err, desc) = errorOf(body)
            throw GoogleError(when (err) {
                "invalid_client" -> "Google did not accept the Client ID. Check it, and that the client type is \"TVs and Limited Input devices\"."
                "invalid_scope" -> "Google refused the Drive app-folder permission for this client. Check the OAuth consent screen scopes."
                else -> "Google sign-in could not start: ${err.ifEmpty { "HTTP $code" }} ${desc}".trim()
            }, err)
        }
        val o = JSONObject(body)
        return DeviceCode(
            o.getString("device_code"), o.getString("user_code"),
            o.optString("verification_url", o.optString("verification_uri", VERIFY_URL)).ifEmpty { VERIFY_URL },
            System.currentTimeMillis() + o.optLong("expires_in", 1800) * 1000, o.optInt("interval", 5).coerceAtLeast(1)
        )
    }

    /** Step 2: one poll. null = the user has not finished yet; "slow_down" raises the interval via the exception code. */
    fun pollDevice(clientId: String, secret: String, dc: DeviceCode): Tokens? {
        val form = FormBody.Builder().add("client_id", clientId).add("client_secret", secret)
            .add("device_code", dc.deviceCode).add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build()
        val (code, body) = call(Request.Builder().url(TOKEN_URL).post(form).build())
        if (code == 200) return tokens(JSONObject(body), null)
        val (err, desc) = errorOf(body)
        when (err) {
            "authorization_pending" -> return null
            "slow_down" -> throw GoogleError("slow down", "slow_down")
            "access_denied" -> throw GoogleError("Sign-in was declined on the Google page.", err)
            "expired_token" -> throw GoogleError("The code expired. Start sign-in again.", err)
            "invalid_client" -> throw GoogleError("Google did not accept the Client ID / Client secret pair.", err)
            else -> throw GoogleError("Google sign-in failed: ${err.ifEmpty { "HTTP $code" }} $desc".trim(), err)
        }
    }

    /** New access token from the saved refresh token. */
    fun refresh(clientId: String, secret: String, refresh: String): Tokens {
        val form = FormBody.Builder().add("client_id", clientId).add("client_secret", secret)
            .add("refresh_token", refresh).add("grant_type", "refresh_token").build()
        val (code, body) = call(Request.Builder().url(TOKEN_URL).post(form).build())
        if (code == 200) return tokens(JSONObject(body), refresh)
        val (err, _) = errorOf(body)
        if (err == "invalid_grant" || err == "invalid_client" || err == "unauthorized_client")
            throw GoogleError("Google access ended (signed out, revoked, or the test-user week ran out). Sign in again.", err, authLost = true)
        throw GoogleError("Could not refresh Google access: ${err.ifEmpty { "HTTP $code" }}", err)
    }

    fun revoke(token: String) { runCatching { call(Request.Builder().url(REVOKE_URL).post(FormBody.Builder().add("token", token).build()).build()) } }

    private fun tokens(o: JSONObject, oldRefresh: String?) = Tokens(
        o.getString("access_token"),
        System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000,
        o.optString("refresh_token").ifEmpty { oldRefresh },
        o.optString("id_token").takeIf { it.isNotEmpty() }?.let { emailFromIdToken(it) },
        o.optString("id_token").takeIf { it.isNotEmpty() }?.let { claim(it, "name") },
        o.optString("id_token").takeIf { it.isNotEmpty() }?.let { claim(it, "picture") }
    )

    fun claim(jwt: String, name: String): String? = runCatching {
        val bytes = android.util.Base64.decode(jwt.split('.')[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        JSONObject(String(bytes)).optString(name).ifEmpty { null }
    }.getOrNull()

    /** The email claim from an id_token (received directly from Google over HTTPS, so not re-verified). */
    fun emailFromIdToken(jwt: String): String? = runCatching {
        val part = jwt.split('.')[1]
        val bytes = android.util.Base64.decode(part, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        JSONObject(String(bytes)).optString("email").ifEmpty { null }
    }.getOrNull()

    fun userEmail(access: String): String? = userInfo(access)?.optString("email")?.ifEmpty { null }

    /** Google profile: email, name, picture (URL of the profile photo). */
    fun userInfo(access: String): JSONObject? = runCatching {
        val (code, body) = call(Request.Builder().url("https://openidconnect.googleapis.com/v1/userinfo").header("Authorization", "Bearer $access").build())
        if (code == 200) JSONObject(body) else null
    }.getOrNull()

    // ---------- Drive app folder ----------

    private fun driveFail(code: Int, body: String, what: String): Nothing {
        val (err, msg) = errorOf(body)
        if (code == 401) throw GoogleError("Google access expired", "401")
        if (code == 403 && (msg.contains("has not been used", true) || msg.contains("is disabled", true)))
            throw GoogleError("The Google Drive API is not enabled in your Google Cloud project. Enable it and try again.", "drive_disabled")
        throw GoogleError("Drive $what failed: ${err.ifEmpty { "HTTP $code" }} ${msg.take(160)}".trim(), err)
    }

    /** Ids of our sync file(s) in the app folder (normally one). */
    fun findFiles(access: String): List<String> {
        val q = java.net.URLEncoder.encode("name = '$FILE_NAME' and trashed = false", "UTF-8")
        val (code, body) = call(Request.Builder().url("$DRIVE?spaces=appDataFolder&q=$q&fields=files(id,modifiedTime)&orderBy=modifiedTime%20desc")
            .header("Authorization", "Bearer $access").build())
        if (code != 200) driveFail(code, body, "list")
        val a = JSONObject(body).optJSONArray("files") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it).getString("id") }
    }

    fun download(access: String, id: String): String? {
        val (code, body) = call(Request.Builder().url("$DRIVE/$id?alt=media").header("Authorization", "Bearer $access").build())
        if (code == 404) return null
        if (code != 200) driveFail(code, body, "download")
        return body
    }

    /** Creates the file (id == null) or replaces its content. Returns the file id. */
    fun upload(access: String, id: String?, content: String): String {
        val req = if (id == null) {
            val meta = JSONObject().put("name", FILE_NAME).put("parents", org.json.JSONArray().put("appDataFolder")).put("mimeType", "application/json")
            val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(meta.toString().toRequestBody(JSON))
                .addPart(content.toRequestBody(JSON)).build()
            Request.Builder().url("$UPLOAD?uploadType=multipart&fields=id").header("Authorization", "Bearer $access").post(body).build()
        } else Request.Builder().url("$UPLOAD/$id?uploadType=media&fields=id").header("Authorization", "Bearer $access")
            .patch(content.toRequestBody(JSON)).build()
        val (code, body) = call(req)
        if (code !in 200..299) driveFail(code, body, "upload")
        return JSONObject(body).optString("id").ifEmpty { id ?: "" }
    }

    fun delete(access: String, id: String) {
        val (code, body) = call(Request.Builder().url("$DRIVE/$id").header("Authorization", "Bearer $access").delete().build())
        if (code !in 200..299 && code != 404) driveFail(code, body, "delete")
    }
}
