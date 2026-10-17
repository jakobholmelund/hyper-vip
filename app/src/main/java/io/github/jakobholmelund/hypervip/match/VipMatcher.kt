package io.github.jakobholmelund.hypervip.match

import android.app.Notification
import android.app.Person
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import androidx.core.os.BundleCompat
import io.github.jakobholmelund.hypervip.data.ContactsRepository
import io.github.jakobholmelund.hypervip.data.VipContact
import io.github.jakobholmelund.hypervip.data.VipStore

object VipMatcher {
    // Public string values of Notification.EXTRA_CALL_PERSON; not every SDK exposes the constant.
    private const val EXTRA_CALL_PERSON = "android.callPerson"

    fun byNumber(context: Context, number: String?): VipContact? {
        if (number.isNullOrBlank()) return null
        val vips = VipStore.get(context)
        if (vips.isEmpty()) return null
        val owners = ContactsRepository.lookupByNumber(context, number)
        vips.firstOrNull { vip -> owners.any { (id, key) -> vip.isSameContact(key, id) } }?.let { return it }
        return vips.firstOrNull { vip -> vip.numbers.any { PhoneNumbers.matches(it, number) } }
    }

    /**
     * Finds the VIP a notification is from, using (in order) the caller of a CallStyle
     * notification, the sender of the latest message, the people attached to the notification,
     * and finally the title. Group conversations never match.
     */
    fun byNotification(context: Context, notification: Notification): VipContact? {
        val vips = VipStore.get(context)
        if (vips.isEmpty()) return null
        val extras = notification.extras
        val candidates = mutableListOf<Pair<String?, CharSequence?>>()

        BundleCompat.getParcelable(extras, EXTRA_CALL_PERSON, Person::class.java)?.let {
            candidates += it.uri to it.name
        }
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        if (style != null) {
            if (style.isGroupConversation) return null
            style.messages.lastOrNull()?.person?.let { candidates += it.uri to it.name }
        }
        BundleCompat.getParcelableArrayList(extras, Notification.EXTRA_PEOPLE_LIST, Person::class.java)
            ?.forEach { candidates += it.uri to it.name }
        candidates += null to extras.getCharSequence(Notification.EXTRA_TITLE)

        for ((uri, name) in candidates) {
            if (uri != null) byContactUri(context, vips, uri)?.let { return it }
            if (name != null) {
                vips.firstOrNull { Names.matches(it.name, name) }?.let { return it }
                if (PhoneNumbers.looksLikeNumber(name)) byNumber(context, name.toString())?.let { return it }
            }
        }
        return null
    }

    /** Handles tel: URIs and contacts-provider URIs such as content://com.android.contacts/contacts/lookup/KEY/ID. */
    private fun byContactUri(context: Context, vips: List<VipContact>, raw: String): VipContact? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        return when (uri.scheme) {
            "tel" -> byNumber(context, uri.schemeSpecificPart)
            "content" -> {
                if (uri.authority != ContactsContract.AUTHORITY) return null
                val segments = uri.pathSegments
                val lookupIndex = segments.indexOf("lookup")
                val key: String
                val id: Long?
                if (lookupIndex >= 0 && lookupIndex + 1 < segments.size) {
                    key = segments[lookupIndex + 1]
                    id = segments.getOrNull(lookupIndex + 2)?.toLongOrNull()
                } else {
                    key = ""
                    id = segments.lastOrNull()?.toLongOrNull()
                }
                vips.firstOrNull { it.isSameContact(key, id ?: Long.MIN_VALUE) }
            }
            else -> null
        }
    }
}
