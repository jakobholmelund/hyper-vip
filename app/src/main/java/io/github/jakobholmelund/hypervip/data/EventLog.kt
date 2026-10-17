package io.github.jakobholmelund.hypervip.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class LogEntry(val time: Long, val message: String)

/** A short on-device history of what Hyper VIP saw and did, shown in the app for troubleshooting. */
object EventLog {
    private const val TAG = "HyperVip"
    private const val PREFS = "event_log"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 40
    private var state: MutableStateFlow<List<LogEntry>>? = null

    fun flow(context: Context): StateFlow<List<LogEntry>> = state(context)

    @Synchronized
    fun log(context: Context, message: String) {
        Log.i(TAG, message)
        val flow = state(context)
        val next = (listOf(LogEntry(System.currentTimeMillis(), message)) + flow.value).take(MAX_ENTRIES)
        save(context, next)
        flow.value = next
    }

    @Synchronized
    fun clear(context: Context) {
        save(context, emptyList())
        state(context).value = emptyList()
    }

    @Synchronized
    private fun state(context: Context): MutableStateFlow<List<LogEntry>> =
        state ?: MutableStateFlow(load(context)).also { state = it }

    private fun save(context: Context, entries: List<LogEntry>) {
        val array = JSONArray()
        for (entry in entries) array.put(JSONObject().put("t", entry.time).put("m", entry.message))
        prefs(context).edit { putString(KEY_ENTRIES, array.toString()) }
    }

    private fun load(context: Context): List<LogEntry> {
        val json = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                LogEntry(o.getLong("t"), o.getString("m"))
            }
        }.getOrDefault(emptyList())
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
