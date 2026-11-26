package tv.tvstreamer

/**
 * Compact context-menu labels for Live TV channels/groups and Catalog Selector / catalog groups.
 * Actions are applied by the screen; this is the menu contract (unit-tested).
 */
object ItemContext {
    const val SHOW = "Show"
    const val HIDE = "Hide"
    const val SHOW_ALL_CHANNELS = "Show channels in this section"
    const val HIDE_ALL_CHANNELS = "Hide channels in this section"
    const val SHOW_ALL = "Show all"
    const val HIDE_ALL = "Hide all"
    const val RENAME = "Rename"
    const val RESET_NAME = "Reset to original"
    const val ADD_LIST = "Add to My List"
    const val REMOVE_LIST = "Remove from My List"
    const val SELECT = "Select"
    const val SELECT_CHANNELS = "Select channels (subgroup)"
    const val COLLAPSE = "Collapse"
    const val EXPAND = "Expand"
    const val COLLAPSE_ALL = "Collapse all"
    const val EXPAND_ALL = "Expand all"

    fun channelItems(on: Boolean, inList: Boolean, renamed: Boolean): List<String> = buildList {
        add(if (on) HIDE else SHOW)
        add(RENAME)
        if (renamed) add(RESET_NAME)
        add(if (inList) REMOVE_LIST else ADD_LIST)
        add(SELECT)
    }

    fun liveGroupItems(groupOn: Boolean, folded: Boolean): List<String> = listOf(
        if (groupOn) HIDE else SHOW,
        SHOW_ALL_CHANNELS,
        HIDE_ALL_CHANNELS,
        if (folded) EXPAND else COLLAPSE,
        EXPAND_ALL,
        COLLAPSE_ALL,
        SELECT_CHANNELS
    )

    fun catalogGroupItems(groupOn: Boolean, liveType: Boolean, folded: Boolean?): List<String> = buildList {
        add(if (groupOn) HIDE else SHOW)
        add(SHOW_ALL)
        add(HIDE_ALL)
        if (liveType && folded != null) {
            add(if (folded) EXPAND else COLLAPSE)
            add(EXPAND_ALL)
            add(COLLAPSE_ALL)
        }
        add(SELECT)
    }

    fun sectionItems(folded: Boolean): List<String> = listOf(
        if (folded) EXPAND else COLLAPSE,
        EXPAND_ALL,
        COLLAPSE_ALL
    )

    /** Catalog Selector: Movies + Series + every Live TV type parent. */
    fun catalogCollapseAll(liveTypes: Collection<String>): Set<String> =
        setOf("Movies", "Series") + liveTypes.map { "live:$it" }.toSet()
}
