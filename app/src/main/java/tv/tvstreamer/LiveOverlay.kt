package tv.tvstreamer

/**
 * Live TV full-screen playback (1.53, pure logic, unit tested). Live channels never show the movie player controls
 * (no seek bar, no play / pause, no arrows). Only these overlays exist, one at a time:
 *  - [Layer.INFO]: a floating bar at the bottom with what is on now (progress) and next; hides by itself.
 *  - [Layer.CHANNELS]: Left. The channels of the playing channel's category.
 *  - [Layer.GUIDE]: Right. This channel's schedule as a list.
 * Up / Down change channel at once (also while the info bar shows). Back closes the top overlay, then leaves.
 */
object LiveOverlay {
    enum class Layer { NONE, INFO, CHANNELS, GUIDE }
    enum class Pad { UP, DOWN, LEFT, RIGHT, OK, BACK, MENU }
    sealed class Effect {
        object None : Effect()
        data class Zap(val step: Int) : Effect()
        object Retry : Effect()
        object Menu : Effect()
        object Exit : Effect()
    }

    const val INFO_MS = 5_000L

    /**
     * A key on the video (no popup has focus). The side panels handle their own keys (Up / Down / OK) and close on
     * Back themselves, so with [Layer.CHANNELS] or [Layer.GUIDE] showing only Back is answered here.
     */
    fun press(layer: Layer, key: Pad, hasError: Boolean = false, channels: Int = 2): Pair<Layer, Effect> = when (layer) {
        Layer.CHANNELS, Layer.GUIDE -> if (key == Pad.BACK) Layer.NONE to Effect.None else layer to Effect.None
        else -> when (key) {
            Pad.UP -> if (channels > 1) Layer.INFO to Effect.Zap(1) else layer to Effect.None
            Pad.DOWN -> if (channels > 1) Layer.INFO to Effect.Zap(-1) else layer to Effect.None
            Pad.LEFT -> Layer.CHANNELS to Effect.None
            Pad.RIGHT -> Layer.GUIDE to Effect.None
            Pad.OK -> if (hasError) layer to Effect.Retry else (if (layer == Layer.INFO) Layer.NONE else Layer.INFO) to Effect.None
            Pad.MENU -> Layer.NONE to Effect.Menu
            Pad.BACK -> if (layer == Layer.INFO) Layer.NONE to Effect.None else Layer.NONE to Effect.Exit
        }
    }

    /**
     * The channels of the playing channel's category, in list order, and the playing one's position in it. A list
     * with no category info (or a single-channel category) falls back to the whole list.
     */
    fun categoryList(list: List<Item>, idx: Int): Pair<List<Int>, Int> {
        val cur = list.getOrNull(idx) ?: return emptyList<Int>() to -1
        val ix = list.indices.filter { list[it].cat == cur.cat && cur.cat.isNotEmpty() }
        val use = if (ix.size > 1) ix else list.indices.toList()
        return use to use.indexOf(idx)
    }
}
