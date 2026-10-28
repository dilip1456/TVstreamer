package tv.tvstreamer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveIndexTest {
    private fun ch(id: String, name: String, cat: String) = Item("itv", id, name, "cmd", cat, 1, false)

    private val cats = listOf(Cat("1", "UK | SPORTS", false), Cat("2", "INDIA", false), Cat("3", "HINDI NEWS", false),
        Cat("4", "USA", false), Cat("5", "TAMIL", false), Cat("6", "Misc", false))
    private val titles = cats.associate { it.id to it.title }
    private val names = listOf("Sky Sports 1", "Sony Max HD", "Aaj Tak", "ESPN 2 HD", "Sun TV", "Channel 7", "Zee Cinema", "CNN",
        "Star Plus", "Cartoon Network", "MTV", "BBC One", "Al Jazeera", "Sony Ten 1", "Sony SAB", "Colors Tamil")
    private val chans = names.flatMapIndexed { i, n -> cats.map { c -> ch("$i-${c.id}", n, c.id) } }

    /** The rules as they were (category and channel looked at separately for every channel). */
    private fun reference(c: Item): ChInfo {
        val t = titles[c.cat] ?: ""
        val ct = LiveKind.typeOf(t)
        val lang = LiveKind.language(t, c.name)
        val type = when {
            ct.isNotEmpty() && ct != LiveKind.ENT && ct != LiveKind.REGIONAL -> ct
            LiveKind.typeOf(c.name).isNotEmpty() -> LiveKind.typeOf(c.name)
            ct.isNotEmpty() -> ct
            lang.isNotEmpty() -> LiveKind.REGIONAL
            else -> LiveKind.OTHER
        }
        return ChInfo(type, lang.ifEmpty { Tags.OTHER })
    }

    @Test fun sameAnswersAsChannelByChannel() = runBlocking {
        val m = LiveIndex.build(chans, titles)
        assertEquals(chans.size, m.size)
        for (c in chans) {
            assertEquals(c.name + " in " + titles[c.cat], reference(c), m[c.key])
            assertEquals(LiveKind.type(titles[c.cat] ?: "", c.name), m[c.key]!!.type)
        }
    }

    @Test fun progressArrivesInChunks() = runBlocking {
        val seen = mutableListOf<Int>()
        val m = LiveIndex.build(chans, titles, chunk = 10) { partial, n -> assertEquals(n, partial.size); seen += n }
        assertTrue(seen.size >= (chans.size - 1) / 10)
        assertEquals(seen.sorted(), seen)
        assertEquals(chans.size, m.size)
    }

    @Test fun groupsOnlySortedChannelsInPortalOrder() {
        val info = mapOf(chans[0].key to ChInfo(LiveKind.SPORTS, "English"), chans[2].key to ChInfo(LiveKind.SPORTS, "English"),
            chans[1].key to ChInfo(LiveKind.NEWS, "Hindi"))
        val g = LiveIndex.groupByType(chans, info)
        assertEquals(listOf(chans[0], chans[2]), g[LiveKind.SPORTS])
        assertEquals(listOf(chans[1]), g[LiveKind.NEWS])
        assertEquals(3, g.values.sumOf { it.size })   // not-yet-sorted channels are left out, not dumped in Other
    }

    @Test fun keyFollowsTheList() {
        val k = LiveIndex.keyOf(chans, cats)
        assertEquals(k, LiveIndex.keyOf(chans.map { it.copy() }, cats))
        assertNotEquals(k, LiveIndex.keyOf(chans.dropLast(1), cats))
        assertNotEquals(k, LiveIndex.keyOf(chans.mapIndexed { i, c -> if (i == 3) c.copy(name = "Other name") else c }, cats))
        assertNotEquals(k, LiveIndex.keyOf(chans, cats.map { if (it.id == "2") it.copy(title = "INDIA HD") else it }))
    }

    @Test fun manyChannelsAreQuick() = runBlocking {
        val big = (0 until 20_000).map { i -> ch("b$i", names[i % names.size] + " $i", cats[i % cats.size].id) }
        val t0 = System.nanoTime()
        val m = LiveIndex.build(big, titles)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(big.size, m.size)
        println("LiveIndex: 20000 channels in $ms ms")
        assertTrue("took $ms ms", ms < 20_000)
    }
}
