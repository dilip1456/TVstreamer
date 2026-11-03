package tv.tvstreamer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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

/** Settings > Hidden: hidden Home rows, categories and channels, each with "Show again" (D-pad: OK). The only place to restore them. */
@Composable
fun HiddenRowsPanel(repo: Repo, modifier: Modifier = Modifier) {
    val rows = HomeHide.entries(repo.hidden)
    val cats = repo.hidden.filter { it.startsWith("vod:") }.mapNotNull { k -> repo.vodCats.find { "vod:${it.id}" == k } }
    val liveCats = repo.hidden.filter { it.startsWith("itv:") }.mapNotNull { k -> repo.liveCats.find { "itv:${it.id}" == k } }
    val channels = repo.hiddenChannelCount()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label("Hidden", 18)
        if (rows.isEmpty() && cats.isEmpty() && liveCats.isEmpty() && channels == 0) Label("Nothing hidden", 13, Pal.Dim)
        rows.forEach { e ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("${HomeHide.title(e)}  ·  ${HomeHide.tabLabel(HomeHide.tab(e))}", 14, Pal.Text, Modifier.weight(1f))
                PillButton("Show again", { repo.unhideRow(e) }, size = 13)
            }
        }
        cats.forEach { c ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("${Names.cat(c.title)}  ·  category", 14, Pal.Text, Modifier.weight(1f))
                PillButton("Show again", { repo.toggleHide("vod", c.id) }, size = 13)
            }
        }
        liveCats.forEach { c ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("${Names.cat(c.title)}  ·  Live TV", 14, Pal.Text, Modifier.weight(1f))
                PillButton("Show again", { repo.toggleHide("itv", c.id) }, size = 13)
            }
        }
        if (channels > 0) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("$channels hidden channel" + (if (channels > 1) "s" else ""), 14, Pal.Text, Modifier.weight(1f))
            PillButton("Show again", { repo.unhideAllChannels() }, size = 13)
        }
        if (rows.size + cats.size > 1) PillButton("Show all rows again", { repo.unhideAllRows() }, size = 13)
    }
}
