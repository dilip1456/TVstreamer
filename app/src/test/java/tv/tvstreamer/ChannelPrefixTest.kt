package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPrefixTest {
    @Test fun stripsBareIsoAndSeparatedCodes() {
        assertEquals("BNT 1", ChannelTitle.pretty("AF BNT 1"))
        assertEquals("BNT 1", ChannelTitle.pretty("AF: BNT 1"))
        assertEquals("Nova", ChannelTitle.pretty("BG Nova"))
        assertEquals("Nova", ChannelTitle.pretty("BG | Nova"))
        assertEquals("Canal 5", ChannelTitle.pretty("AR - Canal 5"))
        assertEquals("Sky Sports Main Event", ChannelTitle.pretty("[UK] SKY SPORTS MAIN EVENT 4K"))
        assertEquals("CNN", ChannelTitle.pretty("|US| CNN HD"))
        assertEquals("BBC One", ChannelTitle.pretty("EN: BBC One"))
        assertEquals("ESPN", ChannelTitle.pretty("US- ESPN"))
        assertEquals("NBC", ChannelTitle.pretty("AF/BG: NBC"))
        assertEquals("RTP", ChannelTitle.pretty("PT_PT | RTP"))
        assertEquals("Star Plus", ChannelTitle.pretty("IN Star Plus"))
        assertEquals("Geo News", ChannelTitle.pretty("PK Geo News"))
        assertEquals("Al Jazeera", ChannelTitle.pretty("AR Al Jazeera"))
        assertEquals("TF1", ChannelTitle.pretty("FR: TF1"))
        assertEquals("Das Erste", ChannelTitle.pretty("DE Das Erste"))
        assertEquals("Rai 1", ChannelTitle.pretty("IT Rai 1"))
        assertEquals("TVE", ChannelTitle.pretty("ES | TVE"))
        assertEquals("CBC", ChannelTitle.pretty("CA CBC"))
        assertEquals("ABC", ChannelTitle.pretty("AU: ABC"))
        assertEquals("RTE", ChannelTitle.pretty("IE RTE"))
        assertEquals("TRT", ChannelTitle.pretty("TR TRT"))
    }

    @Test fun keepsRealNetworkNames() {
        assertEquals("BBC One", ChannelTitle.pretty("BBC One"))
        assertEquals("AMC", ChannelTitle.pretty("AMC"))
        assertEquals("ABC News", ChannelTitle.pretty("ABC News"))
        assertEquals("BET", ChannelTitle.pretty("BET"))
        assertEquals("TNT Sports", ChannelTitle.pretty("TNT Sports"))
        assertEquals("ESPN", ChannelTitle.pretty("ESPN"))
        assertEquals("CNN International", ChannelTitle.pretty("CNN International"))
        assertEquals("HBO", ChannelTitle.pretty("HBO"))
        assertEquals("A&E", ChannelTitle.pretty("A&E"))
        assertEquals("USA Network", ChannelTitle.pretty("USA Network"))
        assertEquals("TV One", ChannelTitle.pretty("TV One"))
        assertEquals("BBC News", ChannelTitle.pretty("BBC News"))
    }

    @Test fun brandWithSeparatorStays() {
        assertEquals("BBC: News", ChannelTitle.pretty("BBC: News"))
        assertEquals("AMC: Movies", ChannelTitle.pretty("AMC: Movies"))
        assertEquals("HBO: East", ChannelTitle.pretty("HBO - East"))
        assertEquals("CNN: International", ChannelTitle.pretty("CNN: International"))
        assertTrue(ChannelTitle.pretty("ESPN - 2").uppercase().startsWith("ESPN"))
    }

    @Test fun displayDoesNotChangeKeysAndSearchCanUseOriginal() {
        val raw = "AF: BNT 1"
        val item = Item("itv", "bnt1", raw, "cmd", "1", 12, false)
        assertEquals("itv:bnt1", item.key)
        assertEquals("BNT 1", ChannelTitle.pretty(item.name))
        assertEquals("AF: BNT 1", item.name)
        val hay = (item.name + " " + ChannelTitle.pretty(item.name)).lowercase()
        assertTrue(hay.contains("af"))
        assertTrue(hay.contains("bnt"))
    }

    @Test fun numberPlusCleanNameIsADisplayConcern() {
        val name = ChannelTitle.pretty("BG | Nova HD")
        assertEquals("Nova", name)
        assertFalse(name.startsWith("BG"))
        assertEquals("7  Nova", (if (7 > 0) "7  " else "") + name)
    }
}
