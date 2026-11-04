package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class PopupStackTest {
    @Test fun closingTopReturnsOpener() {
        val s = PopupStack<String>()
        val a = s.open("row5")
        s.focused("menuItem1")
        assertTrue(s.isTop(a))
        assertEquals("row5", s.close(a))
        assertTrue(s.isEmpty)
    }

    @Test fun nestedPopupClosesOnlyTop() {
        val s = PopupStack<String>()
        val menu = s.open("row5"); s.focused("rename")
        val dlg = s.open("rename")
        assertTrue(s.isTop(dlg)); assertFalse(s.isTop(menu))
        assertEquals("rename", s.close(dlg))     // back into the menu, on the entry that opened the dialog
        assertTrue(s.isTop(menu))
        assertEquals("row5", s.close(menu))
    }

    @Test fun replacedPopupHandsOnItsOpener() {
        val s = PopupStack<String>()
        val menu = s.open("row5"); s.focused("rename")
        val dlg = s.open("rename")               // the menu page is replaced by the dialog
        assertNull(s.close(menu))                // not the top one: no focus change
        assertEquals("row5", s.close(dlg))       // focus lands on the row that opened the menu
    }

    @Test fun unknownCloseIsIgnored() {
        val s = PopupStack<String>()
        assertNull(s.close(42))
    }
}
