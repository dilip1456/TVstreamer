package com.dilip.stbplus

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import java.io.File

class Store(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("data", Context.MODE_PRIVATE)
    private val dir: File = ctx.filesDir

    private val sec: SharedPreferences = try {
        val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "secure", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences("secure_fallback", Context.MODE_PRIVATE)
    }

    var portal: String
        get() = sec.getString("portal", "") ?: ""
        set(v) { sec.edit().putString("portal", v.trim()).apply() }

    var mac: String
        get() = sec.getString("mac", "") ?: ""
        set(v) { sec.edit().putString("mac", v.trim()).apply() }

    var password: String
        get() = sec.getString("pw", "") ?: ""
        set(v) { sec.edit().putString("pw", v).apply() }

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

    fun clearHistory() { p.edit().remove("recent").remove("resume").apply() }

    fun cacheWrite(name: String, text: String) { runCatching { File(dir, name).writeText(text) } }
    fun cacheRead(name: String): String? =
        runCatching { File(dir, name).takeIf { it.exists() }?.readText() }.getOrNull()
}
