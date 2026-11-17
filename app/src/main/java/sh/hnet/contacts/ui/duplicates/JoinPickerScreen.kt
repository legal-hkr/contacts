@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.contacts.ui.duplicates

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.model.ListSettings
import sh.hnet.contacts.model.Search
import sh.hnet.contacts.model.SearchContact
import sh.hnet.contacts.model.SearchResult
import sh.hnet.contacts.ui.components.Avatar
import sh.hnet.contacts.ui.contacts.SearchField

/**
 * Join with another contact (SPEC §9.6): a search page like the list's – the field focused,
 * "Join with: …" under it, the contact itself left out; a tap opens the review in manual mode.
 */
@Composable
fun JoinPickerScreen(contactId: Long, name: String, onBack: () -> Unit, onPick: (Long) -> Unit) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    val field = rememberTextFieldState()
    val hidden by app.preferences.hiddenAccounts.collectAsStateWithLifecycle(emptySet())
    val index by produceState<List<SearchContact>>(emptyList(), hidden) { value = runCatching { app.contacts.searchIndex(ListSettings(), hidden) }.getOrDefault(emptyList()).filter { it.row.id != contactId } }
    val results by produceState<List<SearchResult>>(emptyList(), index) {
        snapshotFlow { field.text.toString() }.collectLatest { text ->
            value = if (text.isBlank()) index.filter { it.shown }.sortedBy { it.row.sortName.lowercase() }.map { SearchResult(it.row, true, null, it.row.name, null) }
            else Search.find(index, text, app.contacts.countryCode(), all = false)
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        SearchField(field, field.text.toString(), onClose = onBack, onClear = { field.clearText() })
        Text(stringResource(R.string.label_join_with, name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.contact.id }) { result ->
                ListItem(onClick = { onPick(result.contact.id) }, leadingContent = { Avatar(result.contact.name, result.contact.lookupKey, result.contact.photo, 40.dp) }) {
                    Text(result.contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
