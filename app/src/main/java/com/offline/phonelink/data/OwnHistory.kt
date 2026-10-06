package com.offline.phonelink.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Calls seen by this app, kept on the player (the phone's own history may not be available). */
object OwnHistory {
    private const val PREFS = "history"
    private const val KEY = "calls"
    private const val MAX = 200

    fun load(context: Context): List<HistoryEntry> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    number = o.getString("n"),
                    name = o.optString("name").ifEmpty { null },
                    direction = CallDirection.valueOf(o.getString("d")),
                    time = o.getLong("t"),
                    durationSec = o.optLong("s"),
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(context: Context, entry: HistoryEntry) {
        val all = (listOf(entry) + load(context)).take(MAX)
        val arr = JSONArray()
        for (e in all) {
            arr.put(
                JSONObject()
                    .put("n", e.number)
                    .put("name", e.name ?: "")
                    .put("d", e.direction.name)
                    .put("t", e.time)
                    .put("s", e.durationSec),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
