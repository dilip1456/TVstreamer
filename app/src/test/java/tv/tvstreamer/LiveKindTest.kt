package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.tvstreamer.LiveKind.DOCS
import tv.tvstreamer.LiveKind.ENT
import tv.tvstreamer.LiveKind.KIDS
import tv.tvstreamer.LiveKind.MOVIES
import tv.tvstreamer.LiveKind.MUSIC
import tv.tvstreamer.LiveKind.NEWS
import tv.tvstreamer.LiveKind.OTHER
import tv.tvstreamer.LiveKind.REGIONAL
import tv.tvstreamer.LiveKind.RELIGIOUS
import tv.tvstreamer.LiveKind.SPORTS

class LiveKindTest {
    @Test fun categoryNamesTheType() {
        assertEquals(SPORTS, LiveKind.type("UK | SPORTS", "Channel 1"))
        assertEquals(NEWS, LiveKind.type("HINDI NEWS", "Some Channel"))
        assertEquals(KIDS, LiveKind.type("EN| KIDS", "Something"))
        assertEquals(RELIGIOUS, LiveKind.type("INDIA DEVOTIONAL", "Channel"))
        assertEquals(MUSIC, LiveKind.type("Music", "Box"))
    }

    @Test fun channelNameWhenCategoryIsGeneric() {
        assertEquals(SPORTS, LiveKind.type("USA", "ESPN 2 HD"))
        assertEquals(NEWS, LiveKind.type("INDIA ENTERTAINMENT", "Aaj Tak"))
        assertEquals(MOVIES, LiveKind.type("INDIA", "Sony Max HD"))
        assertEquals(SPORTS, LiveKind.type("INDIA", "Sony Ten 1"))
        assertEquals(ENT, LiveKind.type("INDIA", "Sony SAB"))
        assertEquals(MOVIES, LiveKind.type("HINDI", "Zee Cinema"))
        assertEquals(MOVIES, LiveKind.type("HINDI", "&Pictures HD"))
        assertEquals(ENT, LiveKind.type("HINDI", "&TV"))
        assertEquals(NEWS, LiveKind.type("USA", "Fox News"))
        assertEquals(SPORTS, LiveKind.type("USA", "FOX SPORTS 1"))
        assertEquals(ENT, LiveKind.type("USA", "FOX 5 New York"))
        assertEquals(DOCS, LiveKind.type("UK", "National Geographic"))
        assertEquals(KIDS, LiveKind.type("USA", "Cartoon Network"))
        assertEquals(MUSIC, LiveKind.type("INDIA", "9XM"))
        assertEquals(ENT, LiveKind.type("ENTERTAINMENT", "Random Name"))
    }

    @Test fun fallbacks() {
        assertEquals(REGIONAL, LiveKind.type("TAMIL", "Polimer TV"))
        assertEquals(REGIONAL, LiveKind.type("MEXICO", "Canal 5"))
        assertEquals(OTHER, LiveKind.type("VIP", "XYZ 1"))
    }

    @Test fun languages() {
        assertEquals("Hindi", LiveKind.language("HINDI NEWS", "Aaj Tak"))
        assertEquals("Tamil", LiveKind.language("INDIA", "Zee Tamil HD"))
        assertEquals("Bengali", LiveKind.language("INDIA", "Colors Bangla"))
        assertEquals("Hindi", LiveKind.language("INDIA", "Star Plus"))
        assertEquals("English", LiveKind.language("UK | SPORTS", "Sky Sports Main Event"))
        assertEquals("English", LiveKind.language("USA", "ESPN"))
        assertEquals("Spanish", LiveKind.language("MEXICO", "Canal 5"))
        assertEquals("Arabic", LiveKind.language("AR| beIN", "beIN Sports 1"))
        assertEquals("Urdu", LiveKind.language("PAKISTAN", "Geo News"))
        assertEquals("", LiveKind.language("VIP", "XYZ 1"))
        // channel names: lowercase codes are not languages
        assertEquals("", LiveKind.language("SPORTS", "IT Channel"))
    }
}
