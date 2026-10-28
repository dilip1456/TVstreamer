package tv.tvstreamer

import okhttp3.FormBody
import org.junit.Assert.*
import org.junit.Test

class GoogleErrorsTest {
    @Test fun scopeStringIsExactAndFormEncoded() {
        assertEquals("openid email profile https://www.googleapis.com/auth/drive.appdata", DriveSpace.APPDATA.scopes)
        assertEquals("openid email profile https://www.googleapis.com/auth/drive.file", DriveSpace.FILE.scopes)
        val f = FormBody.Builder().add("scope", DriveSpace.APPDATA.scopes).build()
        assertEquals("openid%20email%20profile%20https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.appdata", f.encodedValue(0))
    }

    @Test fun invalidScopeAtStartKeepsGooglesWords() {
        val e = GoogleErrors.deviceStart(400, """{"error":"invalid_scope","error_description":"Some requested scopes were invalid. {invalid=[https://www.googleapis.com/auth/drive.appdata]}"}""", DriveSpace.APPDATA)
        assertEquals("invalid_scope", e.code)
        assertTrue(e.message!!.contains("Some requested scopes were invalid"))
        assertTrue(e.hint.contains("Data access"))
        assertEquals(GoogleErrors.DATA_ACCESS_URL, e.link)
    }

    @Test fun invalidClientMentionsClientType() {
        val e = GoogleErrors.deviceStart(401, """{"error":"invalid_client","error_description":"Invalid client type."}""", DriveSpace.APPDATA)
        assertTrue(e.message!!.contains("Invalid client type"))
        assertTrue(e.hint.contains("TVs and Limited Input devices"))
    }

    @Test fun rateLimitUsesErrorCodeField() {
        assertEquals("rate_limit_exceeded", GoogleErrors.deviceStart(403, """{"error_code":"rate_limit_exceeded"}""", DriveSpace.APPDATA).code)
    }

    @Test fun pollMapsEveryCase() {
        assertNull(GoogleErrors.poll(428, """{"error":"authorization_pending","error_description":"Precondition Required"}"""))
        assertEquals("slow_down", GoogleErrors.poll(403, """{"error":"slow_down"}""")!!.code)
        val denied = GoogleErrors.poll(403, """{"error":"access_denied","error_description":"Forbidden"}""")!!
        assertTrue(denied.hint.contains("Test users"))
        assertFalse(denied.message!!.contains("— Forbidden"))
        assertEquals("org_internal", GoogleErrors.poll(403, """{"error":"org_internal"}""")!!.code)
        assertEquals("admin_policy_enforced", GoogleErrors.poll(400, """{"error":"admin_policy_enforced"}""")!!.code)
        assertTrue(GoogleErrors.poll(401, """{"error":"invalid_client","error_description":"Unauthorized"}""")!!.hint.contains("GOCSPX-"))
    }

    @Test fun refreshInvalidGrantSignsOut() {
        assertTrue(GoogleErrors.refresh(400, """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""").authLost)
        assertFalse(GoogleErrors.refresh(500, "oops").authLost)
    }

    @Test fun driveApiDisabledGivesActivationLink() {
        val body = """{"error":{"code":403,"message":"Google Drive API has not been used in project 123456 before or it is disabled. Enable it by visiting https://console.developers.google.com/apis/api/drive.googleapis.com/overview?project=123456 then retry.","status":"PERMISSION_DENIED",
            "errors":[{"message":"x","domain":"usageLimits","reason":"accessNotConfigured"}],
            "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"SERVICE_DISABLED","metadata":{"activationUrl":"https://console.developers.google.com/apis/api/drive.googleapis.com/overview?project=123456"}}]}}"""
        val e = GoogleErrors.drive(403, body, "list", DriveSpace.APPDATA)
        assertEquals("drive_disabled", e.code)
        assertEquals("https://console.developers.google.com/apis/api/drive.googleapis.com/overview?project=123456", e.link)
        assertTrue(e.message!!.contains("has not been used in project 123456"))
    }

    @Test fun driveDisabledLinkFromMessageOnly() {
        val e = GoogleErrors.drive(403, """{"error":{"code":403,"message":"Drive API is disabled. Enable it by visiting https://console.developers.google.com/apis/api/drive.googleapis.com/overview?project=9 then retry."}}""", "list", DriveSpace.APPDATA)
        assertEquals("drive_disabled", e.code)
        assertEquals("https://console.developers.google.com/apis/api/drive.googleapis.com/overview?project=9", e.link)
    }

    @Test fun driveInsufficientScopeAsksToTickBox() {
        val body = """{"error":{"code":403,"message":"Request had insufficient authentication scopes.","status":"PERMISSION_DENIED","errors":[{"reason":"insufficientPermissions"}],"details":[{"reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}"""
        val e = GoogleErrors.drive(403, body, "list", DriveSpace.APPDATA)
        assertEquals("scope_missing", e.code)
        assertTrue(e.authLost)
        assertTrue(e.hint.contains("tick the box"))
    }

    @Test fun drive401IsRetryable() { assertEquals("401", GoogleErrors.drive(401, "{}", "list", DriveSpace.APPDATA).code) }

    @Test fun grantedScopeCheck() {
        val ad = DriveSpace.APPDATA.scope
        assertTrue(GoogleErrors.hasScope("openid https://www.googleapis.com/auth/userinfo.email $ad", ad))
        assertFalse(GoogleErrors.hasScope("openid https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile", ad))
        assertFalse(GoogleErrors.hasScope("$ad.readonly", ad))
        assertTrue(GoogleErrors.hasScope(null, ad))   // Google did not say: try, the Drive call explains if it fails
        assertEquals(DriveSpace.APPDATA, DriveSpace.of(null))
        assertEquals(DriveSpace.FILE, DriveSpace.of("FILE"))
    }
}
