package tv.tvstreamer

/**
 * "My Channels": the user's own channel folders. This is the one source of truth for favourite channels: a channel is a
 * favourite when it is in at least one folder (it may be in several). Folder order is the user's; inside a folder,
 * channels keep the order they were added in (and can be moved up / down). Immutable: every change returns a new set.
 */
data class FolderSet(val folders: Map<String, List<Item>> = emptyMap(), val order: List<String> = emptyList()) {
    /** Folder names in the user's order; folders missing from the order (e.g. made on another device) follow A-Z. */
    val names: List<String> get() = order.filter { it in folders }.distinct() + folders.keys.filter { it !in order }.sorted()

    fun items(name: String): List<Item> = folders[name].orEmpty()
    fun contains(key: String): Boolean = folders.values.any { l -> l.any { it.key == key } }
    fun foldersOf(key: String): List<String> = names.filter { n -> items(n).any { it.key == key } }
    /** Every favourite channel once, folder by folder. */
    fun all(): List<Item> = names.flatMap { items(it) }.distinctBy { it.key }

    private fun with(m: Map<String, List<Item>>, o: List<String> = names) = FolderSet(m, o.filter { it in m }.distinct())

    fun create(name: String): FolderSet {
        val n = clean(name)
        if (n.isEmpty() || n in folders) return this
        return with(folders + (n to emptyList()), names + n)
    }

    /** Renames [old]; renaming onto an existing folder merges the two. */
    fun rename(old: String, new: String): FolderSet {
        val n = clean(new)
        if (n.isEmpty() || old !in folders || n == old) return this
        val merged = (items(n) + items(old)).distinctBy { it.key }
        val o = if (n in folders) names.filter { it != old } else names.map { if (it == old) n else it }
        return with(folders - old + (n to merged), o)
    }

    fun delete(name: String): FolderSet = if (name !in folders) this else with(folders - name)

    /** Moves a folder [step] places (−1 = earlier). */
    fun move(name: String, step: Int): FolderSet {
        val l = names.toMutableList()
        val i = l.indexOf(name)
        val j = i + step
        if (i < 0 || j !in l.indices) return this
        l.removeAt(i); l.add(j, name)
        return FolderSet(folders, l)
    }

    /** Adds channels to [name] (made when missing); channels already there keep their place. */
    fun add(name: String, chans: List<Item>): FolderSet {
        val n = clean(name)
        if (n.isEmpty()) return this
        val have = items(n)
        val keys = have.map { it.key }.toSet()
        val base = if (n in folders) this else create(n)
        return FolderSet(base.folders + (n to have + chans.filter { it.key !in keys }.distinctBy { it.key }), base.names)
    }

    fun remove(name: String, keys: Set<String>): FolderSet =
        if (name !in folders) this else FolderSet(folders + (name to items(name).filter { it.key !in keys }), names)

    /** "Remove from favourites": out of every folder (the folders themselves stay). */
    fun removeEverywhere(keys: Set<String>): FolderSet =
        FolderSet(folders.mapValues { (_, l) -> l.filter { it.key !in keys } }, names)

    /** Moves one channel inside a folder [step] places. */
    fun moveItem(name: String, key: String, step: Int): FolderSet {
        val l = items(name).toMutableList()
        val i = l.indexOfFirst { it.key == key }
        val j = i + step
        if (i < 0 || j !in l.indices) return this
        val x = l.removeAt(i); l.add(j, x)
        return FolderSet(folders + (name to l), names)
    }

    /** The same set with every stored channel copy passed through [f] (e.g. a new display name). */
    fun mapItems(f: (Item) -> Item): FolderSet = FolderSet(folders.mapValues { (_, l) -> l.map(f) }, order)

    /**
     * How [keys] sit in each folder for the picker: ALL (every one is in it), SOME or NONE.
     */
    fun membership(keys: Set<String>): Map<String, Member> = names.associateWith { n ->
        val inside = items(n).count { it.key in keys }
        when { keys.isEmpty() || inside == 0 -> Member.NONE; inside == keys.size -> Member.ALL; else -> Member.SOME }
    }

    enum class Member { NONE, SOME, ALL }

    companion object {
        const val DEFAULT = "Favorites"
        /** Trimmed, single spaces, no tabs (the sync key separator), at most 40 characters. */
        fun clean(name: String): String = name.replace('\t', ' ').trim().replace(Regex("\\s+"), " ").take(40).trim()

        /** One-time move of the old favourite channel list into a "Favorites" folder (channels already in a folder are skipped). */
        fun migrate(set: FolderSet, oldFavChannels: List<Item>): FolderSet {
            val add = oldFavChannels.filter { !set.contains(it.key) }
            return if (add.isEmpty()) set else set.add(DEFAULT, add)
        }
    }
}

/** User display names for channels (channel key -> name), applied over the portal's names. */
object ChannelNames {
    fun clean(name: String): String = name.trim().replace(Regex("\\s+"), " ").take(80)

    /** [i] with its custom name, else its portal name from [portal] (so a reset name comes back), else unchanged. */
    fun apply(i: Item, names: Map<String, String>, portal: Map<String, Item>): Item {
        if (i.kind != "itv") return i
        val n = names[i.key] ?: portal[i.key]?.name ?: return i
        return if (n == i.name) i else i.copy(name = n)
    }

    fun set(names: Map<String, String>, key: String, name: String?): Map<String, String> {
        val n = name?.let { clean(it) }.orEmpty()
        return if (n.isEmpty()) names - key else names + (key to n)
    }
}
