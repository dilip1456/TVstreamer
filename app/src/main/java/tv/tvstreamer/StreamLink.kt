package tv.tvstreamer

/**
 * create_link answers -> playable URLs (pure logic, unit tested).
 *
 * Root cause of "channels that don't load" (v1.52): for live channels the old code returned any http answer as-is,
 * before the checks VOD got. Portals often answer "http://localhost/ch/123_" (meaning: the portal's own host) or a URL
 * whose stream / token is empty ("...?stream=&extension=ts", "play_token=&") when the session is stale. The player then
 * tried an address that can never play, failed twice and showed an error. Now live answers get the same checks: localhost
 * becomes the portal host, and an empty stream / token means "sign in again and ask once more" ([needsFreshToken]).
 */
object StreamLink {
    private val SCHEMES = listOf("http://", "https://", "rtsp://", "rtmp://", "udp://", "rtp://")
    private val EMPTY_STREAM = Regex("(?i)[?&](?:stream|play_token|token)=(?:&|$)")

    /** "ffmpeg http://x" / "ffrt http://x" / "auto http://x" -> "http://x". */
    fun clean(cmd: String): String {
        var raw = cmd.trim()
        for (p in listOf("ffmpeg ", "ffrt ", "ffrt2 ", "ffrt3 ", "ffrt4 ", "auto ")) raw = raw.removePrefix(p).trim()
        if (raw.contains(" ")) raw = raw.substringAfterLast(" ").trim()
        return raw
    }

    /** True when the answer is a stream address with an empty stream id or token (stale session). */
    fun needsFreshToken(rawCmd: String): Boolean {
        val raw = clean(rawCmd)
        return SCHEMES.any { raw.lowercase().startsWith(it) } && EMPTY_STREAM.containsMatchIn(raw)
    }

    /** A playable URL from a create_link answer, or "" (live and VOD follow the same rules). [origin] = portal host. */
    fun usable(rawCmd: String, origin: String, vod: Boolean = true): String {
        val raw = clean(rawCmd)
        if (raw.isEmpty()) return ""
        val l = raw.lowercase()
        if (SCHEMES.any { l.startsWith(it) }) {
            if (EMPTY_STREAM.containsMatchIn(raw)) return ""
            val host = Regex("^[a-z]+://([^/:]+)").find(l)?.groupValues?.get(1) ?: ""
            if (host == "localhost" || host == "127.0.0.1") {
                if (origin.isEmpty()) return ""
                val path = raw.substringAfter("://").substringAfter("/", "")
                // The unchanged placeholder ("http://localhost/ch/123_") is a request for a link, not a stream
                if (!vod && Regex("^ch/\\d+_?$").matches(path)) return ""
                return "$origin/$path"
            }
            return raw
        }
        if (raw.startsWith("/") && vod) return if (origin.isNotEmpty()) origin + raw else ""
        return ""
    }

    /** The cmd to ask create_link with for a live channel: its cmd, else the first of "cmds", else the MAG placeholder. */
    fun liveCmd(cmd: String, cmds: List<String>, id: String): String = when {
        cmd.isNotBlank() -> cmd
        cmds.any { it.isNotBlank() } -> cmds.first { it.isNotBlank() }
        id.isNotBlank() -> "ffrt http://localhost/ch/${id}_"
        else -> ""
    }
}
