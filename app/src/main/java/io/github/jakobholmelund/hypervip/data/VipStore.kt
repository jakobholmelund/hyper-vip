package io.github.jakobholmelund.hypervip.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class VipContact(
    val lookupKey: String,
    val contactId: Long,
    val name: String,
    /** Copied from the contact so VIPs still match if contacts access is lost later. */
    val numbers: List<String>,
) {
    fun isSameContact(lookupKey: String, contactId: Long): Boolean =
        (this.lookupKey.isNotEmpty() && this.lookupKey == lookupKey) || this.contactId == contactId
}

object VipStore {
    private const val PREFS = "vips"
    private const val KEY_CONTACTS = "contacts"
    private var state: MutableStateFlow<List<VipContact>>? = null

    fun flow(context: Context): StateFlow<List<VipContact>> = state(context)

    fun get(context: Context): List<VipContact> = state(context).value

    fun add(context: Context, vip: VipContact) = update(context) { list ->
        if (list.any { it.isSameContact(vip.lookupKey, vip.contactId) }) list
        else (list + vip).sortedBy { it.name.lowercase() }
    }

    fun remove(context: Context, vip: VipContact) = update(context) { list ->
        list.filterNot { it.isSameContact(vip.lookupKey, vip.contactId) }
    }

    /** Picks up renamed contacts and edited numbers. VIPs that can't be found are kept as they are. */
    fun refresh(context: Context, contacts: List<PhoneContact>) = update(context) { list ->
        list.map { vip ->
            val fresh = contacts.firstOrNull { vip.isSameContact(it.lookupKey, it.contactId) }
                ?: return@map vip
            vip.copy(
                lookupKey = fresh.lookupKey,
                contactId = fresh.contactId,
                name = fresh.name,
                numbers = fresh.numbers,
            )
        }.sortedBy { it.name.lowercase() }
    }

    @Synchronized
    private fun update(context: Context, transform: (List<VipContact>) -> List<VipContact>) {
        val flow = state(context)
        val next = transform(flow.value)
        if (next == flow.value) return
        prefs(context).edit { putString(KEY_CONTACTS, encode(next)) }
        flow.value = next
    }

    @Synchronized
    private fun state(context: Context): MutableStateFlow<List<VipContact>> =
        state ?: MutableStateFlow(decode(prefs(context).getString(KEY_CONTACTS, null))).also { state = it }

    private fun encode(list: List<VipContact>): String {
        val array = JSONArray()
        for (vip in list) {
            array.put(
                JSONObject()
                    .put("lookupKey", vip.lookupKey)
                    .put("contactId", vip.contactId)
                    .put("name", vip.name)
                    .put("numbers", JSONArray(vip.numbers)),
            )
        }
        return array.toString()
    }

    private fun decode(json: String?): List<VipContact> {
        if (json == null) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                val numbers = o.getJSONArray("numbers")
                VipContact(
                    lookupKey = o.optString("lookupKey"),
                    contactId = o.optLong("contactId", -1),
                    name = o.optString("name"),
                    numbers = List(numbers.length()) { numbers.getString(it) },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
