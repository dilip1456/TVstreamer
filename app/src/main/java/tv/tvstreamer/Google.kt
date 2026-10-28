package tv.tvstreamer

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Google sign-in with the OAuth 2.0 "device" flow (works on Fire TV, no Play Services) and the user's private
 * Drive app folder. Tokens are never logged.
 */
object Google {
    /** Preferred scopes. Some clients get invalid_scope for drive.appdata in the device flow; then drive.file is used. */
    val SCOPES = DriveSpace.APPDATA.scopes
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

    data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val expiresAt: Long, val interval: Int, val space: DriveSpace = DriveSpace.APPDATA)
    data class Tokens(val access: String, val expiresAt: Long, val refresh: String?, val email: String?, val name: String? = null, val photo: String? = null, val scope: String? = null)

    private fun call(req: Request): Pair<Int, String> = try {
        http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
    } catch (e: java.io.IOException) {
        throw GoogleError("No connection to Google (${e.javaClass.simpleName}). Changes are kept and sync later.", "offline")
    }

    /** Step 1: ask Google for a code the user types at google.com/device. The scope list is form-encoded by OkHttp (spaces -> %20 / +). */
    fun startDevice(clientId: String, space: DriveSpace = DriveSpace.APPDATA): DeviceCode {
        val (code, body) = call(Request.Builder().url(DEVICE_URL).post(FormBody.Builder().add("client_id", clientId).add("scope", space.scopes).build()).build())
        if (code != 200) throw GoogleErrors.deviceStart(code, body, space)
        val o = JSONObject(body)
        return DeviceCode(
            o.getString("device_code"), o.getString("user_code"),
            o.optString("verification_url", o.optString("verification_uri", VERIFY_URL)).ifEmpty { VERIFY_URL },
            System.currentTimeMillis() + o.optLong("expires_in", 1800) * 1000, o.optInt("interval", 5).coerceAtLeast(1), space
        )
    }

    /** Step 2: one poll. null = the user has not finished yet; "slow_down" raises the interval via the exception code. */
    fun pollDevice(clientId: String, secret: String, dc: DeviceCode): Tokens? {
        val form = FormBody.Builder().add("client_id", clientId).add("client_secret", secret)
            .add("device_code", dc.deviceCode).add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build()
        val (code, body) = call(Request.Builder().url(TOKEN_URL).post(form).build())
        if (code == 200) return tokens(JSONObject(body), null)
        throw GoogleErrors.poll(code, body) ?: return null
    }

    /** New access token from the saved refresh token. */
    fun refresh(clientId: String, secret: String, refresh: String): Tokens {
        val form = FormBody.Builder().add("client_id", clientId).add("client_secret", secret)
            .add("refresh_token", refresh).add("grant_type", "refresh_token").build()
        val (code, body) = call(Request.Builder().url(TOKEN_URL).post(form).build())
        if (code == 200) return tokens(JSONObject(body), refresh)
        throw GoogleErrors.refresh(code, body)
    }

    fun revoke(token: String) { runCatching { call(Request.Builder().url(REVOKE_URL).post(FormBody.Builder().add("token", token).build()).build()) } }

    private fun tokens(o: JSONObject, oldRefresh: String?) = Tokens(
        o.getString("access_token"),
        System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000,
        o.optString("refresh_token").ifEmpty { oldRefresh },
        o.optString("id_token").takeIf { it.isNotEmpty() }?.let { emailFromIdToken(it) },
        o.optString("id_token").takeIf { it.isNotEmpty() }?.let { claim(it, "name") },
        o.optString("id_token").takeIf { it.isNotEmpty() }?.let { claim(it, "picture") },
        o.optString("scope").ifEmpty { null }
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

    private fun driveFail(code: Int, body: String, what: String, space: DriveSpace): Nothing = throw GoogleErrors.drive(code, body, what, space)

    /** Ids of our sync file(s) in the app folder (normally one). */
    fun findFiles(access: String, space: DriveSpace): List<String> {
        val q = java.net.URLEncoder.encode("name = '$FILE_NAME' and trashed = false", "UTF-8")
        val (code, body) = call(Request.Builder().url("$DRIVE?spaces=${if (space == DriveSpace.APPDATA) "appDataFolder" else "drive"}&q=$q&fields=files(id,modifiedTime)&orderBy=modifiedTime%20desc")
            .header("Authorization", "Bearer $access").build())
        if (code != 200) driveFail(code, body, "list", space)
        val a = JSONObject(body).optJSONArray("files") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it).getString("id") }
    }

    fun download(access: String, id: String, space: DriveSpace): String? {
        val (code, body) = call(Request.Builder().url("$DRIVE/$id?alt=media").header("Authorization", "Bearer $access").build())
        if (code == 404) return null
        if (code != 200) driveFail(code, body, "download", space)
        return body
    }

    /** Creates the file (id == null) or replaces its content. Returns the file id. */
    fun upload(access: String, id: String?, content: String, space: DriveSpace): String {
        val req = if (id == null) {
            val meta = JSONObject().put("name", FILE_NAME).put("mimeType", "application/json")
            if (space == DriveSpace.APPDATA) meta.put("parents", org.json.JSONArray().put("appDataFolder"))
            else meta.put("description", "TVstreamer settings sync. Do not edit; deleting it removes the cloud copy.")
            val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(meta.toString().toRequestBody(JSON))
                .addPart(content.toRequestBody(JSON)).build()
            Request.Builder().url("$UPLOAD?uploadType=multipart&fields=id").header("Authorization", "Bearer $access").post(body).build()
        } else Request.Builder().url("$UPLOAD/$id?uploadType=media&fields=id").header("Authorization", "Bearer $access")
            .patch(content.toRequestBody(JSON)).build()
        val (code, body) = call(req)
        if (code !in 200..299) driveFail(code, body, "upload", space)
        return JSONObject(body).optString("id").ifEmpty { id ?: "" }
    }

    fun delete(access: String, id: String, space: DriveSpace) {
        val (code, body) = call(Request.Builder().url("$DRIVE/$id").header("Authorization", "Bearer $access").delete().build())
        if (code !in 200..299 && code != 404) driveFail(code, body, "delete", space)
    }
}
