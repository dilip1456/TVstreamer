package tv.tvstreamer

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import android.content.SharedPreferences
import org.json.JSONArray
import java.io.File

class Store(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("data", Context.MODE_PRIVATE)
    private val dir: File = ctx.filesDir

    // Prefs + internal file (always writable) + external file (survives reinstall)
    private val sec: SharedPreferences = ctx.getSharedPreferences("secure", Context.MODE_PRIVATE)
    private val internalCfg: File = File(dir, "config.json")
    private val externalCfg: File = File(Environment.getExternalStorageDirectory(), "TVStreamer/config.json")

    init { loadConfig() }

    private fun loadConfig() {
        val sources = listOf(internalCfg, externalCfg)
        for (f in sources) {
            runCatching {
                if (f.exists()) {
                    val o = JSONObject(f.readText())
                    if (portal.isEmpty() && o.optString("portal").isNotEmpty()) portal = o.optString("portal")
                    if (mac.isEmpty() && o.optString("mac").isNotEmpty()) mac = o.optString("mac")
                    if (serial.isEmpty() && o.optString("serial").isNotEmpty()) serial = o.optString("serial")
                    if (password.isEmpty() && o.optString("pw").isNotEmpty()) password = o.optString("pw")
                }
            }
        }
    }

    private fun saveConfig() {
        val json = JSONObject()
            .put("portal", portal)
            .put("mac", mac)
            .put("serial", serial)
            .put("pw", password)
            .toString()
        runCatching { internalCfg.writeText(json) }
        runCatching {
            externalCfg.parentFile?.mkdirs()
            externalCfg.writeText(json)
        }
    }

    var portal: String
        get() = sec.getString("portal", "") ?: ""
        set(v) { sec.edit().putString("portal", v.trim()).commit(); saveConfig() }

    var mac: String
        get() = sec.getString("mac", "") ?: ""
        set(v) { sec.edit().putString("mac", v.trim()).commit(); saveConfig() }

    var serial: String
        get() = sec.getString("serial", "") ?: ""
        set(v) { sec.edit().putString("serial", v.trim()).commit(); saveConfig() }

    var password: String
        get() = sec.getString("pw", "") ?: ""
        set(v) { sec.edit().putString("pw", v).commit(); saveConfig() }

    var hidden: Set<String>
        get() = p.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()
        set(v) { p.edit().putStringSet("hidden", v.toSet()).apply() }

    fun list(name: String): List<Item> =
        runCatching { JSONArray(p.getString(name, "[]")).toItems() }.getOrDefault(emptyList())

    fun saveList(name: String, l: List<Item>) {
        p.edit().putString(name, itemsJson(l).toString()).apply()
    }

    fun addRecent(i: Item) {
        if (i.locked) return
        saveList("recent", (listOf(i) + list("recent").filter { it.key != i.key }).take(30))
    }

    private fun resumeArray(): JSONArray =
        runCatching { JSONArray(p.getString("resume", "[]")) }.getOrDefault(JSONArray())

    fun saveResume(i: Item, pos: Long, dur: Long) {
        if (i.locked) return
        val old = resumeArray()
        val out = JSONArray()
        out.put(i.toJson().put("p", pos).put("d", dur))
        for (x in 0 until old.length()) {
            val o = old.getJSONObject(x)
            if ("${o.getString("k")}:${o.getString("i")}" != i.key) out.put(o)
            if (out.length() >= 40) break
        }
        p.edit().putString("resume", out.toString()).apply()
    }

    fun resumePos(i: Item): Long {
        val a = resumeArray()
        for (x in 0 until a.length()) {
            val o = a.getJSONObject(x)
            if ("${o.getString("k")}:${o.getString("i")}" == i.key) return o.optLong("p")
        }
        return 0L
    }

    fun continueList(): List<Pair<Item, Long>> {
        val a = resumeArray()
        val out = mutableListOf<Pair<Item, Long>>()
        for (x in 0 until a.length()) {
            val o = a.getJSONObject(x)
            val pos = o.optLong("p")
            val d = o.optLong("d")
            if (pos > 30000 && (d <= 0 || pos < d * 0.95)) out += Item.from(o) to pos
        }
        return out
    }

    fun customCats(): Map<String, List<Item>> {
        val raw = p.getString("custom_cats", "{}") ?: "{}"
        return try {
            val o = org.json.JSONObject(raw)
            o.keys().asSequence().associateWith { k -> o.getJSONArray(k).toItems() }
        } catch (_: Exception) { emptyMap() }
    }
    fun addToCustom(name: String, item: Item) {
        val m = customCats().toMutableMap()
        val list = m.getOrPut(name) { emptyList() }.toMutableList()
        if (list.none { it.key == item.key }) list += item
        m[name] = list
        val o = org.json.JSONObject()
        m.forEach { (k, v) -> o.put(k, itemsJson(v)) }
        p.edit().putString("custom_cats", o.toString()).apply()
    }
    /** User arranged order of categories by kind ("itv" or "vod"). Ids not listed keep the portal order after these. */
    fun catOrder(kind: String): List<String> =
        runCatching { JSONArray(p.getString("order_$kind", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }
            .getOrDefault(emptyList())

    fun saveCatOrder(kind: String, ids: List<String>) {
        p.edit().putString("order_$kind", JSONArray(ids).toString()).apply()
    }

    fun removeResume(key: String) {
        val old = resumeArray()
        val out = JSONArray()
        for (x in 0 until old.length()) {
            val o = old.getJSONObject(x)
            if ("${o.getString("k")}:${o.getString("i")}" != key) out.put(o)
        }
        p.edit().putString("resume", out.toString()).apply()
    }

    fun resumeInfo(i: Item): Pair<Long, Long> {
        val a = resumeArray()
        for (x in 0 until a.length()) {
            val o = a.getJSONObject(x)
            if ("${o.getString("k")}:${o.getString("i")}" == i.key) return o.optLong("p") to o.optLong("d")
        }
        return 0L to 0L
    }

    /** App theme: dark (default), light or system. Synced. */
    var theme: String
        get() = p.getString("theme", "dark") ?: "dark"
        set(v) { p.edit().putString("theme", v).apply() }

    /** Player picture mode: fit, fill or zoom. */
    var aspect: String
        get() = p.getString("aspect", "fit") ?: "fit"
        set(v) { p.edit().putString("aspect", v).apply() }

    /** Where the player's option bar sits: bottom, left or right. */
    var barPos: String
        get() = p.getString("bar_pos", "bottom") ?: "bottom"
        set(v) { p.edit().putString("bar_pos", v).apply() }

    /** TMDB v3 API key (or v4 read token) and optional OMDb key. Stored only in the private "secure" prefs, never in the repo. */
    var tmdbKey: String
        get() = sec.getString("tmdb_key", "") ?: ""
        set(v) { sec.edit().putString("tmdb_key", v.trim()).apply() }

    var omdbKey: String
        get() = sec.getString("omdb_key", "") ?: ""
        set(v) { sec.edit().putString("omdb_key", v.trim()).apply() }

    /** Remote button mapping (JSON, see Keys). */
    var keymap: String
        get() = p.getString("keymap", "") ?: ""
        set(v) { p.edit().putString("keymap", v).apply() }

    /** Languages picked for a tab ("movies" / "series"). null = never chosen (show everything). */
    fun langs(tab: String): Set<String>? =
        if (!p.contains("langs_$tab")) null else p.getStringSet("langs_$tab", emptySet())?.toSet()

    fun saveLangs(tab: String, v: Set<String>?) {
        if (v == null) p.edit().remove("langs_$tab").apply() else p.edit().putStringSet("langs_$tab", v.toSet()).apply()
    }

    /** Chosen sort order per place (tab / grid). */
    fun sort(scope: String, def: String): String = p.getString("sort_$scope", def) ?: def
    fun saveSort(scope: String, v: String) { p.edit().putString("sort_$scope", v).apply() }

    /** Episode key -> its series (JSON), so "See all episodes" works from Continue watching. */
    fun saveParent(seriesItem: Item, eps: List<Item>) {
        runCatching {
            val f = File(dir, "parents.json")
            val o = if (f.exists()) JSONObject(f.readText()) else JSONObject()
            val series = o.optJSONObject("s") ?: JSONObject()
            val map = o.optJSONObject("e") ?: JSONObject()
            series.put(seriesItem.key, seriesItem.toJson())
            eps.forEach { map.put(it.key, seriesItem.key) }
            // Keep the file small: at most ~60 series
            if (series.length() > 60) {
                val keep = series.keys().asSequence().toList().takeLast(60).toSet()
                series.keys().asSequence().toList().filter { it !in keep }.forEach { series.remove(it) }
                map.keys().asSequence().toList().filter { map.optString(it) !in keep }.forEach { map.remove(it) }
            }
            f.writeText(JSONObject().put("s", series).put("e", map).toString())
        }
    }

    fun parentOf(ep: Item): Item? = runCatching {
        val f = File(dir, "parents.json")
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val sk = o.optJSONObject("e")?.optString(ep.key).orEmpty()
        o.optJSONObject("s")?.optJSONObject(sk)?.let { Item.from(it) }
    }.getOrNull()

    /** Last episode played per series (and whether it was watched to the end), for opening a show where you left off. */
    fun saveLastEp(seriesKey: String, epKey: String, done: Boolean) {
        val o = runCatching { JSONObject(p.getString("lastep", "{}") ?: "{}") }.getOrDefault(JSONObject())
        o.remove(seriesKey)
        o.put(seriesKey, JSONObject().put("e", epKey).put("d", done))
        // keep the newest ~100 shows
        val ks = o.keys().asSequence().toList()
        if (ks.size > 100) ks.take(ks.size - 100).forEach { o.remove(it) }
        p.edit().putString("lastep", o.toString()).apply()
    }

    /** (episode key, finished) or null when the show was never played. */
    fun lastEp(seriesKey: String): Pair<String, Boolean>? = runCatching {
        JSONObject(p.getString("lastep", "{}") ?: "{}").optJSONObject(seriesKey)?.let { it.getString("e") to it.optBoolean("d") }
    }.getOrNull()

    fun clearHistory() { p.edit().remove("recent").remove("resume").apply() }

    // ---------- Cloud sync ----------

    /** Called (on the writing thread) whenever synced data changes; muted while sync itself writes. */
    @Volatile var onChange: (() -> Unit)? = null
    @Volatile private var muted = false
    private val watcher = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (!muted && k != null && k !in LOCAL_ONLY) onChange?.invoke()
    }
    init { p.registerOnSharedPreferenceChangeListener(watcher); sec.registerOnSharedPreferenceChangeListener(watcher) }

    private fun prefStrings(): Map<String, String> = p.all.filter { (k, v) ->
        v is String && k !in STRUCTURED && !k.startsWith("order_") && !k.startsWith("langs_") && k !in LOCAL_ONLY
    }.mapValues { it.value as String }

    /** This device's synced data as raw values. Fixes (TMDB overrides) are added by the caller. */
    fun syncRead(withSecrets: Boolean): SyncLocal {
        val favs = runCatching { JSONArray(p.getString("fav", "[]")).let { a -> (0 until a.length()).map { a.getJSONObject(it).toString() } } }.getOrDefault(emptyList())
        val custom = runCatching {
            val o = JSONObject(p.getString("custom_cats", "{}") ?: "{}")
            o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getJSONObject(it).toString() } } }
        }.getOrDefault(emptyMap())
        val orders = p.all.keys.filter { it.startsWith("order_") }.associate { it.removePrefix("order_") to catOrder(it.removePrefix("order_")) }
        val langs = p.all.keys.filter { it.startsWith("langs_") }.mapNotNull { k -> langs(k.removePrefix("langs_"))?.let { k.removePrefix("langs_") to it } }.toMap()
        val resume = resumeArray().let { a -> (0 until a.length()).map { a.getJSONObject(it).toString() } }
        val last = runCatching { JSONObject(p.getString("lastep", "{}") ?: "{}").let { o -> o.keys().asSequence().associateWith { o.getJSONObject(it).toString() } } }.getOrDefault(emptyMap())
        val secrets = if (!withSecrets) emptyMap() else mapOf(
            "portal" to portal, "mac" to mac, "serial" to serial, "pw" to password, "tmdb" to tmdbKey, "omdb" to omdbKey
        ).filterValues { it.isNotEmpty() }
        return SyncLocal(
            favs = favs, hidden = hidden, custom = custom, orders = orders, langs = langs, prefs = prefStrings(),
            recent = p.getString("recent", "") ?: "", resume = resume, lastEp = last, secrets = secrets
        )
    }

    /** Replaces this device's synced data. Secrets are only written when [withSecrets]; they are never cleared by sync. */
    fun syncWrite(l: SyncLocal, withSecrets: Boolean): Boolean {
        muted = true
        var secretsChanged = false
        try {
            val e = p.edit()
            e.putString("fav", JSONArray().also { a -> l.favs.forEach { a.put(JSONObject(it)) } }.toString())
            e.putStringSet("hidden", l.hidden.toSet())
            e.putString("custom_cats", JSONObject().also { o -> l.custom.forEach { (k, v) -> o.put(k, JSONArray().also { a -> v.forEach { a.put(JSONObject(it)) } }) } }.toString())
            p.all.keys.filter { it.startsWith("order_") && it.removePrefix("order_") !in l.orders }.forEach { e.remove(it) }
            l.orders.forEach { (k, v) -> e.putString("order_$k", JSONArray(v).toString()) }
            p.all.keys.filter { it.startsWith("langs_") && it.removePrefix("langs_") !in l.langs }.forEach { e.remove(it) }
            l.langs.forEach { (k, v) -> e.putStringSet("langs_$k", v.toSet()) }
            prefStrings().keys.filter { it !in l.prefs }.forEach { e.remove(it) }
            l.prefs.forEach { (k, v) -> if (k !in STRUCTURED && k !in LOCAL_ONLY) e.putString(k, v) }
            if (l.recent.isEmpty()) e.remove("recent") else e.putString("recent", l.recent)
            e.putString("resume", JSONArray().also { a -> l.resume.forEach { a.put(JSONObject(it)) } }.toString())
            e.putString("lastep", JSONObject().also { o -> l.lastEp.forEach { (k, v) -> o.put(k, JSONObject(v)) } }.toString())
            e.commit()
            if (withSecrets) {
                fun put(name: String, cur: String, set: (String) -> Unit) {
                    val v = l.secrets[name]
                    if (!v.isNullOrEmpty() && v != cur) { set(v); if (name != "tmdb" && name != "omdb") secretsChanged = true }
                }
                put("portal", portal) { portal = it }
                put("mac", mac) { mac = it }
                put("serial", serial) { serial = it }
                put("pw", password) { password = it }
                put("tmdb", tmdbKey) { tmdbKey = it }
                put("omdb", omdbKey) { omdbKey = it }
            }
        } finally { muted = false }
        return secretsChanged
    }

    companion object {
        /** Prefs stored in their own sync entries. */
        val STRUCTURED = setOf("fav", "hidden", "custom_cats", "recent", "resume", "lastep")
        /** Never synced. */
        val LOCAL_ONLY = emptySet<String>()
    }

    fun cacheWrite(name: String, text: String) { runCatching { File(dir, name).writeText(text) } }
    fun cacheRead(name: String): String? =
        runCatching { File(dir, name).takeIf { it.exists() }?.readText() }.getOrNull()
}
