package io.github.jakobholmelund.hypervip.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.PhoneLookup
import androidx.core.content.ContextCompat
import io.github.jakobholmelund.hypervip.match.PhoneNumbers

data class PhoneContact(
    val contactId: Long,
    val lookupKey: String,
    val name: String,
    val starred: Boolean,
    val numbers: List<String>,
)

object ContactsRepository {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Every contact that has at least one phone number, sorted by name. */
    fun loadAll(context: Context): List<PhoneContact> {
        if (!hasPermission(context)) return emptyList()
        val projection = arrayOf(
            Phone.CONTACT_ID,
            Phone.LOOKUP_KEY,
            Phone.DISPLAY_NAME_PRIMARY,
            Phone.STARRED,
            Phone.NUMBER,
        )
        val byId = LinkedHashMap<Long, PhoneContact>()
        runCatching {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val number = c.getString(4)?.takeIf { it.isNotBlank() } ?: continue
                    val existing = byId[id]
                    if (existing == null) {
                        byId[id] = PhoneContact(
                            contactId = id,
                            lookupKey = c.getString(1).orEmpty(),
                            name = c.getString(2)?.takeIf { it.isNotBlank() } ?: number,
                            starred = c.getInt(3) == 1,
                            numbers = listOf(number),
                        )
                    } else if (existing.numbers.none { PhoneNumbers.matches(it, number) }) {
                        byId[id] = existing.copy(numbers = existing.numbers + number)
                    }
                }
            }
        }
        return byId.values.toList()
    }

    /**
     * The contacts that own [number], as (contact id, lookup key) pairs. Uses the same lookup the
     * dialer uses for caller ID, so it copes with every number format the phone itself understands.
     */
    fun lookupByNumber(context: Context, number: String): List<Pair<Long, String>> {
        if (!hasPermission(context) || number.isBlank()) return emptyList()
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val owners = mutableListOf<Pair<Long, String>>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(PhoneLookup.CONTACT_ID, PhoneLookup.LOOKUP_KEY),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) owners += c.getLong(0) to c.getString(1).orEmpty()
            }
        }
        return owners
    }
}
