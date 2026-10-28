package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatcherTest {
    private fun c(id: Int, t: String, y: String, pop: Double, orig: String = t) = Cand(id, "movie", t, orig, y, pop)

    @Test fun exactTitleAndYearWins() {
        val r = listOf(c(1, "Don", "1978", 20.0), c(2, "Don", "2006", 15.0), c(3, "Don 2", "2011", 30.0))
        assertEquals(2, Matcher.pick(r, "Don", "2006")?.id)
        assertEquals(1, Matcher.pick(r, "Don", "1978")?.id)
    }

    @Test fun popularityBreaksTiesWithoutYear() {
        val r = listOf(c(1, "Dune", "1984", 30.0), c(2, "Dune", "2021", 150.0))
        assertEquals(2, Matcher.pick(r, "Dune", "")?.id)
    }

    @Test fun spellingVariantsMatch() {
        val r = listOf(c(7, "Pathaan", "2023", 40.0))
        assertEquals(7, Matcher.pick(r, "Pathan", "2023")?.id)
    }

    @Test fun originalTitleCounts() {
        val r = listOf(c(5, "Parasite", "2019", 80.0, orig = "Gisaengchung"))
        assertEquals(5, Matcher.pick(r, "Gisaengchung", "2019")?.id)
    }

    @Test fun unrelatedResultsRejected() {
        val r = listOf(c(1, "The Notebook", "2004", 50.0), c(2, "Frozen", "2013", 90.0))
        assertNull(Matcher.pick(r, "Kantara", "2022"))
        assertNull(Matcher.pick(emptyList(), "Kantara", "2022"))
    }

    @Test fun wrongYearPenalised() {
        val r = listOf(c(1, "Kick", "2009", 60.0), c(2, "Kick", "2014", 10.0))
        assertEquals(2, Matcher.pick(r, "Kick", "2014")?.id)
    }
}
