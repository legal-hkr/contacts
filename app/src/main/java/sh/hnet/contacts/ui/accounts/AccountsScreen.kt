@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.data.AccountInfo
import sh.hnet.contacts.data.Moved
import sh.hnet.contacts.ui.components.RowIcon
import sh.hnet.contacts.ui.components.SettingsGroup
import sh.hnet.contacts.ui.components.SettingsPage
import sh.hnet.contacts.ui.components.settingsRowColors

/**
 * Settings › Accounts (SPEC §8.1–§8.3): every account that has contacts or could take them, with
 * the switch that shows its contacts in the list, read-only ones marked; then "On this phone" –
 * local contacts, always listed –, "Move to an account" for all of them, and the note that the
 * vCard export is their only copy. Read again when the storage changes.
 */
@OptIn(FlowPreview::class)
@Composable
fun AccountsScreen(onBack: () -> Unit, onMoved: (Moved, String) -> Unit, onExport: () -> Unit) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<AccountInfo>?>(null) }
    var targets by remember { mutableStateOf<List<AccountInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        app.contacts.changes.debounce(300).onStart { emit(Unit) }.collect {
            accounts = runCatching { app.contacts.accounts() }.getOrNull()
            targets = runCatching { app.contacts.targets() }.getOrDefault(emptyList())
        }
    }
    val hidden by app.preferences.hiddenAccounts.collectAsStateWithLifecycle(emptySet())
    var moving by remember { mutableStateOf(false) }
    val found = accounts ?: return SettingsPage(title = stringResource(R.string.title_accounts), onBack = onBack) {}
    val synced = found.filter { !it.local }
    // Local contacts are listed even without any: a new contact can be saved there
    val local = found.firstOrNull { it.local } ?: AccountInfo(app.contacts.localTarget(), true, null, null, 0, true, 0)
    val moveTargets = targets.filter { !it.local }

    SettingsPage(title = stringResource(R.string.title_accounts), onBack = onBack) {
        if (synced.isNotEmpty()) {
            SettingsGroup(stringResource(R.string.title_accounts)) {
                for (account in synced) {
                    row { shapes ->
                        AccountSwitchRow(shapes, account, shown = account.account.key !in hidden, onShownChange = { shown ->
                            scope.launch { app.preferences.setAccountShown(account.account.key, shown) }
                        })
                    }
                }
            }
        }
        SettingsGroup(stringResource(R.string.label_this_phone)) {
            row { shapes ->
                AccountSwitchRow(shapes, local, shown = local.account.key !in hidden, onShownChange = { shown ->
                    scope.launch { app.preferences.setAccountShown(local.account.key, shown) }
                })
            }
            if (local.count > 0 && moveTargets.isNotEmpty()) {
                row { shapes ->
                    SegmentedListItem(
                        onClick = { moving = true },
                        shapes = shapes,
                        colors = settingsRowColors(),
                        leadingContent = { RowIcon(R.drawable.ic_drive_file_move) },
                        supportingContent = { Text(stringResource(R.string.desc_move_to_account)) },
                    ) {
                        Text(stringResource(R.string.menu_move_to_account))
                    }
                }
            }
        }
        // The local contacts' only copy outside the phone (SPEC §8.3), with the way to make it
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        ) {
            Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.msg_local_only_copy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onExport, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.button_export_all_short)) }
        }
    }
    if (moving) {
        MoveDialog(
            contacts = null,
            name = null,
            targets = moveTargets,
            onMoved = { moved, target ->
                moving = false
                onMoved(moved, target)
            },
            onDismiss = { moving = false },
        )
    }
}
