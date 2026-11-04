package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPrettyTest {
    @Test fun stripsCountryTagsQualityAndNumbers() {
        assertEquals("BBC One", ChannelTitle.pretty("UK: BBC One HD"))
        assertEquals("BBC One", ChannelTitle.pretty("|UK| BBC ONE FHD"))
        assertEquals("Sky Sports Main Event", ChannelTitle.pretty("[UK] SKY SPORTS MAIN EVENT 4K"))
        assertEquals("Star Plus", ChannelTitle.pretty("IN | STAR PLUS (backup)"))
        assertEquals("Zee TV", ChannelTitle.pretty("123 | Zee TV HD"))
        assertEquals("ESPN2", ChannelTitle.pretty("US: ESPN2 1080p"))
        assertEquals("CNN", ChannelTitle.pretty("USA | CNN  |  HD"))
        assertEquals("Al Jazeera", ChannelTitle.pretty("AR: Al Jazeera ᴴᴰ"))
    }

    @Test fun keepsMixedCaseNames() {
        assertEquals("beIN Sports 1", ChannelTitle.pretty("beIN Sports 1 HD"))
        assertEquals("Channel 4", ChannelTitle.pretty("Channel 4"))
        assertEquals("Sky News", ChannelTitle.pretty("sky news"))
    }

    @Test fun numberOnlyFallsBack() {
        assertFalse(ChannelTitle.pretty("UK: HD").isBlank())
    }

    @Test fun localStationsKeepCallsignAndCity() {
        assertEquals("WABC New York", ChannelTitle.pretty("US: ABC 7 WABC NEW YORK HD", local = true))
        assertEquals("KTLA Los Angeles", ChannelTitle.pretty("USA | KTLA-TV (CW) LOS ANGELES, CA", local = true))
        assertEquals("WNBC New York", ChannelTitle.pretty("NBC 4 New York WNBC", local = true))
        assertEquals("WGN", ChannelTitle.pretty("US LOCAL: WGN", local = true))
    }

    @Test fun localOnlyWhenAsked() {
        assertEquals("WWE Network", ChannelTitle.pretty("WWE Network"))
    }

    @Test fun usLocalIsNews() {
        assertTrue(LiveKind.isLocalNews("US Local"))
        assertTrue(LiveKind.isLocalNews("USA | LOCALS"))
        assertTrue(LiveKind.isLocalNews("Local News"))
        assertFalse(LiveKind.isLocalNews("UK | Entertainment"))
        assertEquals("Local News", LiveKind.sectionLabel("US Local"))
        assertEquals(LiveKind.NEWS, LiveKind.type("US Local", "ABC 7 WABC New York"))
        assertEquals("Entertainment", LiveKind.sectionLabel("UK | ENTERTAINMENT"))
        assertEquals("News", LiveKind.sectionLabel("USA | NEWS"))
    }
}
