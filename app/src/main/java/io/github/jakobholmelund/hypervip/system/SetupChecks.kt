package io.github.jakobholmelund.hypervip.system

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import io.github.jakobholmelund.hypervip.alert.VipAlerts
import io.github.jakobholmelund.hypervip.data.AppSettings
import io.github.jakobholmelund.hypervip.data.SettingsStore
import io.github.jakobholmelund.hypervip.notify.CallWatcherService
import io.github.jakobholmelund.hypervip.notify.VipNotificationListener

enum class Need { REQUIRED, RECOMMENDED, OPTIONAL }

data class SetupItem(
    val key: String,
    val title: String,
    val description: String,
    val need: Need,
    val done: Boolean,
    /** Runtime permissions to request; when empty, [action] opens a settings screen instead. */
    val permissions: List<String> = emptyList(),
    val actionLabel: String = "Allow",
    val action: ((Context) -> Unit)? = null,
    val secondaryLabel: String? = null,
    val secondaryAction: ((Context) -> Unit)? = null,
)

object SetupChecks {
    fun evaluate(context: Context, settings: AppSettings): List<SetupItem> {
        val nm = context.getSystemService(NotificationManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val items = mutableListOf<SetupItem>()

        items += SetupItem(
            key = "contacts",
            title = "Contacts",
            description = "To pick your VIPs and recognise them when they call or message.",
            need = Need.REQUIRED,
            done = granted(context, Manifest.permission.READ_CONTACTS),
            permissions = listOf(Manifest.permission.READ_CONTACTS),
        )
        items += SetupItem(
            key = "phone",
            title = "Phone and call logs",
            description = "Android only tells an app who is calling if it has call log access.",
            need = Need.REQUIRED,
            done = granted(context, Manifest.permission.READ_PHONE_STATE) &&
                granted(context, Manifest.permission.READ_CALL_LOG),
            permissions = listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            items += SetupItem(
                key = "notifications",
                title = "Notifications",
                description = "Shows who is calling while a VIP rings, with a button to silence it.",
                need = Need.REQUIRED,
                done = VipAlerts.canNotify(context),
                permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
            )
        }
        items += SetupItem(
            key = "battery",
            title = "Battery: no restrictions",
            description = "Lets Hyper VIP start ringing when a VIP calls; otherwise HyperOS may block it. " +
                "It doesn't cost battery: Hyper VIP runs nothing in the background and only wakes up when a " +
                "call or notification arrives. If the first button does nothing, use “HyperOS battery " +
                "saver” and pick “No restrictions”.",
            need = Need.REQUIRED,
            done = power.isIgnoringBatteryOptimizations(context.packageName),
            action = SystemSettings::requestBatteryExemption,
            secondaryLabel = if (SystemSettings.isXiaomi) "HyperOS battery saver" else null,
            secondaryAction = if (SystemSettings.isXiaomi) SystemSettings::openHyperOsBattery else null,
        )
        if (SystemSettings.isXiaomi) {
            items += SetupItem(
                key = "autostart",
                title = "Autostart",
                description = "HyperOS only delivers incoming-call events to apps with Autostart on. " +
                    "Android can't report this setting, so confirm it once you've switched it on.",
                need = Need.REQUIRED,
                done = settings.autostartConfirmed,
                actionLabel = "Open",
                action = SystemSettings::openAutostart,
                secondaryLabel = "It's on",
                secondaryAction = { ctx -> SettingsStore.update(ctx) { it.copy(autostartConfirmed = true) } },
            )
        }
        items += SetupItem(
            key = "listener",
            title = "Notification access",
            description = "Alerts you to VIPs' messages and stops a VIP ring when a call app reports a " +
                "missed call. It also keeps Hyper VIP running reliably on HyperOS.",
            need = Need.RECOMMENDED,
            done = nm.isNotificationListenerAccessGranted(ComponentName(context, VipNotificationListener::class.java)),
            action = SystemSettings::openNotificationAccess,
        )
        items += SetupItem(
            key = "accessibility",
            title = "Accessibility: backup for WhatsApp calls",
            description = "Only turn this on if VIP calls in WhatsApp, Signal or Telegram don't ring " +
                "without it. Some banking apps refuse to open while any accessibility service is on. " +
                "Recent activity shows which route spotted each call. To turn it on: Accessibility → " +
                "Downloaded apps → Hyper VIP call watcher (if greyed out, App info → ⋮ → Allow " +
                "restricted settings first).",
            need = Need.OPTIONAL,
            done = CallWatcherService.isEnabled(context),
            actionLabel = "Open",
            action = SystemSettings::openAccessibility,
        )
        items += SetupItem(
            key = "dnd",
            title = "Do Not Disturb access",
            description = "Only needed if your Do Not Disturb blocks alarms or uses Total silence. " +
                "Hyper VIP then lets alarms through for the few seconds a VIP rings.",
            need = Need.OPTIONAL,
            done = nm.isNotificationPolicyAccessGranted,
            action = SystemSettings::openDndAccess,
        )
        return items
    }

    fun missingRequired(items: List<SetupItem>): Int = items.count { it.need == Need.REQUIRED && !it.done }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
