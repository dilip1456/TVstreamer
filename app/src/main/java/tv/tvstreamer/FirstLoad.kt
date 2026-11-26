package tv.tvstreamer

/**
 * First-launch / empty-cache loading state (1.99.3). Pure logic, so it can be unit-tested.
 * Home and Live TV use this when the channel or catalog cache is empty, instead of a blank screen.
 * It only describes what is already being loaded: no new portal calls, and Stop still keeps what has arrived.
 */
object FirstLoad {
    enum class Kind { HOME, LIVE }

    data class State(
        val visible: Boolean,
        val title: String = "",
        val message: String = "",
        /** 0..1 when a total is known; null means an indeterminate bar with a running count. */
        val fraction: Float? = null,
        val detail: String = "",
        val canStop: Boolean = false,
        val stopped: Boolean = false,
    )

    const val FIRST_LOAD_HINT = "This is the first load and may take a minute."
    const val TITLE_CHANNELS = "Loading your channels"
    const val TITLE_LIBRARY = "Loading your library"
    const val TITLE_STOPPED = "Loading stopped"

    /**
     * @param hasPortal false when the portal URL or MAC has not been entered (Settings is the right message then)
     * @param liveFailed the channel download failed and nothing is saved (Live TV already offers Retry)
     */
    fun of(
        kind: Kind,
        hasPortal: Boolean,
        hasChannels: Boolean,
        hasCatalog: Boolean,
        localReading: Boolean,
        liveFetching: Boolean,
        liveStatus: String,
        channelCount: Int,
        catalogLoading: Boolean,
        catalogDone: Int,
        catalogTotal: Int,
        catalogSize: Int,
        catalogStopped: Boolean,
        liveFailed: Boolean = LiveStatus.failed(liveStatus),
    ): State {
        if (!hasPortal) return State(visible = false)
        if (kind == Kind.LIVE) {
            if (hasChannels || liveFailed) return State(visible = false)
            return waiting(
                title = TITLE_CHANNELS,
                channels = channelCount,
                catalogLoading = catalogLoading,
                catalogDone = catalogDone,
                catalogTotal = catalogTotal,
                catalogSize = catalogSize,
                catalogStopped = catalogStopped,
                localReading = localReading,
                liveStatus = liveStatus,
            )
        }
        // Home: hide once the catalog has something to show; otherwise cover empty rows while work is in flight.
        if (hasCatalog) return State(visible = false)
        val waitingOnWork = localReading || liveFetching || catalogLoading ||
            liveStatus.isNotEmpty() || catalogStopped || !hasChannels
        if (!waitingOnWork) return State(visible = false)
        val title = when {
            catalogStopped && !catalogLoading -> TITLE_STOPPED
            hasChannels && (catalogLoading || catalogStopped) -> TITLE_LIBRARY
            else -> TITLE_CHANNELS
        }
        return waiting(
            title = title,
            channels = channelCount,
            catalogLoading = catalogLoading,
            catalogDone = catalogDone,
            catalogTotal = catalogTotal,
            catalogSize = catalogSize,
            catalogStopped = catalogStopped,
            localReading = localReading,
            liveStatus = liveStatus,
        )
    }

    private fun waiting(
        title: String,
        channels: Int,
        catalogLoading: Boolean,
        catalogDone: Int,
        catalogTotal: Int,
        catalogSize: Int,
        catalogStopped: Boolean,
        localReading: Boolean,
        liveStatus: String,
    ): State {
        val stopped = catalogStopped && !catalogLoading
        return State(
            visible = true,
            title = title,
            message = if (stopped)
                "Whatever has already loaded is kept. Press Resume to continue, or open Settings."
            else FIRST_LOAD_HINT,
            fraction = fraction(catalogDone, catalogTotal),
            detail = detail(channels, catalogDone, catalogTotal, catalogSize, localReading, liveStatus, catalogLoading, stopped),
            canStop = catalogLoading && !catalogStopped,
            stopped = stopped,
        )
    }

    /** Determinate when the loader already knows a total; otherwise the bar is indeterminate. */
    fun fraction(done: Int, total: Int): Float? =
        if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null

    fun detail(
        channels: Int,
        catalogDone: Int,
        catalogTotal: Int,
        catalogSize: Int,
        localReading: Boolean,
        liveStatus: String,
        catalogLoading: Boolean,
        stopped: Boolean,
    ): String {
        val parts = mutableListOf<String>()
        when {
            catalogTotal > 0 -> {
                parts += "Page %,d of %,d".format(catalogDone.coerceAtLeast(0), catalogTotal)
                if (catalogSize > 0) parts += "%,d titles".format(catalogSize)
            }
            catalogSize > 0 -> parts += "%,d titles".format(catalogSize)
            channels > 0 -> parts += "%,d channels".format(channels)
            localReading -> parts += "Opening saved channels…"
            liveStatus.isNotEmpty() && !LiveStatus.failed(liveStatus) -> parts += liveStatus
            catalogLoading -> parts += "Starting…"
            stopped -> parts += "Nothing saved yet"
        }
        return parts.joinToString(" · ")
    }
}
