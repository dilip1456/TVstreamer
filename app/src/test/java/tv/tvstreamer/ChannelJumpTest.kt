package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class ChannelJumpTest {
    private fun ch(id: String, num: Int) = Item("itv", id, "Ch $id", "", "1", num, false)
    private fun row(id: String, num: Int) = LiveModes.Row(ch(id, num), true, LiveKind.NEWS)

    private val current = listOf(row("a", 101), row("b", 102), row("c", 201))
    private val all = current + listOf(row("d", 305), row("e", 1020))

    @Test fun appendsDigitsAndCapsLength() {
        assertEquals("1", ChannelJump.append("", 1))
        assertEquals("12", ChannelJump.append("1", 2))
        assertEquals("0", ChannelJump.append("", 0))
        assertEquals("10", ChannelJump.append("1", 0))
        assertEquals("12345", ChannelJump.append("1234", 5))
        assertEquals("23456", ChannelJump.append("12345", 6))
    }

    @Test fun exactMatchInTheCurrentListStaysThere() {
        val hit = ChannelJump.find("102", current, all)!!
        assertEquals("itv:b", hit.key)
        assertFalse(hit.switchToAll)
    }

    @Test fun exactMatchOutsideTheCurrentListSwitchesToAll() {
        val hit = ChannelJump.find("305", current, all)!!
        assertEquals("itv:d", hit.key)
        assertTrue(hit.switchToAll)
    }

    @Test fun prefixMatchWhenTheExactNumberIsMissing() {
        val hit = ChannelJump.find("10", current, all)!!
        assertEquals("itv:a", hit.key)
        assertFalse(hit.switchToAll)
    }

    @Test fun unknownNumberReturnsNull() {
        assertNull(ChannelJump.find("999", current, all))
        assertNull(ChannelJump.find("", current, all))
    }

    @Test fun digitCodesForNumberRowAndNumPad() {
        assertEquals(0, ChannelJump.digitOfCode(7, "0"))
        assertEquals(9, ChannelJump.digitOfCode(16, "9"))
        assertEquals(3, ChannelJump.digitOfCode(147, "NumPad3"))
        assertNull(ChannelJump.digitOfCode(21, "DirectionDown"))
    }
}
