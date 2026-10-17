package io.github.jakobholmelund.hypervip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jakobholmelund.hypervip.data.ContactsRepository
import io.github.jakobholmelund.hypervip.data.PhoneContact
import io.github.jakobholmelund.hypervip.data.VipContact
import io.github.jakobholmelund.hypervip.data.VipStore
import io.github.jakobholmelund.hypervip.match.PhoneNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vips by VipStore.flow(context).collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val contacts by produceState<List<PhoneContact>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { ContactsRepository.loadAll(context) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose VIPs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search contacts") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
            )
            val all = contacts
            when {
                all == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                !ContactsRepository.hasPermission(context) ->
                    Message("Allow contacts access on the main screen first.")
                all.isEmpty() -> Message("No contacts with phone numbers found.")
                else -> ContactList(all, vips, query)
            }
        }
    }
}

@Composable
private fun ContactList(contacts: List<PhoneContact>, vips: List<VipContact>, query: String) {
    val filtered = remember(contacts, query) { search(contacts, query) }
    val starred = if (query.isBlank()) filtered.filter { it.starred } else emptyList()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (starred.isNotEmpty()) {
            item(key = "header-starred") { Header("Starred") }
            items(starred, key = { "starred-${it.contactId}" }) { ContactRow(it, vips) }
            item(key = "header-all") { Header("All contacts") }
        }
        items(filtered, key = { "all-${it.contactId}" }) { ContactRow(it, vips) }
    }
}

@Composable
private fun ContactRow(contact: PhoneContact, vips: List<VipContact>) {
    val context = LocalContext.current
    val vip = vips.firstOrNull { it.isSameContact(contact.lookupKey, contact.contactId) }
    val toggle = {
        if (vip != null) VipStore.remove(context, vip)
        else VipStore.add(context, VipContact(contact.lookupKey, contact.contactId, contact.name, contact.numbers))
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = toggle).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(contact.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(contact.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                contact.numbers.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked = vip != null, onCheckedChange = { toggle() })
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

private fun search(contacts: List<PhoneContact>, query: String): List<PhoneContact> {
    val q = query.trim()
    if (q.isEmpty()) return contacts
    val digits = PhoneNumbers.normalize(q).trimStart('+')
    return contacts.filter { contact ->
        contact.name.contains(q, ignoreCase = true) ||
            (digits.length >= 3 && contact.numbers.any { PhoneNumbers.normalize(it).contains(digits) })
    }
}
