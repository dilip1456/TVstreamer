package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveGroupsTest {
    private fun ch(id: String, name: String, cat: String = "1") = Item("itv", id, name, "", cat, id.toInt(), false)
    private val a = ch("1", "UK: BBC News"); private val b = ch("2", "CNN"); private val c = ch("3", "Star Sports", "2")
    private val d = ch("4", "Aaj Tak", "3"); private val e = ch("5", "9XM", "2")
    private val info = mapOf(
        a.key to ChInfo(LiveKind.NEWS, "English"), b.key to ChInfo(LiveKind.NEWS, "English"), c.key to ChInfo(LiveKind.SPORTS, "Hindi"),
        d.key to ChInfo(LiveKind.NEWS, "Hindi"), e.key to ChInfo(LiveKind.MUSIC, "Hindi"))
    private val cats = mapOf("1" to "|UK| NEWS", "2" to "IN | Sports", "3" to "Hindi")
    private val pool = listOf(a, b, c, d, e)

    @Test fun typeCountsInDisplayOrder() {
        assertEquals(listOf(LiveKind.NEWS to 3, LiveKind.SPORTS to 1, LiveKind.MUSIC to 1), LiveGroups.typeCounts(pool, info))
        assertEquals(listOf(a, b, d), LiveGroups.ofType(pool, info, LiveKind.NEWS))
    }

    @Test fun countryFromNameThenCategory() {
        assertEquals("UK", LiveGroups.countryOf("UK: BBC News", ""))
        assertEquals("UK", LiveGroups.countryOf("CNN", "|UK| NEWS"))
        assertEquals("IN", LiveGroups.countryOf("Star Sports", "IN | Sports"))
        assertEquals("", LiveGroups.countryOf("Aaj Tak", "Hindi"))
        assertEquals(listOf("UK" to 2, "IN" to 2).sortedBy { it.first }, LiveGroups.countryCounts(pool, cats).sortedBy { it.first })
    }

    @Test fun filtersCombine() {
        assertEquals(listOf(c, d, e), LiveGroups.filter(pool, info, cats, "Hindi", ""))
        assertEquals(listOf(c, e), LiveGroups.filter(pool, info, cats, "Hindi", "IN"))
        assertEquals(pool, LiveGroups.filter(pool, info, cats, "", ""))
    }

    @Test fun sectionsByLanguageOrLetter() {
        val news = listOf(a, b, d)
        val byLang = LiveGroups.sections(news, info, sortAz = false, byLanguage = true)
        assertEquals(listOf("English", "Hindi"), byLang.map { it.title })
        assertEquals(listOf(a, b), byLang[0].items)
        val az = LiveGroups.sections(pool, info, sortAz = true, byLanguage = true)
        assertEquals(listOf("A", "B", "C", "S", "#"), az.map { it.title })   // "UK: BBC News" files under B
        assertEquals(listOf(LiveSection("", news)), LiveGroups.sections(news, info, sortAz = false, byLanguage = false))
        assertEquals(listOf(listOf(a, b), listOf(d)), LiveGroups.rows(news, 2))
    }
}
