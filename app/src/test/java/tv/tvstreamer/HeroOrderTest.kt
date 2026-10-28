package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroOrderTest {
    private val id = { s: String -> s.substringBefore('#') }

    @Test fun lastPlayedLeadsThenHistoryThenFavourites() {
        assertEquals(listOf("c", "b", "a", "x", "y"), HeroOrder.build(listOf("c", "b", "a"), listOf("x", "y"), id))
    }

    @Test fun eachTitleOnce() {
        // "b" is both played and a favourite; two episodes of one show ("s#1", "s#2") count once
        assertEquals(listOf("s#2", "b", "x"), HeroOrder.build(listOf("s#2", "b", "s#1"), listOf("b", "x", "s#9"), id))
    }

    @Test fun favouritesAloneAndNothing() {
        assertEquals(listOf("x", "y"), HeroOrder.build(emptyList(), listOf("x", "y"), id))
        assertEquals(emptyList<String>(), HeroOrder.build(emptyList<String>(), emptyList(), id))
    }

    @Test fun capped() {
        assertEquals(3, HeroOrder.build((1..10).map { "h$it" }, (1..10).map { "f$it" }, id, max = 3).size)
        assertEquals(listOf("h1", "h2", "h3"), HeroOrder.build((1..10).map { "h$it" }, listOf("f"), id, max = 3))
    }

    @Test fun stepsWrap() {
        assertEquals(1, HeroOrder.next(0, 3)); assertEquals(0, HeroOrder.next(2, 3)); assertEquals(0, HeroOrder.next(5, 0))
    }

    @Test fun pausesAfterInteraction() {
        assertFalse(HeroOrder.shouldRotate(1, 100_000, 0))                       // one slide: nothing to rotate
        assertTrue(HeroOrder.shouldRotate(3, 100_000, 0))
        assertFalse(HeroOrder.shouldRotate(3, 100_000, 100_000 - 2_000))         // key pressed 2 s ago
        assertTrue(HeroOrder.shouldRotate(3, 100_000, 100_000 - HeroOrder.PAUSE_MS))
    }
}
