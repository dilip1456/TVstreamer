package tv.tvstreamer

/** A Google OAuth client (TV / limited-input type): the built-in one from the build, or the user's own override. */
data class OAuthClient(val id: String, val secret: String, val builtIn: Boolean) {
    val usable: Boolean get() = id.isNotBlank() && secret.isNotBlank()
}

/** Which client to use. Pure so it can be tested without Android. */
object GoogleClient {
    /** The client compiled into this APK (from the CI secrets); empty in local builds without them. */
    val builtIn: OAuthClient get() = OAuthClient(BuildConfig.GOOGLE_CLIENT_ID.trim(), BuildConfig.GOOGLE_CLIENT_SECRET.trim(), true)

    /** For a new sign-in: a complete override wins (advanced setting), otherwise the built-in client. */
    fun forSignIn(builtIn: OAuthClient, custom: OAuthClient): OAuthClient =
        if (custom.usable) custom else builtIn

    /**
     * For refreshing an existing sign-in: Google only accepts the client that issued the token, so use the one
     * recorded at sign-in ([tokenClientId]). Sign-ins from before 1.46 recorded nothing; they used the user's own
     * client, so prefer that when it is still saved.
     */
    fun forRefresh(builtIn: OAuthClient, custom: OAuthClient, tokenClientId: String): OAuthClient = when {
        tokenClientId.isNotEmpty() && custom.usable && custom.id == tokenClientId -> custom
        tokenClientId.isNotEmpty() && builtIn.id == tokenClientId -> builtIn
        else -> forSignIn(builtIn, custom)
    }
}
