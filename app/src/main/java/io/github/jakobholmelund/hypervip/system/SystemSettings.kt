package io.github.jakobholmelund.hypervip.system

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.jakobholmelund.hypervip.R
import io.github.jakobholmelund.hypervip.alert.VipAlerts
import io.github.jakobholmelund.hypervip.notify.VipNotificationListener
import java.util.Locale

/** Opens the system screens Hyper VIP needs, with HyperOS-specific pages where they exist. */
object SystemSettings {
    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.lowercase(Locale.ROOT) in setOf("xiaomi", "redmi", "poco")

    fun openAppDetails(context: Context) =
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))

    fun openDndAccess(context: Context) =
        start(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))

    fun openNotificationAccess(context: Context) = start(
        context,
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, VipNotificationListener::class.java).flattenToString(),
        ),
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
    )

    @SuppressLint("BatteryLife") // Sideloaded app whose whole job is reacting to calls in the background.
    fun requestBatteryExemption(context: Context) = start(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)),
        hyperOsBatteryIntent(context),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    /** HyperOS's per-app battery saver page, where "No restrictions" lives. */
    fun openHyperOsBattery(context: Context) = start(
        context,
        hyperOsBatteryIntent(context),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)),
    )

    fun openAutostart(context: Context) = start(
        context,
        Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)),
    )

    fun openCallAlertSettings(context: Context) = start(
        context,
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, VipAlerts.CHANNEL_CALLS),
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    private fun hyperOsBatteryIntent(context: Context) = Intent()
        .setClassName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
        .putExtra("package_name", context.packageName)
        .putExtra("package_label", context.getString(R.string.app_name))

    private fun packageUri(context: Context) = Uri.fromParts("package", context.packageName, null)

    /** Starts the first intent the system can handle. */
    private fun start(context: Context, vararg intents: Intent) {
        for (intent in intents) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}
