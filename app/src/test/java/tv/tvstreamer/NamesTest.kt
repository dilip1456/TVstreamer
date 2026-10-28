package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Test

class NamesTest {
    @Test fun prefixes() {
        assertEquals("Action Movies", Names.title("EN| Action Movies"))
        assertEquals("Action Movies", Names.title("|EN| Action Movies"))
        assertEquals("Avatar", Names.title("4K-Avatar"))
        assertEquals("Dune: Part Two (2024)", Names.title("NF - Dune: Part Two (2024) FHD"))
        assertEquals("The Matrix (1999)", Names.title("EN - The Matrix (1999) [4K]"))
        assertEquals("Breaking Bad", Names.title("[EN] Breaking Bad"))
        assertEquals("Hindi Movies", Names.title("IN: HINDI MOVIES"))
        assertEquals("Netflix Series", Names.title("| NETFLIX SERIES"))
    }
    @Test fun keepsRealTitles() {
        assertEquals("X-Men", Names.title("X-Men"))
        assertEquals("Spider-Man: No Way Home", Names.title("Spider-Man: No Way Home"))
        assertEquals("ER", Names.title("ER"))
        assertEquals("USA", Names.title("USA"))
        assertEquals("Up", Names.title("Up"))
        assertEquals("The Office", Names.title("The Office"))
    }
    @Test fun categoryTag() {
        assertEquals("Action · EN", Names.cat("EN| ACTION"))
        assertEquals("Kids", Names.cat("4K- KIDS"))
    }
}
