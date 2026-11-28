package tv.tvstreamer

/**
 * Shared look for v1.99.7 overlays and focus: semi-transparent panels, a single
 * blended focus style (light text on a brand-blue pill), and optional blur on
 * API 31+ when the device can afford it. Pure so it can be unit-tested.
 */
object OverlayLook {
    /** Panel fill opacity (80–88 %). */
    const val PANEL_ALPHA = 0.84f
    /** Floating top bar opacity. */
    const val BAR_ALPHA = 0.72f
    /** Backdrop behind a popup (not the panel itself). */
    const val SCRIM_ALPHA = 0.45f
    /** Channel info banner auto-hide after a channel change, in milliseconds. */
    const val INFO_MS = 3_000L

    const val UPDATE_NOW = "Update Now"

    fun useBlur(sdk: Int, lowRam: Boolean): Boolean = sdk >= 31 && !lowRam

    fun panelAlpha(alpha: Float = PANEL_ALPHA): Float = alpha.coerceIn(0.80f, 0.88f)
}
