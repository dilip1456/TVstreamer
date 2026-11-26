package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFeedbackTest {
    private val empty = AiFeedbackState()

    private fun item(id: String, name: String, year: String = "1999", series: Boolean = false) =
        Item("vod", id, name, "", "1", if (series) -1 else 0, false, year = year)

    @Test fun toggleLikeAndDislikeAreExclusiveAndClearOnSecondTap() {
        val matrix = "vod:1" to "The Matrix (1999)"
        var s = AiFeedback.toggle(empty, matrix.first, matrix.second, AiVote.LIKED)
        assertEquals(AiVote.LIKED, AiFeedback.voteOf(s, matrix.first, matrix.second))
        assertEquals(listOf("The Matrix (1999)"), AiFeedback.likedTitles(s))
        assertTrue(AiFeedback.dislikedTitles(s).isEmpty())

        s = AiFeedback.toggle(s, matrix.first, matrix.second, AiVote.DISLIKED)
        assertEquals(AiVote.DISLIKED, AiFeedback.voteOf(s, matrix.first, matrix.second))
        assertTrue(AiFeedback.likedTitles(s).isEmpty())
        assertEquals(listOf("The Matrix (1999)"), AiFeedback.dislikedTitles(s))

        s = AiFeedback.toggle(s, matrix.first, matrix.second, AiVote.DISLIKED)
        assertEquals(AiVote.NONE, AiFeedback.voteOf(s, matrix.first, matrix.second))
        assertTrue(s.liked.isEmpty() && s.disliked.isEmpty())
    }

    @Test fun jsonRoundTripAndIgnoresBadEntries() {
        val s = AiFeedbackState(
            liked = listOf(AiFeedbackEntry("vod:1", "The Matrix (1999)")),
            disliked = listOf(AiFeedbackEntry("vod:2", "Foo (2020)"))
        )
        val back = AiFeedback.fromJson(AiFeedback.toJson(s))
        assertEquals(s, back)
        assertEquals(AiFeedbackState(), AiFeedback.fromJson(null))
        assertEquals(AiFeedbackState(), AiFeedback.fromJson("not json"))
        val messy = AiFeedback.fromJson("""{"liked":[{"key":"vod:1","title":"A"},{"key":"","title":"x"},{"key":"vod:1","title":"dup"}],"disliked":[null]}""")
        assertEquals(listOf(AiFeedbackEntry("vod:1", "A")), messy.liked)
        assertTrue(messy.disliked.isEmpty())
    }

    @Test fun hideDislikedMatchesKeyOrNormalizedTitle() {
        val a = item("1", "The Matrix (1999)", "1999")
        val otherVersion = item("99", "The Matrix", "1999")
        val b = item("2", "Inception (2010)", "2010")
        val s = AiFeedback.toggle(empty, a.key, "The Matrix (1999)", AiVote.DISLIKED)
        assertTrue(AiFeedback.isDisliked(s, a))
        assertTrue(AiFeedback.isDisliked(s, otherVersion))   // same title, different id
        assertFalse(AiFeedback.isDisliked(s, b))
        assertEquals(listOf(b), AiFeedback.visible(listOf(a, otherVersion, b), s))
    }

    @Test fun menuLabelsShowCheckForCurrentVote() {
        assertEquals("Like", AiFeedback.label(AiVote.LIKED, AiVote.NONE))
        assertEquals("Dislike", AiFeedback.label(AiVote.DISLIKED, AiVote.NONE))
        assertEquals("✓  Like", AiFeedback.label(AiVote.LIKED, AiVote.LIKED))
        assertEquals("✓  Dislike", AiFeedback.label(AiVote.DISLIKED, AiVote.DISLIKED))
        val items = AiFeedback.menuItems(AiVote.LIKED)
        assertEquals(listOf("✓  Like", "Dislike"), items.map { it.first })
        assertEquals(listOf(AiVote.LIKED, AiVote.DISLIKED), items.map { it.second })
    }

    @Test fun titleOfUsesCleanNameAndYear() {
        val i = item("1", "The Matrix 1999 1080p", "1999")
        val t = AiFeedback.titleOf(i)
        assertTrue(t.contains("Matrix"))
        assertTrue(t.contains("1999"))
    }
}
