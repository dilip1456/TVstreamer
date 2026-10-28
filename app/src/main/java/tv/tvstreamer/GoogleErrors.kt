package tv.tvstreamer

import org.json.JSONObject

/** Thrown with a short, user-readable message (plus a fix hint and an optional link). Never contains tokens. */
class GoogleError(
    msg: String,
    val code: String = "",
    val authLost: Boolean = false,
    val hint: String = "",
    val link: String = ""
) : Exception(msg)

/** Where the sync file lives: the hidden app-data folder (preferred) or a normal file this app created in My Drive. */
enum class DriveSpace(val scope: String) {
    APPDATA("https://www.googleapis.com/auth/drive.appdata"),
    FILE("https://www.googleapis.com/auth/drive.file");

    val scopes: String get() = "openid email profile $scope"

    companion object {
        fun of(s: String?): DriveSpace = entries.firstOrNull { it.name == s } ?: APPDATA
    }
}

/**
 * Turns Google's error replies into plain words with a specific fix. Pure (org.json only) so it is unit tested.
 * OAuth errors look like {"error":"invalid_scope","error_description":"..."}; API errors like
 * {"error":{"code":403,"message":"...","status":"PERMISSION_DENIED","errors":[{"reason":"accessNotConfigured"}],
 * "details":[{"reason":"SERVICE_DISABLED","metadata":{"activationUrl":"https://..."}}]}}.
 */
object GoogleErrors {
    const val CONSENT_URL = "https://console.cloud.google.com/auth/audience"
    const val DATA_ACCESS_URL = "https://console.cloud.google.com/auth/scopes"
    const val CLIENTS_URL = "https://console.cloud.google.com/auth/clients"
    const val DRIVE_API_URL = "https://console.cloud.google.com/apis/library/drive.googleapis.com"
    const val PERMISSIONS_URL = "https://myaccount.google.com/connections"

    const val TICK_DRIVE = "On the Google consent screen, tick the box allowing TVstreamer to access its own data in Google Drive " +
        "(\"See, create, and delete its own configuration data in your Google Drive\") and press Continue."

    data class Parsed(val error: String, val description: String, val reasons: List<String>, val link: String)

    private val URL_RE = Regex("""https://[^\s"')]+""")

    fun parse(body: String): Parsed = runCatching {
        val o = JSONObject(body)
        val e = o.opt("error")
        if (e is JSONObject) {
            val reasons = mutableListOf<String>()
            var link = ""
            e.optJSONArray("errors")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("reason")?.takeIf { it.isNotEmpty() }?.let(reasons::add) }
            e.optJSONArray("details")?.let { a ->
                for (i in 0 until a.length()) {
                    val d = a.optJSONObject(i) ?: continue
                    d.optString("reason").takeIf { it.isNotEmpty() }?.let(reasons::add)
                    d.optJSONObject("metadata")?.optString("activationUrl")?.takeIf { it.isNotEmpty() }?.let { link = it }
                }
            }
            val msg = e.optString("message")
            if (link.isEmpty()) link = URL_RE.find(msg)?.value?.trimEnd('.', ',') ?: ""
            Parsed(e.optString("status").ifEmpty { e.optInt("code").toString() }, msg, reasons, link)
        } else {
            val err = o.optString("error").ifEmpty { o.optString("error_code") }
            Parsed(err, o.optString("error_description"), emptyList(), "")
        }
    }.getOrDefault(Parsed("", body.take(200), emptyList(), ""))

    private fun said(http: Int, p: Parsed): String {
        val e = p.error.ifEmpty { "HTTP $http" }
        return if (p.description.isNotEmpty() && p.description != "Bad Request" && p.description != "Forbidden") "Google said: $e — ${p.description.take(300)}" else "Google said: $e (HTTP $http)"
    }

    /** Error from https://oauth2.googleapis.com/device/code. */
    fun deviceStart(http: Int, body: String, space: DriveSpace): GoogleError {
        val p = parse(body)
        val s = said(http, p)
        return when (p.error) {
            "invalid_client" -> GoogleError(
                "Google did not accept the Client ID. $s",
                p.error, hint = "If you entered your own client under Advanced: copy the Client ID again (it ends in .apps.googleusercontent.com, no spaces); the client must be of type " +
                    "\"TVs and Limited Input devices\" — a Web, Android or Desktop client does not work here.", link = CLIENTS_URL)
            "invalid_scope" -> GoogleError(
                "Google would not offer the ${if (space == DriveSpace.APPDATA) "Drive app-folder (drive.appdata)" else "Drive file (drive.file)"} permission to this client. $s",
                p.error, hint = "In Google Cloud > Google Auth Platform > Data access, add the scope ${space.scope}. " +
                    "Also check that the Google Drive API is enabled and the client type is \"TVs and Limited Input devices\".", link = DATA_ACCESS_URL)
            "org_internal" -> GoogleError("This client only allows accounts from one Google Workspace organisation. $s",
                p.error, hint = "In Google Auth Platform > Audience, set the user type to External and add your Gmail as a test user.", link = CONSENT_URL)
            "rate_limit_exceeded" -> GoogleError("Too many sign-in attempts. $s", p.error, hint = "Wait a minute and try again.")
            else -> GoogleError("Google sign-in could not start. $s", p.error)
        }
    }

    /** Error while polling https://oauth2.googleapis.com/token for the device code. null = the user has not finished yet. */
    fun poll(http: Int, body: String): GoogleError? {
        val p = parse(body)
        val s = said(http, p)
        return when (p.error) {
            "authorization_pending" -> null
            "slow_down" -> GoogleError("slow down", "slow_down")
            "access_denied" -> GoogleError("Google says access was denied. $s", p.error,
                hint = "Either Cancel was pressed on the Google page, or this Google account is not on the test-user list. While the app is " +
                    "in \"Testing\", add your Gmail in Google Auth Platform > Audience > Test users, then sign in again.", link = CONSENT_URL)
            "expired_token" -> GoogleError("The code expired before it was approved. Start sign-in again.", p.error)
            "invalid_client", "unauthorized_client" -> GoogleError("Google did not accept the Client ID / Client secret pair. $s", p.error,
                hint = "If you entered your own client under Advanced, copy both again from the same \"TVs and Limited Input devices\" client, " +
                    "or clear them to use the built-in client. Otherwise update the app.", link = CLIENTS_URL)
            "org_internal" -> GoogleError("This client only allows accounts from one Google Workspace organisation. $s", p.error,
                hint = "Set the user type to External and add your Gmail as a test user.", link = CONSENT_URL)
            "admin_policy_enforced" -> GoogleError("Your Google Workspace administrator blocks this permission. $s", p.error,
                hint = "Use a personal Gmail account, or ask the administrator to allow the app.")
            "invalid_grant" -> GoogleError("The sign-in code was already used or is invalid. Start sign-in again. $s", p.error)
            else -> GoogleError("Google sign-in failed. $s", p.error)
        }
    }

    /** Error refreshing the access token with the saved refresh token. */
    fun refresh(http: Int, body: String): GoogleError {
        val p = parse(body)
        return if (p.error == "invalid_grant" || p.error == "invalid_client" || p.error == "unauthorized_client")
            GoogleError("Google access ended (signed out, revoked, or the 7-day test-user limit ran out). Sign in again. ${said(http, p)}",
                p.error, authLost = true, hint = "To stop the weekly sign-out, publish the app in Google Auth Platform > Audience (Publish app).", link = CONSENT_URL)
        else GoogleError("Could not refresh Google access. ${said(http, p)}", p.error)
    }

    /** Error from a Drive API call. */
    fun drive(http: Int, body: String, what: String, space: DriveSpace): GoogleError {
        val p = parse(body)
        val r = p.reasons
        val raw = "Google said: ${(r.firstOrNull() ?: p.error).ifEmpty { "HTTP $http" }} — ${p.description.take(300)}"
        if (http == 401) return GoogleError("Google access expired", "401")
        if (http == 403 && ("accessNotConfigured" in r || "SERVICE_DISABLED" in r || p.description.contains("has not been used", true) || p.description.contains("is disabled", true)))
            return GoogleError("The Google Drive API is turned off in your Google Cloud project. $raw", "drive_disabled",
                hint = "Open the link, press Enable, wait a minute, then press Sync now.", link = p.link.ifEmpty { DRIVE_API_URL })
        if (http == 403 && ("insufficientPermissions" in r || "ACCESS_TOKEN_SCOPE_INSUFFICIENT" in r || p.description.contains("insufficient", true)))
            return GoogleError("Google did not give TVstreamer the Drive permission it needs (${space.scope.substringAfterLast('/')}). $raw", "scope_missing",
                authLost = true, hint = "Sign in again. $TICK_DRIVE")
        if (http == 403 && ("rateLimitExceeded" in r || "userRateLimitExceeded" in r))
            return GoogleError("Google Drive is busy (rate limit). It will retry later. $raw", "rate")
        if (http == 403 && "storageQuotaExceeded" in r)
            return GoogleError("Your Google Drive is full. Free some space, then press Sync now. $raw", "quota")
        return GoogleError("Drive $what failed. $raw", r.firstOrNull() ?: p.error)
    }

    /** True when the space-separated [granted] scope list from the token reply contains [needed]. Empty = unknown, assume yes. */
    fun hasScope(granted: String?, needed: String): Boolean =
        granted.isNullOrBlank() || granted.split(' ', '\t', '\n').any { it == needed }

    fun scopeMissing(space: DriveSpace) = GoogleError(
        "Google signed you in but did not give permission to store TVstreamer's data in Google Drive, so sync cannot work.",
        "scope_missing", hint = TICK_DRIVE + " A new code is shown below — sign in again with it."
    )
}
