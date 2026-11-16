package com.rfscout.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Saves and loads the watchlist and mute list in the app's preferences. */
object MyWatchStore {
    private const val KEY = "my_watch"
    private const val KEY_MUTED = "my_muted"

    fun load(prefs: SharedPreferences) {
        MyWatch.entries.clear()
        MyWatch.muted.clear()
        try {
            val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = o.optString("p")
                if (p.length >= MyWatch.MIN_LEN) MyWatch.entries.add(MyEntry(p, o.optString("n")))
            }
            val m = JSONArray(prefs.getString(KEY_MUTED, "[]") ?: "[]")
            for (i in 0 until m.length()) MyWatch.muted.add(m.getString(i))
        } catch (_: Exception) {
            // damaged data just means empty lists
        }
    }

    fun save(prefs: SharedPreferences) {
        val arr = JSONArray()
        for (e in MyWatch.entries) arr.put(JSONObject().put("p", e.pattern).put("n", e.note))
        val m = JSONArray()
        for (k in MyWatch.muted) m.put(k)
        prefs.edit().putString(KEY, arr.toString()).putString(KEY_MUTED, m.toString()).apply()
    }
}
