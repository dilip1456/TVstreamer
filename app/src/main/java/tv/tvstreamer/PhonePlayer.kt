package tv.tvstreamer

/**
 * Phone / mobile player orientation (pure logic, unit tested). Fire TV / leanback is not touched.
 *
 * Playback starts in portrait unless Settings > Player says otherwise. A Fullscreen control switches to
 * landscape; Exit fullscreen (or Back) returns to a portrait lock. Leaving the player also returns to portrait
 * so browsing stays portrait-locked.
 */
object PhonePlayer {
    /** Open this session in landscape? Only the Settings default; the in-player toggle does not change it. */
    fun startInLandscape(pref: Boolean): Boolean = pref

    /** After the Fullscreen / Exit control is used. */
    fun afterToggle(landscape: Boolean): Boolean = !landscape

    /** On the phone, Back in landscape exits fullscreen first instead of leaving the player. */
    fun backExitsFullscreen(landscape: Boolean): Boolean = landscape
}
