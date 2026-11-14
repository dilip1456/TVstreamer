package tv.tvstreamer

/**
 * In-app updater decisions (pure, unit tested): compare installed vs GitHub release, honour Ask-once
 * vs Auto, and remember a declined version so the same release is not offered again.
 * Auto is the default: download and install in the background, then show a short "Updated" notice.
 * Ask-once shows an in-app banner (not a popup) when a new version is ready.
 */
object UpdateLogic {
    enum class Mode {
        ASK_ONCE, AUTO;
        val label get() = when (this) {
            ASK_ONCE -> "Ask once"
            AUTO -> "Auto"
        }
        val detail get() = when (this) {
            ASK_ONCE -> "Show a banner when a new version is ready"
            AUTO -> "Download and install in the background"
        }
        fun next(): Mode = if (this == ASK_ONCE) AUTO else ASK_ONCE
        companion object {
            fun parse(s: String?) = if (s == "ask") ASK_ONCE else AUTO
            fun encode(m: Mode) = if (m == AUTO) "auto" else "ask"
        }
    }

    enum class Decision { UP_TO_DATE, SKIP_DECLINED, OFFER, AUTO_INSTALL }

    /** What the in-app update banner should show. Never a popup. */
    enum class Banner { HIDDEN, PROGRESS, INSTALLING, OFFER, SUCCESS, CONFIRM, MESSAGE }

    data class Offer(val code: Long, val name: String, val tag: String, val apkUrl: String, val size: Long)

    /** "TVStreamer 1.58 (code 58)" -> 58. Falls back to the tag "v1.58" -> 58 (the workflow uses code == minor). */
    fun remoteCode(name: String, tag: String): Long {
        Regex("code\\s*(\\d+)", RegexOption.IGNORE_CASE).find(name)?.let { return it.groupValues[1].toLong() }
        val parts = tag.removePrefix("v").removePrefix("build-").split(".")
        return parts.getOrNull(1)?.toLongOrNull() ?: parts.getOrNull(0)?.toLongOrNull() ?: -1
    }

    fun isNewer(installed: Long, remote: Long): Boolean = remote > 0L && remote > installed

    /**
     * [manual] is a user-started "Check for updates": a previously declined version may be offered again.
     * Launch checks skip a version the user already dismissed in Ask-once mode.
     */
    fun decide(installed: Long, remote: Long, declined: Long, mode: Mode, manual: Boolean): Decision {
        if (!isNewer(installed, remote)) return Decision.UP_TO_DATE
        if (mode == Mode.AUTO) return Decision.AUTO_INSTALL
        if (!manual && declined == remote) return Decision.SKIP_DECLINED
        return Decision.OFFER
    }

    fun progress(received: Long, total: Long): Int =
        if (total > 0L) ((received * 100L) / total).toInt().coerceIn(0, 100) else 0

    fun banner(progress: Int, busy: Boolean, hasPending: Boolean, status: String): Banner {
        if (hasPending) return Banner.OFFER
        if (progress in 0..99) return Banner.PROGRESS
        if (progress == 100 || status.startsWith("Installing")) return Banner.INSTALLING
        if (status.startsWith("Updated")) return Banner.SUCCESS
        val s = status.lowercase()
        if ("confirm " in s || "one-time confirm" in s || "one time" in s) return Banner.CONFIRM
        if (status.isBlank() || status.startsWith("Up to date") || status.startsWith("Update skipped") ||
            status.startsWith("Checking") || (!busy && status.isEmpty())) return Banner.HIDDEN
        if (status.startsWith("Update") || status.startsWith("Turn on") || status.startsWith("Already checking"))
            return Banner.MESSAGE
        return Banner.HIDDEN
    }
}
