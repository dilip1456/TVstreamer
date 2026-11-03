package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelTitleTest {
    @Test fun stripsNumberingAndDecoration() {
        assertEquals("BBC One", ChannelTitle.clean("123 | BBC One"))
        assertEquals("CNN", ChannelTitle.clean("12. CNN"))
        assertEquals("Fox", ChannelTitle.clean("#5 - Fox"))
        assertEquals("BBC One", ChannelTitle.clean("101 BBC One", 101))
        assertEquals("UK SPORTS", ChannelTitle.clean("##### UK SPORTS #####"))
        assertEquals("Star Plus", ChannelTitle.clean("★ Star Plus ★"))
    }

    @Test fun keepsNamesThatStartWithANumber() {
        assertEquals("24 Kitchen", ChannelTitle.clean("24 Kitchen", 7))
        assertEquals("9XM", ChannelTitle.clean("9XM"))
        assertEquals("3sat", ChannelTitle.clean("3sat"))
        assertEquals("100% Radio", ChannelTitle.clean("100% Radio"))
    }

    @Test fun countryAndQualityTagsStayReadable() {
        assertEquals("UK: BBC One", ChannelTitle.clean("|UK| BBC One"))
        assertEquals("UK: BBC One", ChannelTitle.clean("[UK] BBC One"))
        assertEquals("UK: BBC One", ChannelTitle.clean("UK | BBC One"))
        assertEquals("UK: BBC One HD", ChannelTitle.clean("UK: BBC Oneᴴᴰ"))
        assertEquals("IN: Star Gold FHD", ChannelTitle.clean("IN | Star Gold ᶠᴴᴰ"))
        assertEquals("UK: Sky News", ChannelTitle.clean("12 | UK: Sky News"))
    }

    @Test fun picksTheFirstRealNameOfTheFields() {
        assertEquals("BBC One", ChannelTitle.pick("1001", "BBC One", "bbcone.uk"))
        assertEquals("UK: BBC One", ChannelTitle.pick("1001", "", "BBCOne.uk"))
        assertEquals("Sky News", ChannelTitle.pick("55", "", "", "ffmpeg http://h:80/live/Sky_News.m3u8"))
        assertEquals("", ChannelTitle.pick("55", "55", "", "ffmpeg http://h/play/live.php?mac=00&stream=12345&extension=ts"))
        assertEquals("", ChannelTitle.pick("77", "", "", "http://h/u/p/77"))
        assertEquals("", ChannelTitle.pick("12", "", "", "http://h/live/a1b2c3d4e5f6a7b8c9d0.ts"))
    }

    @Test fun numberOnlyFallsBackToChannelNumberAndGroup() {
        assertEquals("Channel 123 · Sports", ChannelTitle.display("123", 123, "Sports"))
        assertEquals("Channel 45 · UK: News", ChannelTitle.display("#45", 0, "|UK| News"))
        assertEquals("Channel", ChannelTitle.display("", 0, ""))
        assertEquals("BBC One", ChannelTitle.display("7 | BBC One", 7, "UK"))
        // Already-clean names are left alone (applied again to cached channels)
        assertEquals("UK: BBC One HD", ChannelTitle.display(ChannelTitle.display("|UK| BBC Oneᴴᴰ", 3, ""), 3, ""))
    }

    @Test fun initialsSkipTheCountryTag() {
        assertEquals("BO", ChannelTitle.initials("UK: BBC One"))
        assertEquals("CN", ChannelTitle.initials("CNN"))
        assertEquals("C1", ChannelTitle.initials("Channel 123 · Sports").let { if (it == "C1") it else it })
        assertTrue(ChannelTitle.numeric("12 - 3"))
    }
}
