package tv.tvstreamer

/**
 * D-pad Up / Down with the player controls hidden (pure, unit-tested).
 * Live TV still zaps channels; movies and series episodes only show the player controls.
 */
object PlayerPad {
    enum class Action { SHOW_CONTROLS, ZAP_UP, ZAP_DOWN }

    fun hiddenVertical(live: Boolean, up: Boolean): Action = when {
        live && up -> Action.ZAP_UP
        live -> Action.ZAP_DOWN
        else -> Action.SHOW_CONTROLS
    }
}
