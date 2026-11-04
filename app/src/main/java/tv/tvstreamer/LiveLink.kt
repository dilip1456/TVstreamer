package tv.tvstreamer

/**
 * Live link strategies, stream sniffing and masked diagnostics (1.53, pure logic, unit tested).
 *
 * Stalker portals answer live create_link in many dialects. A channel row carries "cmd" ("ffrt http://localhost/ch/123_",
 * "ffmpeg http://host/…", "auto http://…", sometimes a direct URL), "cmds" (alternate entries, each with its own url
 * and ch_id) and "use_http_tmp_link" (1 = the cmd must be turned into a temporary link; 0 with a real http cmd = the
 * cmd itself plays). [tries] lists every way worth asking, most likely first, without duplicates.
 */
object LiveLink {
    /** One way to get a live stream: a create_link query ([query]) or a direct URL ([direct]). */
    data class Try(val label: String, val cmd: String, val query: String? = null, val direct: Boolean = false)

    /** What the channel list told us about a channel's stream. */
    data class Meta(val cmds: List<String> = emptyList(), val useTmp: Boolean = true, val cmdChIds: List<String> = emptyList())

    private fun isRealHttp(c: String): Boolean {
        val l = c.lowercase()
        return (l.startsWith("http://") || l.startsWith("https://")) && "://localhost" !in l && "://127.0.0.1" !in l
    }

    fun tries(cmd: String, id: String, meta: Meta = Meta(), enc: (String) -> String = { java.net.URLEncoder.encode(it, "UTF-8") }): List<Try> {
        val out = ArrayList<Try>()
        val seen = HashSet<String>()
        fun q(c: String, rest: String) = "type=itv&action=create_link&cmd=${enc(c)}$rest"
        fun addLink(label: String, c: String, rest: String) { val qq = q(c, rest); if (seen.add(qq)) out += Try(label, c, qq) }
        val main = StreamLink.liveCmd(cmd, meta.cmds, id)
        // use_http_tmp_link=0 and a real URL: the cmd is the stream (a MAG box plays it as is)
        if (!meta.useTmp && isRealHttp(StreamLink.clean(main))) out += Try("direct cmd (no temp link)", main, direct = true).also { seen += "d:$main" }
        if (main.isNotEmpty()) {
            addLink("live cmd", main, "&series=&forced_storage=&disable_ad=0&download=0")
            addLink("live cmd, forced_storage=0", main, "&series=0&forced_storage=0&disable_ad=0&download=0")
            addLink("live cmd, bare", main, "")
        }
        // Alternate entries from "cmds" (other servers / qualities)
        meta.cmds.filter { it.isNotBlank() && it != main }.distinct().take(3).forEachIndexed { n, c ->
            addLink("alternate cmd ${n + 1}", c, "&series=&forced_storage=&disable_ad=0&download=0")
        }
        // The MAG placeholder by channel id (and by the cmds' own ch_id when it differs)
        (listOf(id) + meta.cmdChIds).filter { it.isNotBlank() }.distinct().take(2).forEach { cid ->
            val ph = "ffrt http://localhost/ch/${cid}_"
            if (ph != main) addLink(if (cid == id) "placeholder by channel id" else "placeholder by cmds ch_id", ph, "&series=&forced_storage=&disable_ad=0&download=0")
        }
        // Last resort: a real URL in the cmd played directly
        val c = StreamLink.clean(main)
        if (isRealHttp(c) && seen.add("d:$main")) out += Try("direct cmd", main, direct = true)
        return out
    }

    enum class Format { HLS, TS, MP4, DASH, HTML, JSON, EMPTY, UNKNOWN }

    /** What a stream really is, from its first bytes (wins) or its Content-Type. */
    fun sniff(contentType: String?, head: ByteArray?): Format {
        val b = head ?: ByteArray(0)
        val text = String(b, 0, minOf(b.size, 64), Charsets.ISO_8859_1).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        when {
            text.startsWith("#EXTM3U") -> return Format.HLS
            b.isNotEmpty() && b[0] == 0x47.toByte() && (b.size < 189 || b[188] == 0x47.toByte()) -> return Format.TS
            b.size >= 8 && String(b, 4, 4, Charsets.ISO_8859_1) in setOf("ftyp", "moof", "styp") -> return Format.MP4
            text.startsWith("<?xml") && "MPD" in String(b, Charsets.ISO_8859_1) -> return Format.DASH
            text.startsWith("<", true) -> return Format.HTML
            text.startsWith("{") || text.startsWith("[") -> return Format.JSON
        }
        val ct = contentType.orEmpty().lowercase()
        return when {
            "mpegurl" in ct -> Format.HLS
            "mp2t" in ct || "mpeg-ts" in ct -> Format.TS
            "mp4" in ct -> Format.MP4
            "dash" in ct -> Format.DASH
            "html" in ct -> Format.HTML
            "json" in ct -> Format.JSON
            b.isEmpty() -> Format.EMPTY
            else -> Format.UNKNOWN
        }
    }

    /** Short kind of host, never the host itself: "portal host", "localhost", "IP address", "other domain". */
    fun hostKind(url: String, portalOrigin: String): String {
        val host = Regex("^[a-z]+://([^/:?#]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.lowercase() ?: return "none"
        val ph = Regex("^[a-z]+://([^/:?#]+)", RegexOption.IGNORE_CASE).find(portalOrigin)?.groupValues?.get(1)?.lowercase()
        return when {
            host == ph -> "portal host"
            host == "localhost" || host == "127.0.0.1" -> "localhost"
            Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(host) -> "IP address"
            else -> "other domain"
        }
    }

    /**
     * A URL safe to show: scheme, host kind, path with long ids / numbers masked, query keys only.
     * "http://1.2.3.4:8080/live/user/pass/123.ts?token=abc" -> "http://[IP address]/live/…/…/#.ts?token=…"
     */
    private val SAFE_SEGMENTS = setOf("live", "hls", "ch", "play", "stream", "streams", "movie", "series", "index", "playlist",
        "mono", "tracks", "media", "tv", "channel", "chunks", "master", "iptv", "c", "portal.php", "server", "load.php", "live.php")

    fun maskUrl(url: String, portalOrigin: String): String {
        if (url.isBlank()) return ""
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme.isEmpty()) return "(not a URL)"
        val rest = url.substringAfter("://")
        val path = rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
        val query = rest.substringAfter('?', "")
        val segs = path.split('/').filter { it.isNotEmpty() }.map { s ->
            val ext = s.substringAfterLast('.', "").takeIf { it.length in 2..4 && s.contains('.') }
            val base = if (ext != null) s.dropLast(ext.length + 1) else s
            val m = when {
                base.all { it.isDigit() } -> "#"
                base.lowercase() in SAFE_SEGMENTS -> base
                else -> "…"   // user names, passwords, ids, tokens: never shown
            }
            if (ext != null) "$m.$ext" else m
        }
        val keys = query.split('&').filter { it.isNotEmpty() }.map { it.substringBefore('=') + "=…" }
        return "$scheme://[${hostKind(url, portalOrigin)}]/" + segs.joinToString("/") + (if (keys.isNotEmpty()) "?" + keys.joinToString("&") else "")
    }

    /** Failure class from an HTTP status / sniffed format (for the diagnostics and the retry choice). */
    fun classify(http: Int?, format: Format?): String = when {
        http == 401 || http == 403 -> "not authorised (expired token, MAC not allowed or too many connections)"
        http == 404 || http == 410 -> "offline / not found"
        http == 458 || http == 429 || http == 509 -> "connection limit"
        http != null && http >= 500 -> "stream server error"
        format == Format.HTML || format == Format.JSON -> "not a stream (the server answered a page)"
        format == Format.EMPTY -> "empty answer"
        http != null && http in 200..299 && (format == Format.HLS || format == Format.TS || format == Format.MP4) -> "stream reachable (format ${format.name})"
        else -> "unknown"
    }

    /** A fresh sign-in may help: the stream server refused the token / session. */
    fun needsResign(http: Int?, format: Format?): Boolean = http == 401 || http == 403 || format == Format.HTML || format == Format.JSON

    /** The ExoPlayer format override to retry with (1 = HLS, 2 = TS), or 0 when the sniffed format gives no hint. */
    fun forceFor(format: Format?): Int = when (format) { Format.HLS -> 1; Format.TS -> 2; else -> 0 }

    /**
     * Auto-skip a dead channel the user zapped onto: keep the zap direction, at most [max] in a row (then stop on the
     * error so a portal that is down does not spin through every channel). null = stay.
     */
    fun skipStep(zapDir: Int, skippedInRow: Int, max: Int = 5): Int? = if (zapDir != 0 && skippedInRow < max) zapDir else null
}

/** The last link attempts per channel (masked; on this device only). Shown in Channel info and Settings. */
object LinkDiag {
    data class Attempt(val at: Long, val channel: String, val strategy: String, val step: Int, val steps: Int,
                       val url: String = "", val http: Int? = null, val format: String = "", val result: String = "", val resigned: Boolean = false) {
        fun line(): String = buildString {
            append(hhmmSafe(at)).append("  ").append(channel).append('\n')
            append("  way ${step + 1}/$steps: ").append(strategy)
            if (resigned) append(" (after a new sign-in)")
            if (url.isNotEmpty()) append("\n  link: ").append(url)
            if (http != null) append("\n  HTTP ").append(http)
            if (format.isNotEmpty()) append("  ·  ").append(format)
            if (result.isNotEmpty()) append("\n  → ").append(result)
        }
    }
    private fun hhmmSafe(t: Long) = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(t))

    private val byChannel = LinkedHashMap<String, Attempt>()
    /** Channel keys -> preferred ExoPlayer format override learned from sniffing (1 HLS, 2 TS). */
    private val formats = HashMap<String, Int>()

    @Synchronized fun record(key: String, a: Attempt) {
        byChannel.remove(key); byChannel[key] = a
        while (byChannel.size > 40) byChannel.remove(byChannel.keys.first())
    }
    @Synchronized fun update(key: String, f: (Attempt) -> Attempt) { byChannel[key]?.let { byChannel[key] = f(it) } }
    @Synchronized fun of(key: String): Attempt? = byChannel[key]
    @Synchronized fun all(): List<Attempt> = byChannel.values.reversed()
    @Synchronized fun learnFormat(key: String, force: Int) { if (force > 0) formats[key] = force }
    @Synchronized fun formatOf(key: String): Int = formats[key] ?: 0
    fun report(): String = all().joinToString("\n\n") { it.line() }.ifEmpty { "No live link attempts yet. Play a channel first." }
}
