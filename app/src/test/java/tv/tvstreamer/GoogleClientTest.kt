package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class GoogleClientTest {
    private val built = OAuthClient("built.apps.googleusercontent.com", "built-secret", true)
    private val none = OAuthClient("", "", false)
    private val mine = OAuthClient("mine.apps.googleusercontent.com", "mine-secret", false)

    @Test fun builtInIsTheDefault() {
        assertEquals(built, GoogleClient.forSignIn(built, none))
        assertEquals(built, GoogleClient.forSignIn(built, OAuthClient("half-entered", "", false)))   // incomplete override ignored
    }

    @Test fun completeOverrideWins() = assertEquals(mine, GoogleClient.forSignIn(built, mine))

    @Test fun noClientAtAllIsNotUsable() = assertFalse(GoogleClient.forSignIn(OAuthClient("", "", true), none).usable)

    @Test fun refreshUsesTheClientThatSignedIn() {
        assertEquals(built, GoogleClient.forRefresh(built, mine, built.id))   // override saved later: keep the built-in sign-in
        assertEquals(mine, GoogleClient.forRefresh(built, mine, mine.id))
        assertEquals(mine, GoogleClient.forRefresh(built, mine, ""))          // sign-in from before 1.46 used the user's own client
        assertEquals(built, GoogleClient.forRefresh(built, none, ""))
    }

    @Test fun localBuildWithoutSecretsHasAnEmptyBuiltInClient() {
        // Unit tests run from a local build without the CI secrets; the fields must exist and be strings
        assertNotNull(BuildConfig.GOOGLE_CLIENT_ID); assertNotNull(BuildConfig.GOOGLE_CLIENT_SECRET)
    }
}
