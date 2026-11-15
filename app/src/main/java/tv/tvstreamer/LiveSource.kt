package tv.tvstreamer

import org.json.JSONObject

/** The two portal calls behind the channel list (get_genres + get_all_channels). Faked in tests to count calls. */
interface LivePortal {
    fun liveCats(): List<Cat>
    fun channels(): List<Item>
}

/**
 * Where the channel list is kept on the device (live.json, in the app's files folder, which Android never clears).
 * 1.72: written safely (a temporary file renamed over the old one, the previous copy kept as a backup), so an app
 * killed mid-save or an update never leaves a broken list behind.
 */
interface LiveDisk {
    fun read(): String?
    fun write(text: String)
    /** The previous good copy (live.json.bak), used when the main file is missing or unreadable. */
    fun readBackup(): String? = null
}

/** The portal answered, but with no channels: the saved list is kept (1.72). */
class EmptyChannelListException : Exception("The portal sent an empty channel list; the saved list is kept")

/**
 * The channel + genre list (1.70): read from the device once, then kept in memory. The portal is asked only:
 *  - on the very first run (nothing saved),
 *  - by the daily refresh ([due]: a day old, or the first launch of a new day), or
 *  - when asked to ([fetch]: Settings > Refresh, a new login, links failing again and again).
 * Opening Live TV, modes, hide / show, renames, folding and categories never call the portal (see LiveModel).
 * 1.72: a refresh never replaces the saved list with an empty answer, and an unchanged answer keeps the same list
 * objects (nothing is laid out again). Older saved files (1.6x-1.71, same fields, no "v") still read.
 */
class LiveSource(
    private val portal: LivePortal,
    private val disk: LiveDisk,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tzOffset: (Long) -> Long = { java.util.TimeZone.getDefault().getOffset(it).toLong() }
) {
    class Snapshot(val cats: List<Cat>, val items: List<Item>, val at: Long)

    @Volatile var snap: Snapshot? = null
        private set
    @Volatile private var diskRead = false
    private val lock = Any()

    /** The saved list: parsed from disk on first use, then served from memory. Never the portal. */
    fun local(): Snapshot? {
        if (diskRead) return snap
        synchronized(lock) {
            if (!diskRead) {
                snap = readable(runCatching { disk.read() }.getOrNull()) ?: readable(runCatching { disk.readBackup() }.getOrNull())
                diskRead = true
            }
            return snap
        }
    }

    private fun readable(text: String?): Snapshot? =
        text?.let { t -> runCatching { decode(t) }.getOrNull() }?.takeIf { it.items.isNotEmpty() }

    /** True when a channel list is on the device (Live TV can open with no portal call). */
    fun hasSaved(): Boolean = local()?.items?.isNotEmpty() == true

    /** True when the daily refresh may ask the portal (also when nothing is saved). */
    fun due(now: Long = clock()): Boolean {
        val s = local() ?: return true
        if (s.items.isEmpty()) return true
        return CatalogStale.due(now, s.at, CatalogStale.DAY_MS, tzOffset(now))
    }

    /** Reads the list from the portal (one get_genres + one get_all_channels) and saves it. */
    fun fetch(): Snapshot {
        val old = local()
        val lc = portal.liveCats()
        val ch = portal.channels()
        if (ch.isEmpty()) throw EmptyChannelListException()
        // Same answer as the saved one: keep the same lists (the screen keeps its layout), only the time moves on
        val s = if (old != null && old.items == ch && old.cats == lc) Snapshot(old.cats, old.items, clock())
            else Snapshot(lc, ch, clock())
        runCatching { disk.write(encode(s)) }
        synchronized(lock) { snap = s; diskRead = true }
        return s
    }

    companion object {
        fun encode(s: Snapshot): String =
            JSONObject().put("v", FORMAT).put("t", s.at).put("cats", catsJson(s.cats)).put("items", itemsJson(s.items)).toString()

        /** Reads this format and the older ones (no "v"); a missing category list is not fatal. */
        fun decode(text: String): Snapshot {
            val o = JSONObject(text)
            val cats = o.optJSONArray("cats")?.let { a -> runCatching { a.toCats() }.getOrNull() } ?: emptyList()
            return Snapshot(cats, o.getJSONArray("items").toItems(), o.optLong("t"))
        }

        /** Saved-file format (1.72 adds the field; older files have none and read the same way). */
        const val FORMAT = 2
    }
}

/** Everything the Live TV list shows depends on these, and only these (all local). */
class LiveInputs(
    val channels: List<Item>, val cats: List<Cat>, val catOrder: List<String>, val hidden: Set<String>,
    val showAdult: Boolean, val langs: Set<String>?, val info: Map<String, ChInfo>, val mode: String,
    val folders: FolderSet, val view: LiveModes.View
) {
    /** Same inputs (by identity, so the check is instant even for 20 000 channels). */
    fun same(o: LiveInputs?): Boolean = o != null && o.channels === channels && o.cats === cats && o.catOrder == catOrder &&
        (o.hidden === hidden || o.hidden == hidden) && o.showAdult == showAdult && o.langs == langs && o.info === info &&
        o.mode == mode && (o.folders === folders || o.folders == folders) && o.view == view
}

/**
 * The Live TV list worked out from [LiveInputs], off the main thread: pure local filtering and grouping with a stable
 * order (portal / category order, channel numbers, or the mode's own order). No portal access.
 */
class LiveModel(
    val inputs: LiveInputs?,
    val base: List<Item>,
    val rows: List<LiveModes.Row>,
    val tree: List<LiveModes.Node>,
    val sections: List<LiveModes.Section>,
    val allLangs: List<Pair<String, Int>>,
    val catTitle: Map<String, String>,
    internal val classified: List<LiveModes.Classified> = emptyList()
) {
    val types: List<String> = sections.map { it.type }.distinct()
    val flat: List<Item> = sections.flatMap { s -> s.rows.map { it.ch } }
    val flatPos: Map<String, Int> = HashMap<String, Int>(flat.size * 2).also { m -> flat.forEachIndexed { i, c -> m[c.key] = i } }
    val modeCount: Int = rows.count { it.on }

    companion object {
        val EMPTY = LiveModel(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())

        /** Categories in the user's order, hidden ones left out (same rule as Repo.visibleLive). */
        fun visibleCats(cats: List<Cat>, order: List<String>, hidden: Set<String>): List<Cat> {
            val c = cats.distinctBy { it.id }
            val sorted = if (order.isEmpty()) c else {
                val pos = order.withIndex().associate { it.value to it.index }
                c.filter { it.id in pos }.sortedBy { pos[it.id] } + c.filter { it.id !in pos }
            }
            return sorted.filter { "itv:${it.id}" !in hidden }
        }

        private fun hiddenCats(h: Set<String>): Set<String> = h.filterTo(HashSet()) { it.startsWith("itv:") }

        fun build(i: LiveInputs, previous: LiveModel? = null): LiveModel {
            val cats = visibleCats(i.cats, i.catOrder, i.hidden)
            val catIds = cats.mapTo(HashSet()) { it.id }
            val catOrder = cats.map { it.id }
            val p = previous?.inputs
            val catTitle = if (p?.cats === i.cats) previous!!.catTitle else i.cats.associate { it.id to it.title }
            // Hiding / showing channels, modes and views reuse the classified list: only the cheap steps run again
            val sameBase = p != null && p.channels === i.channels && p.cats === i.cats && p.showAdult == i.showAdult &&
                p.langs == i.langs && p.info === i.info && hiddenCats(p.hidden) == hiddenCats(i.hidden)
            val base = if (sameBase) previous!!.base else i.channels.filter { it.cat in catIds && AdultContent.listOk(it.locked, i.showAdult) }
            val classified = if (sameBase) previous!!.classified else LiveModes.classify(base, i.langs, i.info, catTitle)
            val rows = LiveModes.rowsFrom(classified, i.mode, i.folders, i.hidden, i.view)
            val tree = LiveModes.tree(rows, i.hidden, i.view)
            val sections = LiveModes.sections(rows, i.info, catTitle, catOrder,
                if (i.mode != LiveModes.DEFAULT) i.folders.items(i.mode).map { it.key } else null)
            val langs = if (sameBase) previous!!.allLangs else LiveModes.languages(base, i.info)
            return LiveModel(i, base, rows, tree, sections, langs, catTitle, classified)
        }
    }
}
