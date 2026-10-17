package io.github.jakobholmelund.hypervip.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jakobholmelund.hypervip.alert.AudioState
import io.github.jakobholmelund.hypervip.alert.VipAlerts
import io.github.jakobholmelund.hypervip.data.AppSettings
import io.github.jakobholmelund.hypervip.data.ContactsRepository
import io.github.jakobholmelund.hypervip.data.EventLog
import io.github.jakobholmelund.hypervip.data.LogEntry
import io.github.jakobholmelund.hypervip.data.SettingsStore
import io.github.jakobholmelund.hypervip.data.VipContact
import io.github.jakobholmelund.hypervip.data.VipStore
import io.github.jakobholmelund.hypervip.notify.OngoingVisibility
import io.github.jakobholmelund.hypervip.notify.VipNotificationListener
import io.github.jakobholmelund.hypervip.system.Need
import io.github.jakobholmelund.hypervip.system.SetupChecks
import io.github.jakobholmelund.hypervip.system.SetupItem
import io.github.jakobholmelund.hypervip.system.SystemSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onAddVips: () -> Unit) {
    val context = LocalContext.current
    val settings by SettingsStore.flow(context).collectAsStateWithLifecycle()
    val vips by VipStore.flow(context).collectAsStateWithLifecycle()
    val log by EventLog.flow(context).collectAsStateWithLifecycle()

    // Permissions and system settings change outside the app, so re-check every time we're resumed.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        VipNotificationListener.ensureBound(context)
        resumes++
        onPauseOrDispose { }
    }
    // The log changes whenever an alert or test ring ends, which can change setup results too.
    val setup = remember(resumes, settings, log) { SetupChecks.evaluate(context, settings) }
    val sound = remember(resumes, log) { readSoundState(context) }

    LaunchedEffect(resumes) {
        if (ContactsRepository.hasPermission(context)) {
            val contacts = withContext(Dispatchers.IO) { ContactsRepository.loadAll(context) }
            VipStore.refresh(context, contacts)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        resumes++
        val activity = context as? Activity ?: return@rememberLauncherForActivityResult
        // Once Android stops showing the prompt, the app's settings page is the only way left.
        val blocked = result.filter { (permission, granted) ->
            !granted && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
        if (blocked.isNotEmpty()) SystemSettings.openAppDetails(context)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Hyper VIP") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { StatusCard(settings, SetupChecks.missingRequired(setup), vips.size) }
            item { SetupCard(setup) { permissionLauncher.launch(it.toTypedArray()) } }
            item { VipCard(vips, onAddVips) }
            item { AlertsCard(settings) }
            item { SoundStateCard(sound) }
            item { ActivityCard(log) }
        }
    }
}

@Composable
private fun StatusCard(settings: AppSettings, missingSteps: Int, vipCount: Int) {
    val context = LocalContext.current
    val ready = settings.enabled && missingSteps == 0 && vipCount > 0
    val (title, detail) = when {
        !settings.enabled -> "Paused" to "VIPs ring like everyone else while this is off."
        missingSteps > 0 -> "Finish setup" to "${plural(missingSteps, "required step")} left below."
        vipCount == 0 -> "Add your VIPs" to "Pick the people who should always get through."
        else -> "Active" to "${plural(vipCount, "VIP")} will ring through silent mode and Do Not Disturb."
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(
                checked = settings.enabled,
                onCheckedChange = { on -> SettingsStore.update(context) { it.copy(enabled = on) } },
            )
        }
    }
}

@Composable
private fun SetupCard(items: List<SetupItem>, onRequestPermissions: (List<String>) -> Unit) {
    val context = LocalContext.current
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    val completed = items.count { it.done }
    val shown = if (showCompleted) items else items.filterNot { it.done }
    SectionCard(title = "Setup") {
        if (shown.isEmpty()) {
            Text(
                "Everything is set up.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        shown.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider()
            SetupRow(item, onRequestPermissions)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (completed > 0) {
                TextButton(onClick = { showCompleted = !showCompleted }) {
                    Text(if (showCompleted) "Hide completed" else "Show completed ($completed)")
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { SystemSettings.openAppDetails(context) }) { Text("App info") }
        }
        Text(
            "If HyperOS greys out a switch as a “Restricted setting”, open App info, tap ⋮ and choose " +
                "“Allow restricted settings”, then try again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SetupRow(item: SetupItem, onRequestPermissions: (List<String>) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Icon(
            imageVector = when {
                item.done -> Icons.Filled.CheckCircle
                item.need == Need.REQUIRED -> Icons.Filled.Warning
                else -> Icons.Filled.Info
            },
            contentDescription = null,
            tint = when {
                item.done -> MaterialTheme.colorScheme.primary
                item.need == Need.REQUIRED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val need = when (item.need) {
                Need.REQUIRED -> ""
                Need.RECOMMENDED -> " · recommended"
                Need.OPTIONAL -> " · optional"
            }
            Text(item.title + need, style = MaterialTheme.typography.titleSmall)
            Text(
                item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!item.done) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        if (item.permissions.isNotEmpty()) onRequestPermissions(item.permissions)
                        else item.action?.invoke(context)
                    }) { Text(item.actionLabel) }
                    if (item.secondaryLabel != null && item.secondaryAction != null) {
                        OutlinedButton(onClick = { item.secondaryAction.invoke(context) }) { Text(item.secondaryLabel) }
                    }
                }
            }
        }
    }
}

@Composable
private fun VipCard(vips: List<VipContact>, onAdd: () -> Unit) {
    val context = LocalContext.current
    SectionCard(
        title = "VIPs",
        action = {
            TextButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
        },
    ) {
        if (vips.isEmpty()) {
            Text(
                "No VIPs yet. Add the people who should always be able to reach you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        vips.forEach { vip ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(vip.name)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(vip.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        vip.numbers.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { VipStore.remove(context, vip) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove ${vip.name}")
                }
            }
        }
    }
}

@Composable
private fun AlertsCard(settings: AppSettings) {
    val context = LocalContext.current
    var volume by remember(settings.alertVolumePercent) { mutableFloatStateOf(settings.alertVolumePercent.toFloat()) }
    SectionCard(title = "Alerts") {
        Text("VIP alert volume: ${volume.roundToInt()}%", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = volume,
            onValueChange = { volume = it },
            onValueChangeFinished = {
                SettingsStore.update(context) { it.copy(alertVolumePercent = volume.roundToInt()) }
            },
            valueRange = 10f..100f,
            steps = 8,
        )
        Text(
            "VIP alerts play on the alarm channel, which silent mode doesn't mute. If your alarm volume " +
                "is lower than this, it's raised while a VIP rings and put back afterwards.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            title = "Calls in other apps",
            description = "WhatsApp, Signal, Telegram and other calling apps. Needs notification access.",
            checked = settings.appCallAlerts,
        ) { on -> SettingsStore.update(context) { it.copy(appCallAlerts = on) } }
        SwitchRow(
            title = "Messages",
            description = "Play a sound when a VIP messages you. Needs notification access.",
            checked = settings.messageAlerts,
        ) { on -> SettingsStore.update(context) { it.copy(messageAlerts = on) } }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { VipAlerts.testRing(context) }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Test ring")
            }
            OutlinedButton(onClick = { VipAlerts.messageAlert(context, "Test", isTest = true) }) {
                Text("Test message")
            }
        }
        Spacer(Modifier.height(8.dp))
        RingtoneRow(settings)
    }
}

@Composable
private fun RingtoneRow(settings: AppSettings) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val picked = result.data?.let {
            IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        }
        // "Default" means follow the phone's ringtone, which is what no choice means too.
        val choice = picked?.takeIf { it != Settings.System.DEFAULT_RINGTONE_URI }
        SettingsStore.update(context) { it.copy(ringtoneUri = choice) }
    }
    val name = remember(settings.ringtoneUri) { ringtoneName(context, settings.ringtoneUri) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("VIP ringtone", style = MaterialTheme.typography.bodyLarge)
            Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "VIP ringtone")
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_RINGTONE_URI)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(
                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                    settings.ringtoneUri ?: Settings.System.DEFAULT_RINGTONE_URI,
                )
            runCatching { picker.launch(intent) }
        }) { Text("Change") }
    }
}

private fun ringtoneName(context: Context, uri: Uri?): String {
    if (uri == null) return "Same as your phone's ringtone"
    return runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull() ?: "Custom sound"
}

private data class SoundState(
    val ringer: String,
    val doNotDisturb: String,
    val alarmsAllowed: Boolean,
    val dndAccess: Boolean,
    val alarmVolume: Int,
    val alarmMax: Int,
    val listenerRunning: Boolean,
    val seesAppCalls: Boolean?,
)

private fun readSoundState(context: Context): SoundState {
    val am = context.getSystemService(AudioManager::class.java)
    val nm = context.getSystemService(NotificationManager::class.java)
    return SoundState(
        ringer = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "Silent"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
            else -> "Normal"
        },
        doNotDisturb = when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "Off"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "On (priority only)"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "On (alarms only)"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "On (total silence)"
            else -> "Unknown"
        },
        alarmsAllowed = AudioState.alarmsAllowed(nm),
        dndAccess = nm.isNotificationPolicyAccessGranted,
        alarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM),
        alarmMax = am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
        listenerRunning = VipNotificationListener.connected,
        seesAppCalls = OngoingVisibility.result(context),
    )
}

@Composable
private fun SoundStateCard(sound: SoundState) {
    SectionCard(title = "Phone sound right now") {
        InfoRow("Ringer", sound.ringer)
        InfoRow("Do Not Disturb", sound.doNotDisturb)
        InfoRow(
            "Alarms get through",
            when {
                sound.alarmsAllowed -> "Yes"
                sound.dndAccess -> "No — allowed while a VIP rings"
                else -> "No — grant Do Not Disturb access"
            },
        )
        InfoRow("Alarm volume", "${sound.alarmVolume} of ${sound.alarmMax}")
        InfoRow("Notification access", if (sound.listenerRunning) "Running" else "Not running")
        InfoRow(
            "Sees WhatsApp-style calls",
            when (sound.seesAppCalls) {
                true -> "Yes"
                false -> "No — see Setup"
                null -> "Tap Test ring to check"
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActivityCard(log: List<LogEntry>) {
    val context = LocalContext.current
    SectionCard(
        title = "Recent activity",
        action = {
            if (log.isNotEmpty()) TextButton(onClick = { EventLog.clear(context) }) { Text("Clear") }
        },
    ) {
        if (log.isEmpty()) {
            Text(
                "Nothing yet. Calls and messages from VIPs show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        log.take(15).forEach { entry ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Text(
                    DateFormat.format("d MMM HH:mm", entry.time).toString(),
                    modifier = Modifier.width(96.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(entry.message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun plural(count: Int, word: String) = if (count == 1) "1 $word" else "$count ${word}s"
