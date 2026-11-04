package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class StreamLinkTest {
    private val o = "http://portal.example:8080"

    @Test fun liveLocalhostBecomesPortalHost() {
        assertEquals("$o/live/abc/123.ts", StreamLink.usable("ffmpeg http://localhost/live/abc/123.ts", o, vod = false))
    }

    @Test fun livePlaceholderIsNotPlayable() {
        assertEquals("", StreamLink.usable("ffrt http://localhost/ch/123_", o, vod = false))
    }

    @Test fun emptyStreamOrTokenNeedsFreshSession() {
        val u = "ffmpeg http://cdn.example/play/live.php?mac=x&stream=&extension=ts"
        assertTrue(StreamLink.needsFreshToken(u))
        assertEquals("", StreamLink.usable(u, o, vod = false))
        assertTrue(StreamLink.needsFreshToken("http://cdn.example/x?play_token=&a=1"))
        assertFalse(StreamLink.needsFreshToken("http://cdn.example/x?play_token=abc"))
    }

    @Test fun goodLinksPassUnchanged() {
        assertEquals("http://cdn.example/x.m3u8?token=t", StreamLink.usable("auto http://cdn.example/x.m3u8?token=t", o, vod = false))
    }

    @Test fun relativeOnlyForVod() {
        assertEquals("$o/movie/1.mp4", StreamLink.usable("/movie/1.mp4", o, vod = true))
        assertEquals("", StreamLink.usable("/movie/1.mp4", o, vod = false))
    }

    @Test fun liveCmdFallbacks() {
        assertEquals("ffrt http://a", StreamLink.liveCmd("ffrt http://a", listOf("x"), "1"))
        assertEquals("x", StreamLink.liveCmd(" ", listOf("", "x"), "1"))
        assertEquals("ffrt http://localhost/ch/7_", StreamLink.liveCmd("", emptyList(), "7"))
    }
}
