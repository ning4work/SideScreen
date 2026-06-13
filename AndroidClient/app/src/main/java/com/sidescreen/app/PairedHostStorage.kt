package com.sidescreen.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

class PairedHostStorage(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("paired_host", Context.MODE_PRIVATE)

    data class Entry(val host: String, val port: Int, val token: ByteArray, val macName: String) {
        override fun equals(other: Any?): Boolean {
            if (other !is Entry) return false
            return host == other.host && port == other.port && macName == other.macName &&
                token.contentEquals(other.token)
        }

        override fun hashCode(): Int =
            ((host.hashCode() * 31 + port) * 31 + macName.hashCode()) * 31 + token.contentHashCode()

        fun toJson(): JSONObject = JSONObject().apply {
            put("host", host)
            put("port", port)
            put("token_b64", Base64.encodeToString(token, Base64.NO_WRAP or Base64.NO_PADDING))
            put("mac_name", macName)
        }

        companion object {
            fun fromJson(obj: JSONObject): Entry? {
                val host = obj.optString("host", "") .takeIf { it.isNotEmpty() } ?: return null
                val port = obj.optInt("port", -1).takeIf { it > 0 } ?: return null
                val tokenB64 = obj.optString("token_b64", "").takeIf { it.isNotEmpty() } ?: return null
                val token = try {
                    Base64.decode(tokenB64, Base64.NO_WRAP or Base64.NO_PADDING)
                } catch (e: IllegalArgumentException) {
                    return null
                }
                if (token.size != 32) return null
                val macName = obj.optString("mac_name", "Mac")
                return Entry(host, port, token, macName)
            }
        }
    }

    fun save(entry: Entry) {
        val history = loadAll().toMutableList()
        history.removeAll { it.host == entry.host && it.port == entry.port }
        history.add(0, entry)
        if (history.size > MAX_HISTORY) {
            history.subList(MAX_HISTORY, history.size).clear()
        }
        val arr = JSONArray()
        history.forEach { arr.put(it.toJson()) }
        prefs.edit()
            .putString(KEY_HISTORY, arr.toString())
            .apply()
    }

    fun load(): Entry? = loadAll().firstOrNull()

    fun loadAll(): List<Entry> {
        val json = prefs.getString(KEY_HISTORY, null)
        if (json != null) {
            return parseHistory(json)
        }
        // Migrate legacy single-entry format
        val legacy = loadLegacy()
        if (legacy != null) {
            save(legacy)
            prefs.edit()
                .remove("host").remove("port").remove("token_b64").remove("mac_name")
                .apply()
            return listOf(legacy)
        }
        return emptyList()
    }

    fun remove(entry: Entry) {
        val history = loadAll().toMutableList()
        history.removeAll { it.host == entry.host && it.port == entry.port }
        val arr = JSONArray()
        history.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun parseHistory(json: String): List<Entry> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { Entry.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadLegacy(): Entry? {
        val host = prefs.getString("host", null) ?: return null
        val port = prefs.getInt("port", -1).takeIf { it > 0 } ?: return null
        val tokenB64 = prefs.getString("token_b64", null) ?: return null
        val token = try {
            Base64.decode(tokenB64, Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (token.size != 32) return null
        val macName = prefs.getString("mac_name", null) ?: "Mac"
        return Entry(host, port, token, macName)
    }

    companion object {
        private const val KEY_HISTORY = "history_json"
        private const val MAX_HISTORY = 10
    }
}
