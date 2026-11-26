package tv.tvstreamer

/**
 * Catalog Selector tree (1.99.2): each column is a tree you can fold. Live TV keeps its own shape
 * (type, then sub-category, then region); Movies and Series are grouped under their language.
 *
 * Tick rules, both ways:
 *  - ticking a parent ticks it and everything under it; unticking clears them all;
 *  - a parent shows ticked when every child is ticked, partly ticked when some are, empty when none are;
 *  - ticking a child also ticks its parents (a hidden parent would hide it anyway); unticking the last ticked
 *    child of a parent unticks that parent too.
 * Language parents are virtual (no stored key): their tick is worked out from their categories only.
 * Plain logic so it can be unit-tested.
 */
object CatalogTree {
    enum class Tri { ON, PARTIAL, OFF }

    /** One row. [key] is the stored group key (null for a virtual language parent); [id] is unique in the tree. */
    data class Node(
        val id: String,
        val key: String?,
        val title: String,
        val count: Int,
        val depth: Int,
        val parent: String?,
        val children: List<String>
    ) {
        val isParent: Boolean get() = children.isNotEmpty()
    }

    class Tree(val nodes: Map<String, Node>, val order: List<String>) {
        val roots: List<Node> get() = order.mapNotNull { nodes[it] }.filter { it.parent == null }
        operator fun get(id: String): Node? = nodes[id]
        val isEmpty: Boolean get() = order.isEmpty()
        /** Every stored key in the tree (each once). */
        fun keys(): List<String> = order.mapNotNull { nodes[it]?.key }.distinct()
    }

    const val LANG = "lang|"

    /** A tree from groups that already carry their parent key (Live TV type / sub / region). */
    fun of(groups: List<CatalogGroups.Group>): Tree {
        val keys = groups.map { it.key }.toSet()
        val kids = LinkedHashMap<String, MutableList<String>>()
        for (g in groups) {
            val p = g.parent?.takeIf { it in keys } ?: continue
            kids.getOrPut(p) { ArrayList() } += g.key
        }
        val nodes = LinkedHashMap<String, Node>()
        val order = ArrayList<String>()
        for (g in groups) {
            if (g.key in nodes) continue
            val p = g.parent?.takeIf { it in keys }
            nodes[g.key] = Node(g.key, g.key, g.title, g.count, depthOf(g, groups), p, kids[g.key].orEmpty())
            order += g.key
        }
        return Tree(nodes, preorder(nodes, order))
    }

    private fun depthOf(g: CatalogGroups.Group, all: List<CatalogGroups.Group>): Int {
        val byKey = all.associateBy { it.key }
        var d = 0; var p = g.parent
        while (p != null && d < 8) { val pg = byKey[p] ?: break; d++; p = pg.parent }
        return d
    }

    /** Parents first, then their children in list order (so a child listed before its parent still sits under it). */
    private fun preorder(nodes: Map<String, Node>, order: List<String>): List<String> {
        val out = ArrayList<String>(order.size)
        val seen = HashSet<String>()
        fun walk(id: String) {
            if (!seen.add(id)) return
            out += id
            nodes[id]?.children?.forEach(::walk)
        }
        order.filter { nodes[it]?.parent == null }.forEach(::walk)
        order.forEach(::walk)
        return out
    }

    /**
     * Movies / Series: categories under a virtual language parent. [langOf] gives each category one language;
     * languages keep the order of [langOrder] (then first seen). With a single language there is still one
     * parent, so All / None for that language stays one press.
     */
    fun byLanguage(groups: List<CatalogGroups.Group>, langOf: (CatalogGroups.Group) -> String, langOrder: List<String> = emptyList()): Tree {
        val by = LinkedHashMap<String, MutableList<CatalogGroups.Group>>()
        for (g in groups) by.getOrPut(langOf(g).ifBlank { Tags.OTHER }) { ArrayList() } += g
        val rank = langOrder.withIndex().associate { it.value to it.index }
        val langs = by.keys.sortedWith(compareBy<String> { it == Tags.OTHER }.thenBy { rank[it] ?: Int.MAX_VALUE })
        val nodes = LinkedHashMap<String, Node>()
        val order = ArrayList<String>()
        for (l in langs) {
            val pid = LANG + l
            val kids = by.getValue(l).distinctBy { it.key }
            nodes[pid] = Node(pid, null, l, kids.sumOf { it.count }, 0, null, kids.map { pid + "/" + it.key })
            order += pid
            for (g in kids) {
                val id = pid + "/" + g.key
                nodes[id] = Node(id, g.key, g.title, g.count, 1, pid, emptyList())
                order += id
            }
        }
        return Tree(nodes, order)
    }

    /** Stored keys of a node and everything under it. */
    fun subtreeKeys(t: Tree, id: String): List<String> {
        val out = ArrayList<String>()
        fun walk(n: Node) { n.key?.let { out += it }; n.children.forEach { c -> t[c]?.let(::walk) } }
        t[id]?.let(::walk)
        return out.distinct()
    }

    /** Tick state of a node: leaves by their key; parents from their children (a stored parent that is off is off). */
    fun state(t: Tree, id: String, on: (String) -> Boolean): Tri {
        val n = t[id] ?: return Tri.OFF
        if (!ancestorsOn(t, n, on)) return Tri.OFF
        return ownState(t, n, on)
    }

    /** Whether every stored parent above [n] is ticked (an unticked parent hides what is under it). */
    private fun ancestorsOn(t: Tree, n: Node, on: (String) -> Boolean): Boolean {
        var p = n.parent?.let { t[it] }
        while (p != null) { val pk = p.key; if (pk != null && !on(pk)) return false; p = p.parent?.let { t[it] } }
        return true
    }

    private fun ownState(t: Tree, n: Node, on: (String) -> Boolean): Tri {
        if (!n.isParent) return if (n.key == null || on(n.key)) Tri.ON else Tri.OFF
        if (n.key != null && !on(n.key)) return Tri.OFF
        var any = false; var all = true
        for (c in n.children) {
            val cn = t[c] ?: continue
            when (ownState(t, cn, on)) {
                Tri.ON -> any = true
                Tri.PARTIAL -> { any = true; all = false }
                Tri.OFF -> all = false
            }
        }
        return when { all -> Tri.ON; any -> Tri.PARTIAL; else -> Tri.OFF }
    }

    /** What a press on [id] changes: keys to turn on and keys to turn off (none in both). */
    data class Change(val on: List<String>, val off: List<String>) {
        val isEmpty: Boolean get() = on.isEmpty() && off.isEmpty()
    }

    /**
     * Press on [id]: a fully ticked node is cleared with everything under it; a partly ticked or empty one is
     * ticked with everything under it. Parents follow (see the rules at the top).
     */
    fun toggle(t: Tree, id: String, on: (String) -> Boolean): Change {
        val n = t[id] ?: return Change(emptyList(), emptyList())
        val target = state(t, id, on) != Tri.ON
        return set(t, n, target, on)
    }

    /** Tick or clear [id] and everything under it, with the parent rules applied. */
    fun set(t: Tree, id: String, value: Boolean, on: (String) -> Boolean): Change =
        t[id]?.let { set(t, it, value, on) } ?: Change(emptyList(), emptyList())

    private fun set(t: Tree, n: Node, value: Boolean, on: (String) -> Boolean): Change {
        val now = HashMap<String, Boolean>()
        fun cur(k: String) = now[k] ?: on(k)
        for (k in subtreeKeys(t, n.id)) now[k] = value
        var p = n.parent?.let { t[it] }
        var from = n.id
        while (p != null) {
            val pk = p.key
            if (pk != null) {
                if (value) {
                    // An unticked parent hid its other children: keep them unticked when it comes back on
                    if (!cur(pk)) for (c in p.children) if (c != from) for (k in subtreeKeys(t, c)) now[k] = false
                    now[pk] = true
                }
                else {
                    // the last ticked child went: the parent goes too
                    val anyKid = p.children.any { c -> subtreeKeys(t, c).any { cur(it) } }
                    if (!anyKid) now[pk] = false
                }
            }
            from = p.id
            p = p.parent?.let { t[it] }
        }
        val turnOn = now.filter { (k, v) -> v && !on(k) }.keys.toList()
        val turnOff = now.filter { (k, v) -> !v && on(k) }.keys.toList()
        return Change(turnOn, turnOff)
    }

    /** Rows to draw: roots, plus the children of every expanded parent (recursively). */
    fun visible(t: Tree, expanded: Set<String>): List<Node> {
        val out = ArrayList<Node>()
        fun walk(n: Node) {
            out += n
            if (n.isParent && n.id in expanded) n.children.forEach { c -> t[c]?.let(::walk) }
        }
        t.roots.forEach(::walk)
        return out
    }

    /** Fold or unfold one parent. */
    fun toggleExpanded(expanded: Set<String>, id: String): Set<String> = if (id in expanded) expanded - id else expanded + id

    /** What folding should open first: a lone root is opened so its rows show at once; otherwise all folded. */
    fun initialExpanded(t: Tree): Set<String> = t.roots.singleOrNull()?.takeIf { it.isParent }?.let { setOf(it.id) } ?: emptySet()

    /** Left on a child folds its parent; returns the parent id to focus (null for a root). */
    fun collapseFrom(t: Tree, id: String, expanded: Set<String>): Pair<Set<String>, String?> {
        val p = t[id]?.parent ?: return expanded to null
        return (expanded - p - descendants(t, p).toSet()) to p
    }

    private fun descendants(t: Tree, id: String): List<String> {
        val out = ArrayList<String>()
        fun walk(n: Node) { n.children.forEach { c -> out += c; t[c]?.let(::walk) } }
        t[id]?.let(::walk)
        return out
    }

    /** Ticked / total leaves under a parent ("3 / 12"). */
    fun tally(t: Tree, id: String, on: (String) -> Boolean): Pair<Int, Int> {
        val n = t[id] ?: return 0 to 0
        if (!n.isParent) return (if (state(t, id, on) == Tri.ON) 1 else 0) to 1
        var a = 0; var b = 0
        fun walk(x: Node) {
            if (!x.isParent) { b++; if (state(t, x.id, on) == Tri.ON) a++ } else x.children.forEach { c -> t[c]?.let(::walk) }
        }
        walk(n)
        return a to b
    }

    /** Index of [id] among the rows drawn. */
    fun indexIn(rows: List<Node>, id: String): Int = rows.indexOfFirst { it.id == id }
}
