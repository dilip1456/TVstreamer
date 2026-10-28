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

    /** Player picture mode: fit, fill or zoom. */
    var aspect: String
        get() = p.getString("aspect", "fit") ?: "fit"
        set(v) { p.edit().putString("aspect", v).apply() }

    /** Where the player's option bar sits: bottom, left or right. */
    var barPos: String
        get() = p.getString("bar_pos", "bottom") ?: "bottom"
        set(v) { p.edit().putString("bar_pos", v).apply() }

    fun clearHistory() { p.edit().remove("recent").remove("resume").apply() }

    fun cacheWrite(name: String, text: String) { runCatching { File(dir, name).writeText(text) } }
    fun cacheRead(name: String): String? =
        runCatching { File(dir, name).takeIf { it.exists() }?.readText() }.getOrNull()
}
