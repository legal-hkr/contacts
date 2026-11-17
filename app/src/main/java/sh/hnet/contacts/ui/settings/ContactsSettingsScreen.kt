package sh.hnet.contacts.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.data.AccountInfo
import sh.hnet.contacts.ui.accounts.accountName
import sh.hnet.contacts.ui.components.SettingsGroup
import sh.hnet.contacts.ui.components.SettingsNavigationRow
import sh.hnet.contacts.ui.components.SettingsPage
import sh.hnet.contacts.ui.components.SingleChoiceDialog

/** Settings › Contacts (SPEC §13.3): the default account for new contacts, chosen in a dialog among the writable accounts and "On this phone". */
@Composable
fun ContactsSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    val scope = rememberCoroutineScope()
    val targets by produceState<List<AccountInfo>>(emptyList()) { value = runCatching { app.contacts.targets() }.getOrDefault(emptyList()) }
    val chosen by app.preferences.defaultAccount.collectAsStateWithLifecycle(null)
    var choosing by rememberSaveable { mutableStateOf(false) }
    val current = targets.takeIf { it.isNotEmpty() }?.let { app.contacts.resolveDefault(it, chosen) }
    SettingsPage(title = stringResource(R.string.label_settings_contacts), onBack = onBack) {
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_person_add, stringResource(R.string.label_default_account), current?.let { accountName(it) }, onClick = { if (targets.isNotEmpty()) choosing = true })
            }
        }
    }
    if (choosing && current != null) {
        val described = targets.map { target -> target to (target.app ?: if (target.local) stringResource(R.string.label_local_contacts) else target.account.type) }
        SingleChoiceDialog(
            title = AnnotatedString(stringResource(R.string.title_default_account)),
            options = targets,
            initial = current,
            label = { target -> accountName(target) + " (" + described.first { it.first == target }.second + ")" },
            confirmLabel = stringResource(R.string.button_save),
            onConfirm = { target ->
                choosing = false
                scope.launch { app.preferences.setDefaultAccount(target.account.key) }
            },
            onDismiss = { choosing = false },
        )
    }
}
