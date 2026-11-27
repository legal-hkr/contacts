@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.data.AccountInfo
import sh.hnet.contacts.data.Moved
import sh.hnet.contacts.ui.components.DialogConfirmButton
import sh.hnet.contacts.ui.components.DialogDismissButton
import sh.hnet.contacts.ui.components.SettingsRadioRow
import sh.hnet.contacts.ui.components.groupShapes

/**
 * Move to another account (SPEC §8.4): the writable accounts other than the source, the default
 * first and chosen; Move copies the contacts, and the old entries go when Undo is no longer offered.
 *
 * @param contacts The contacts to move; null for every local contact
 * @param name The one contact's name, for the title; null for several
 */
@Composable
fun MoveDialog(contacts: List<Long>?, name: String?, targets: List<AccountInfo>, onMoved: (Moved, String) -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    var chosen by remember { mutableStateOf(targets.firstOrNull()) }
    var busy by remember { mutableStateOf(false) }
    var count by remember { mutableStateOf<Int?>(contacts?.size) }
    if (count == null) {
        LaunchedEffect(Unit) { count = app.writer.localContacts().size }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = !busy, dismissOnBackPress = !busy),
        icon = { Icon(painterResource(R.drawable.ic_drive_file_move), contentDescription = null) },
        title = { Text(if (name != null) stringResource(R.string.title_move_contact, name) else stringResource(R.string.title_move_contacts, count ?: 0)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (name != null) R.string.msg_move_contact else R.string.msg_move_contacts))
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    targets.forEachIndexed { index, target ->
                        SettingsRadioRow(groupShapes(index, targets.size), accountName(target), selected = target == chosen, onSelect = { if (!busy) chosen = target }, supporting = target.app)
                    }
                }
            }
        },
        confirmButton = {
            DialogConfirmButton(enabled = chosen != null && !busy, onClick = {
                val target = chosen ?: return@DialogConfirmButton
                busy = true
                // In the app's scope: a dialog gone midway (the activity recreated by a theme or language change) would cancel the move after the copies were written, and with onMoved never called the old entries stayed beside them with no Undo
                app.appScope.launch {
                    val ids = contacts ?: app.writer.localContacts()
                    val moved = app.writer.move(ids, target.account)
                    withContext(Dispatchers.Main) {
                        busy = false
                        onMoved(moved, target.account.name)
                    }
                }
            }) {
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.label_moving))
                    }
                } else {
                    Text(stringResource(R.string.button_move))
                }
            }
        },
        dismissButton = { DialogDismissButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
    )
}
