package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test
import tv.tvstreamer.LiveKind.SPORTS
import tv.tvstreamer.LiveKind.NEWS
import tv.tvstreamer.LiveKind.MOVIES
import tv.tvstreamer.LiveKind.ENT
import tv.tvstreamer.LiveKind.KIDS
import tv.tvstreamer.LiveKind.MUSIC
import tv.tvstreamer.LiveKind.DOCS
import tv.tvstreamer.LiveKind.RELIGIOUS

class TaxonomyTest {
    private fun sub(type: String, cat: String, name: String) = Taxonomy.sub(type, cat, name)

    @Test fun sports() {
        assertEquals("Cricket", sub(SPORTS, "UK | SPORTS", "Sky Sports Cricket HD"))
        assertEquals("Cricket", sub(SPORTS, "PK: SPORTS", "PTV Sports"))
        assertEquals("Cricket", sub(SPORTS, "USA SPORTS", "Willow HD"))
        assertEquals("Cricket", sub(SPORTS, "AU: SPORTS", "Fox Cricket"))
        assertEquals("Tennis", sub(SPORTS, "USA SPORTS", "Tennis Channel"))
        assertEquals("American Football", sub(SPORTS, "USA SPORTS", "NFL RedZone"))
        assertEquals("American Football", sub(SPORTS, "USA | NFL", "Game Pass 1"))
        assertEquals("Football", sub(SPORTS, "UK | SPORTS", "Sky Sports Premier League"))
        assertEquals("Football", sub(SPORTS, "ARABIC SPORTS", "beIN Sports 1"))
        assertEquals("Basketball", sub(SPORTS, "USA SPORTS", "NBA TV"))
        assertEquals("Baseball", sub(SPORTS, "USA SPORTS", "MLB Network"))
        assertEquals("Hockey", sub(SPORTS, "CA SPORTS", "NHL Network"))
        assertEquals("Golf", sub(SPORTS, "UK | SPORTS", "Sky Sports Golf"))
        assertEquals("Motorsport", sub(SPORTS, "UK | SPORTS", "Sky Sports F1"))
        assertEquals("Combat", sub(SPORTS, "USA SPORTS", "UFC Fight Pass"))
        assertEquals("Combat", sub(SPORTS, "USA SPORTS", "WWE Network"))
        assertEquals("Rugby", sub(SPORTS, "NZ: SPORTS", "Sky Sport Rugby"))
        assertEquals("Horse Racing", sub(SPORTS, "UK | SPORTS", "Racing TV"))
        assertEquals(Taxonomy.OTHER, sub(SPORTS, "USA SPORTS", "ESPN"))
    }

    @Test fun news() {
        assertEquals("Local News", sub(NEWS, "US LOCALS", "WABC New York"))
        assertEquals("Business", sub(NEWS, "USA NEWS", "CNBC"))
        assertEquals("Business", sub(NEWS, "USA NEWS", "Bloomberg TV"))
        assertEquals("International", sub(NEWS, "UK | NEWS", "BBC World News"))
        assertEquals("International", sub(NEWS, "NEWS", "Al Jazeera English"))
        assertEquals("National", sub(NEWS, "INDIA NEWS", "Aaj Tak"))
        assertEquals("Weather", sub(NEWS, "USA NEWS", "The Weather Channel"))
    }

    @Test fun movies() {
        assertEquals("Premium", sub(MOVIES, "USA MOVIES", "HBO Signature"))
        assertEquals("Action", sub(MOVIES, "INDIA MOVIES", "Zee Action"))
        assertEquals("Classic", sub(MOVIES, "USA MOVIES", "TCM"))
        assertEquals("Horror", sub(MOVIES, "USA MOVIES", "Screambox Horror"))
        assertEquals("Family", sub(MOVIES, "USA MOVIES", "Hallmark Movies & Mysteries"))
        assertEquals(Taxonomy.OTHER, sub(MOVIES, "INDIA MOVIES", "Sony Max"))
    }

    @Test fun others() {
        assertEquals("Preschool", sub(KIDS, "KIDS", "Nick Jr"))
        assertEquals("Preschool", sub(KIDS, "UK KIDS", "CBeebies"))
        assertEquals("Cartoons", sub(KIDS, "KIDS", "Cartoon Network"))
        assertEquals("Education", sub(KIDS, "KIDS", "Discovery Kids"))
        assertEquals("Teens & Family", sub(KIDS, "KIDS", "Disney Channel"))
        assertEquals("Pop & Hits", sub(MUSIC, "UK | MUSIC", "MTV Hits"))
        assertEquals("Film & Folk songs", sub(MUSIC, "INDIA MUSIC", "9XM"))
        assertEquals("Country", sub(MUSIC, "USA MUSIC", "CMT"))
        assertEquals("Classical & Jazz", sub(MUSIC, "MUSIC", "Mezzo Live"))
        assertEquals("Nature & Wildlife", sub(DOCS, "DOCUMENTARY", "Nat Geo Wild"))
        assertEquals("History", sub(DOCS, "DOCUMENTARY", "History Channel"))
        assertEquals("Cooking & Food", sub(DOCS, "LIFESTYLE", "Food Network"))
        assertEquals("Travel", sub(DOCS, "LIFESTYLE", "Travelxp HD"))
        assertEquals("Crime & Investigation", sub(DOCS, "USA", "Investigation Discovery"))
        assertEquals("Science & Tech", sub(DOCS, "DOCUMENTARY", "Discovery Science"))
        assertEquals("Christian", sub(RELIGIOUS, "RELIGIOUS", "EWTN"))
        assertEquals("Islamic", sub(RELIGIOUS, "RELIGIOUS", "Madani Channel"))
        assertEquals("Hindu", sub(RELIGIOUS, "RELIGIOUS", "Aastha TV"))
        assertEquals("Sikh", sub(RELIGIOUS, "RELIGIOUS", "PTC Simran"))
        assertEquals("Shopping", sub(ENT, "USA ENTERTAINMENT", "QVC"))
        assertEquals("Networks", sub(ENT, "USA ENTERTAINMENT", "NBC East"))
        assertEquals("Reality", sub(ENT, "USA ENTERTAINMENT", "Bravo"))
        assertEquals(Taxonomy.OTHER, sub(ENT, "ENTERTAINMENT", "Xyzzy TV One"))
    }

    @Test fun regionsForSports() {
        assertEquals("Australia", Taxonomy.region("AU: SPORTS", "Fox Cricket"))
        assertEquals("New Zealand", Taxonomy.region("NZ | SPORTS", "Sky Sport 1"))
        assertEquals("New Zealand", Taxonomy.region("SPORTS", "Sky Sport NZ 2"))
        assertEquals("Caribbean", Taxonomy.region("CARIBBEAN SPORTS", "SportsMax"))
        assertEquals("Caribbean", Taxonomy.region("SPORTS", "Flow Sports 1"))
        assertEquals("UK & Ireland", Taxonomy.region("UK | SPORTS", "Sky Sports Main Event"))
        assertEquals("USA", Taxonomy.region("USA SPORTS", "ESPN 2"))
        assertEquals("Canada", Taxonomy.region("SPORTS", "TSN 1"))
        assertEquals("India", Taxonomy.region("IN: SPORTS", "Star Sports 1 Hindi"))
        assertEquals("Pakistan", Taxonomy.region("SPORTS", "PTV Sports"))
        // "in" / "us" as plain words are not region codes
        assertEquals("", Taxonomy.region("SPORTS", "Live in HD"))
    }

    @Test fun hiddenGroups() {
        val h = setOf(Taxonomy.subKey(SPORTS, "Cricket"), Taxonomy.regionKey(SPORTS, "New Zealand"), Taxonomy.typeKey(KIDS))
        assertTrue(Taxonomy.groupHidden(SPORTS, "Cricket", "", h))
        assertTrue(Taxonomy.groupHidden(SPORTS, "Rugby", "New Zealand", h))
        assertFalse(Taxonomy.groupHidden(SPORTS, "Rugby", "Australia", h))
        assertTrue(Taxonomy.groupHidden(KIDS, "Cartoons", "", h))
        assertEquals("Sports › Cricket", Taxonomy.label(Taxonomy.subKey(SPORTS, "Cricket")))
        assertEquals("Sports › New Zealand", Taxonomy.label(Taxonomy.regionKey(SPORTS, "New Zealand")))
    }
}
