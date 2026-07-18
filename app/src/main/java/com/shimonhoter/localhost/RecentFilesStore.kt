package com.shimonhoter.localhost

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object RecentFilesStore {
    private const val PREFS = "localhost_prefs"
    private const val KEY_HISTORY = "history"
    private const val KEY_FAVORITES = "favorites"
    private const val MAX_HISTORY = 50

    data class Entry(val uri: String, val displayName: String, val timestamp: Long)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readList(context: Context, key: String): MutableList<Entry> {
        val raw = prefs(context).getString(key, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<Entry>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                list.add(Entry(o.getString("uri"), o.getString("name"), o.optLong("ts", 0L)))
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun writeList(context: Context, key: String, list: List<Entry>) {
        val array = JSONArray()
        list.forEach { e ->
            array.put(JSONObject().apply {
                put("uri", e.uri)
                put("name", e.displayName)
                put("ts", e.timestamp)
            })
        }
        prefs(context).edit().putString(key, array.toString()).apply()
    }

    fun addHistory(context: Context, uri: String, displayName: String) {
        val list = readList(context, KEY_HISTORY)
        list.removeAll { it.uri == uri }
        list.add(0, Entry(uri, displayName, System.currentTimeMillis()))
        writeList(context, KEY_HISTORY, list.take(MAX_HISTORY))
    }

    fun getHistory(context: Context): List<Entry> = readList(context, KEY_HISTORY)

    fun getFavorites(context: Context): List<Entry> = readList(context, KEY_FAVORITES)

    fun isFavorite(context: Context, uri: String): Boolean =
        readList(context, KEY_FAVORITES).any { it.uri == uri }

    /** Toggles favorite state for [uri] and returns the new state (true = now favorited). */
    fun toggleFavorite(context: Context, uri: String, displayName: String): Boolean {
        val list = readList(context, KEY_FAVORITES)
        val existing = list.indexOfFirst { it.uri == uri }
        return if (existing >= 0) {
            list.removeAt(existing)
            writeList(context, KEY_FAVORITES, list)
            false
        } else {
            list.add(0, Entry(uri, displayName, System.currentTimeMillis()))
            writeList(context, KEY_FAVORITES, list)
            true
        }
    }
}
