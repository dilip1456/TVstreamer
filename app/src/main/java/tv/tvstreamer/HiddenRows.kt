package tv.tvstreamer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Home rows the user hid with a row's ⋮ / Menu > "Hide this category". Kept in the synced hidden set as
 * "row:<tab>|<row key>\t<title>" (the title only for the restore list), next to hidden portal categories ("vod:<id>").
 */
object HomeHide {
    const val PREFIX = "row:"

    fun entry(tab: String, rowKey: String, title: String) = "$PREFIX$tab|$rowKey\t${title.replace('\t', ' ').take(60)}"
    fun id(entry: String) = entry.substringBefore('\t')
    fun title(entry: String) = entry.substringAfter('\t', "").ifEmpty { id(entry).substringAfter('|') }
    fun tab(entry: String) = id(entry).removePrefix(PREFIX).substringBefore('|')

    /** Ids of hidden rows ("row:<tab>|<key>") from the hidden set. */
    fun ids(hidden: Set<String>): Set<String> = hidden.filter { it.startsWith(PREFIX) }.map(::id).toSet()
    fun isHidden(hidden: Set<String>, tab: String, rowKey: String) = "$PREFIX$tab|$rowKey" in ids(hidden)
    fun entries(hidden: Set<String>): List<String> = hidden.filter { it.startsWith(PREFIX) }.sortedBy { title(it).lowercase() }

    fun tabLabel(tab: String) = when (tab) { "home" -> "Home"; "movies" -> "Movies"; "series" -> "Series"; else -> tab.replaceFirstChar { it.uppercase() } }
}

/**
 * Settings > Hidden, as a plain list (1.75): a short header per kind and one row per hidden item. Selecting a row shows
 * it again. The only place to restore hidden rows, categories, channels and titles.
 */
@Composable
fun HiddenRowsPanel(repo: Repo, modifier: Modifier = Modifier, title: Boolean = true) {
    val rows = HomeHide.entries(repo.hidden)
    val cats = repo.hidden.filter { it.startsWith("vod:") }.mapNotNull { k -> repo.vodCats.find { "vod:${it.id}" == k } }
    val liveCats = repo.hidden.filter { it.startsWith("itv:") }.mapNotNull { k -> repo.liveCats.find { "itv:${it.id}" == k } }
    val channels = repo.hiddenChannelCount()
    val groups = repo.hidden.filter { it.startsWith(Taxonomy.PREFIX) }.sorted()
    val titles = repo.hiddenTitleCount()
    val total = rows.size + cats.size + liveCats.size + groups.size + (if (channels > 0) 1 else 0) + (if (titles > 0) 1 else 0)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (title) Label("Hidden", Ts.TITLE)
        if (total == 0) {
            Label("Nothing is hidden", Ts.ROW, Pal.Text)
            Label("Rows, categories, channels and titles you hide appear here.", Ts.SECONDARY, Pal.Dim, maxLines = 3)
            return@Column
        }
        Label("Select an item to show it again.", Ts.SECONDARY, Pal.Dim, Modifier.padding(bottom = 4.dp))
        if (total > 1) SettingRow("Show everything again") {
            repo.unhideAllRows(); repo.unhideAllChannels(); repo.unhideAllTitles()
            liveCats.forEach { repo.toggleHide("itv", it.id) }
            groups.forEach { repo.toggleGroup(it) }
        }
        if (rows.isNotEmpty()) {
            HiddenHeader("Home rows")
            rows.forEach { e -> SettingRow("${HomeHide.title(e)}  ·  ${HomeHide.tabLabel(HomeHide.tab(e))}", "Show") { repo.unhideRow(e) } }
        }
        if (cats.isNotEmpty()) {
            HiddenHeader("Movie and series categories")
            cats.forEach { c -> SettingRow(GroupTitle.display(c.title), "Show") { repo.toggleHide("vod", c.id) } }
        }
        if (liveCats.isNotEmpty() || groups.isNotEmpty()) {
            HiddenHeader("Live TV")
            liveCats.forEach { c -> SettingRow(GroupTitle.display(c.title, "Live TV"), "Show") { repo.toggleHide("itv", c.id) } }
            groups.forEach { g -> SettingRow(Taxonomy.label(g), "Show") { repo.toggleGroup(g) } }
        }
        if (channels > 0) {
            HiddenHeader("Channels")
            SettingRow("$channels hidden channel" + (if (channels > 1) "s" else ""), "Show all") { repo.unhideAllChannels() }
        }
        if (titles > 0) {
            HiddenHeader("Titles")
            SettingRow("$titles hidden title" + (if (titles > 1) "s" else ""), "Show all") { repo.unhideAllTitles() }
        }
    }
}

@Composable
private fun HiddenHeader(text: String) {
    Label(text, Ts.HEAD, Pal.Dim, Modifier.padding(start = 6.dp, top = 12.dp, bottom = 2.dp))
}
