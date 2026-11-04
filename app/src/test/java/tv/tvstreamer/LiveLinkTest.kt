package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class LiveLinkTest {
    private val enc = { s: String -> s.replace(" ", "+") }

    @Test fun placeholderTriesAllDialectsOnce() {
        val t = LiveLink.tries("ffrt http://localhost/ch/12_", "12", enc = enc)
        assertEquals(3, t.size)
        assertTrue(t.all { it.query!!.startsWith("type=itv&action=create_link&cmd=") })
        assertEquals(t.size, t.map { it.query }.distinct().size)
        assertTrue(t.any { "forced_storage=0" in it.query!! })
    }

    @Test fun directUrlWithoutTempLinkGoesFirst() {
        val t = LiveLink.tries("ffmpeg http://cdn.example/live/1.ts", "1", LiveLink.Meta(useTmp = false), enc)
        assertTrue(t.first().direct)
        assertEquals(1, t.count { it.direct })
    }

    @Test fun alternatesAndChIdPlaceholders() {
        val m = LiveLink.Meta(cmds = listOf("ffrt http://localhost/ch/12_", "ffrt http://localhost/ch/99_"), cmdChIds = listOf("99"))
        val t = LiveLink.tries("ffrt http://localhost/ch/12_", "12", m, enc)
        assertTrue(t.any { it.label.startsWith("alternate") && "99_" in it.cmd })
        // the ch_id placeholder equals the alternate: asked only once
        assertEquals(1, t.count { "ch/99_" in it.cmd && "series=&forced_storage=&" in it.query.orEmpty() })
    }

    @Test fun emptyCmdUsesPlaceholder() {
        val t = LiveLink.tries("", "7", enc = enc)
        assertTrue(t.first().cmd.endsWith("/ch/7_"))
    }

    @Test fun sniffByBytesThenType() {
        assertEquals(LiveLink.Format.HLS, LiveLink.sniff("text/plain", "#EXTM3U\n#EXT-X-VERSION:3".toByteArray()))
        val ts = ByteArray(376).also { it[0] = 0x47; it[188] = 0x47 }
        assertEquals(LiveLink.Format.TS, LiveLink.sniff("application/octet-stream", ts))
        assertEquals(LiveLink.Format.HTML, LiveLink.sniff("text/html", "<html>no</html>".toByteArray()))
        assertEquals(LiveLink.Format.HLS, LiveLink.sniff("application/vnd.apple.mpegurl", ByteArray(0)))
        assertEquals(LiveLink.Format.EMPTY, LiveLink.sniff(null, ByteArray(0)))
        val mp4 = "\u0000\u0000\u0000\u0018ftypmp42".toByteArray(Charsets.ISO_8859_1)
        assertEquals(LiveLink.Format.MP4, LiveLink.sniff(null, mp4))
    }

    @Test fun maskedUrlHasNoSecrets() {
        val u = "http://1.2.3.4:8080/live/bob/secretpw/12345.ts?token=abcdef&play_token=zz"
        val m = LiveLink.maskUrl(u, "http://portal.example")
        assertEquals("http://[IP address]/live/…/…/#.ts?token=…&play_token=…", m)
        listOf("bob", "secretpw", "12345", "abcdef", "1.2.3.4").forEach { assertFalse(it, m.contains(it)) }
        assertEquals("portal host", LiveLink.hostKind("http://portal.example/x", "http://portal.example:80"))
        assertEquals("other domain", LiveLink.hostKind("https://cdn.example/x", "http://portal.example"))
    }

    @Test fun classifyAndRetryChoice() {
        assertTrue(LiveLink.classify(403, null).startsWith("not authorised"))
        assertEquals("offline / not found", LiveLink.classify(404, null))
        assertEquals("connection limit", LiveLink.classify(458, null))
        assertTrue(LiveLink.needsResign(401, null))
        assertTrue(LiveLink.needsResign(200, LiveLink.Format.HTML))
        assertFalse(LiveLink.needsResign(404, null))
        assertEquals(1, LiveLink.forceFor(LiveLink.Format.HLS)); assertEquals(2, LiveLink.forceFor(LiveLink.Format.TS))
        assertEquals(0, LiveLink.forceFor(LiveLink.Format.UNKNOWN))
    }

    @Test fun autoSkipOnlyWhenZappingAndBounded() {
        assertEquals(1, LiveLink.skipStep(1, 0)); assertEquals(-1, LiveLink.skipStep(-1, 4))
        assertNull(LiveLink.skipStep(1, 5)); assertNull(LiveLink.skipStep(0, 0))
    }
}
