package tv.tvstreamer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FoldersTest {
    private fun ch(id: String, name: String = "Ch $id") = Item("itv", id, name, "cmd$id", "1", id.toIntOrNull() ?: 0, false)
    private fun keys(l: List<Item>) = l.map { it.id }

    @Test fun createCleansNamesAndIgnoresDuplicates() {
        val f = FolderSet().create("  Sports \t  HD ").create("Sports HD").create("   ")
        assertEquals(listOf("Sports HD"), f.names)
        assertEquals(40, FolderSet.clean("x".repeat(60)).length)
    }

    @Test fun addDedupesKeepsOrderAndAllowsSeveralFolders() {
        var f = FolderSet().add("News", listOf(ch("1"), ch("2"), ch("1")))
        f = f.add("News", listOf(ch("2"), ch("3")))
        f = f.add("Kids", listOf(ch("2")))
        assertEquals(listOf("1", "2", "3"), keys(f.items("News")))
        assertEquals(listOf("News", "Kids"), f.names)               // creation order
        assertEquals(listOf("News", "Kids"), f.foldersOf("itv:2"))
        assertTrue(f.contains("itv:2")); assertFalse(f.contains("itv:9"))
        assertEquals(listOf("1", "2", "3"), keys(f.all()))         // each favourite once
    }

    @Test fun removeFromOneFolderOrEverywhere() {
        val f = FolderSet().add("A", listOf(ch("1"), ch("2"))).add("B", listOf(ch("1")))
        val one = f.remove("A", setOf("itv:1"))
        assertEquals(listOf("2"), keys(one.items("A")))
        assertTrue(one.contains("itv:1"))                           // still in B -> still a favourite
        val none = f.removeEverywhere(setOf("itv:1"))
        assertFalse(none.contains("itv:1"))
        assertEquals(listOf("A", "B"), none.names)                  // folders stay, B now empty
        assertTrue(none.items("B").isEmpty())
    }

    @Test fun renameKeepsPlaceAndMergesIntoExisting() {
        val f = FolderSet().add("A", listOf(ch("1"))).add("B", listOf(ch("2"))).add("C", listOf(ch("1"), ch("3")))
        val r = f.rename("B", "Movies")
        assertEquals(listOf("A", "Movies", "C"), r.names)
        assertEquals(listOf("2"), keys(r.items("Movies")))
        val m = f.rename("A", "C")
        assertEquals(listOf("B", "C"), m.names)
        assertEquals(listOf("1", "3"), keys(m.items("C")))         // merged without duplicates
        assertSame(f, f.rename("Nope", "X"))
    }

    @Test fun deleteAndReorder() {
        val f = FolderSet().create("A").create("B").create("C")
        assertEquals(listOf("B", "A", "C"), f.move("B", -1).names)
        assertEquals(listOf("A", "C", "B"), f.move("B", 1).names)
        assertEquals(f, f.move("A", -1))                            // already first
        assertEquals(listOf("A", "C"), f.delete("B").names)
        // A folder made on another device and missing from the order goes after, A-Z
        val other = FolderSet(f.folders + ("Zed" to emptyList()) + ("Alpha" to emptyList()), f.order)
        assertEquals(listOf("A", "B", "C", "Alpha", "Zed"), other.names)
    }

    @Test fun moveChannelInsideFolder() {
        val f = FolderSet().add("A", listOf(ch("1"), ch("2"), ch("3")))
        assertEquals(listOf("2", "1", "3"), keys(f.moveItem("A", "itv:2", -1).items("A")))
        assertEquals(listOf("1", "2", "3"), keys(f.moveItem("A", "itv:3", 1).items("A")))
    }

    @Test fun membershipForThePicker() {
        val f = FolderSet().add("A", listOf(ch("1"), ch("2"))).add("B", listOf(ch("1"))).create("C")
        val m = f.membership(setOf("itv:1", "itv:2"))
        assertEquals(FolderSet.Member.ALL, m["A"])
        assertEquals(FolderSet.Member.SOME, m["B"])
        assertEquals(FolderSet.Member.NONE, m["C"])
    }

    @Test fun migrateOldFavouritesOnce() {
        val start = FolderSet().add("Kids", listOf(ch("5")))
        val m = FolderSet.migrate(start, listOf(ch("1"), ch("5"), ch("2")))
        assertEquals(listOf("1", "2"), keys(m.items(FolderSet.DEFAULT)))  // ch 5 already had a folder
        assertSame(m, FolderSet.migrate(m, listOf(ch("1"))))
    }

    @Test fun channelNamesApplySetAndReset() {
        val portal = mapOf("itv:1" to ch("1", "BBC One HD"))
        var names = ChannelNames.set(emptyMap(), "itv:1", "  BBC   1 ")
        assertEquals("BBC 1", names["itv:1"])
        assertEquals("BBC 1", ChannelNames.apply(ch("1", "BBC One HD"), names, portal).name)
        // A stored copy (folder / recent) with the old custom name goes back to the portal name after a reset
        names = ChannelNames.set(names, "itv:1", "")
        assertTrue(names.isEmpty())
        assertEquals("BBC One HD", ChannelNames.apply(ch("1", "BBC 1"), names, portal).name)
        // Movies are never renamed; unknown channels stay as they are
        val vod = Item("vod", "1", "Film", "", "", 0, false)
        assertSame(vod, ChannelNames.apply(vod, mapOf("vod:1" to "X"), portal))
        val unknown = ch("9", "Nine")
        assertSame(unknown, ChannelNames.apply(unknown, names, portal))
    }

    // ---- sync of folders and names ----
    private fun j(id: String) = JSONObject().put("k", "itv").put("i", id).put("n", "N$id").toString()

    @Test fun emptyFoldersAndNamesSurviveSync() {
        val l = SyncLocal(custom = sortedMapOf("Empty" to emptyList(), "News" to listOf(j("1"))), names = sortedMapOf("itv:1" to "My BBC"))
        val back = SyncCodec.unflatten(Sync.stamp(emptyMap(), SyncCodec.flatten(l), now = 10))
        assertEquals(l, back)
    }

    @Test fun folderEditsOnTwoDevicesMerge() {
        val base = Sync.stamp(emptyMap(), SyncCodec.flatten(SyncLocal(custom = sortedMapOf("A" to listOf(j("1")), "Old" to listOf(j("9"))))), now = 100)
        // TV adds channel 2 to A; phone adds channel 3 to A, deletes "Old" and renames channel 1
        val tv = Sync.stamp(base, SyncCodec.flatten(SyncLocal(custom = sortedMapOf("A" to listOf(j("1"), j("2")), "Old" to listOf(j("9"))))), now = 200)
        val phone = Sync.stamp(base, SyncCodec.flatten(SyncLocal(custom = sortedMapOf("A" to listOf(j("1"), j("3"))), names = mapOf("itv:1" to "One"))), now = 300)
        val m = SyncCodec.unflatten(Sync.merge(tv, phone))
        assertEquals(setOf("A"), m.custom.keys)                    // deletion wins over the older state
        assertEquals(listOf("1", "3", "2"), m.custom["A"]!!.map { JSONObject(it).getString("i") })
        assertEquals("One", m.names["itv:1"])
        // Resetting the name on the TV later removes it everywhere
        val reset = Sync.stamp(Sync.merge(tv, phone), SyncCodec.flatten(m.copy(names = emptyMap())), now = 400)
        assertTrue(SyncCodec.unflatten(reset).names.isEmpty())
    }
}
