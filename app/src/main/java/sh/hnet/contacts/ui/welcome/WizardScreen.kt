@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.welcome

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.data.AccountInfo
import sh.hnet.contacts.model.MessagingAccounts
import sh.hnet.contacts.ui.accounts.AccountSwitchRow
import sh.hnet.contacts.ui.components.AppLogo
import sh.hnet.contacts.ui.components.FormPage
import sh.hnet.contacts.ui.components.RowIcon
import sh.hnet.contacts.ui.components.SettingsRadioRow
import sh.hnet.contacts.ui.components.groupShapes
import sh.hnet.contacts.ui.components.rememberContactsAccessAsk
import sh.hnet.contacts.ui.components.settingsRowColors
import sh.hnet.contacts.ui.settings.rememberRestoreAction

private enum class Step { ACCESS, BACKUP, ACCOUNTS }

/**
 * The first run (SPEC §12), the family's wizard as Calendar's, lying over the list until it is
 * done: access, backup or a fresh start, and the accounts found. Access already given skips the
 * first step; without access the wizard ends after the second, on the list's no-access state.
 */
@Composable
fun WizardScreen(snackbars: SnackbarHostState, messageScope: CoroutineScope, onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    val startedWithAccess = rememberSaveable { app.contacts.hasAccess() }
    var index by rememberSaveable { mutableIntStateOf(0) }
    // Access refused: the wizard ends with the backup step, which says so ("Step 2 of 2", Done) instead of promising a third
    val steps = when {
        startedWithAccess -> listOf(Step.BACKUP, Step.ACCOUNTS)
        index > 0 && !app.contacts.hasAccess() -> listOf(Step.ACCESS, Step.BACKUP)
        else -> listOf(Step.ACCESS, Step.BACKUP, Step.ACCOUNTS)
    }
    var restored by rememberSaveable { mutableStateOf(false) }
    val next: () -> Unit = {
        val following = steps.getOrNull(index + 1)
        // Without access there are no accounts to show
        if (following == null || (following == Step.ACCOUNTS && !app.contacts.hasAccess())) onDone() else index++
    }
    val label = stringResource(R.string.label_step, index + 1, steps.size)
    when (steps[index]) {
        Step.ACCESS -> AccessStep(label, onNext = next)
        Step.BACKUP -> BackupStep(label, last = index == steps.lastIndex, snackbars, messageScope, onRestored = { restored = true; next() }, onNext = next)
        Step.ACCOUNTS -> AccountsStep(label, restored, onDone = onDone)
    }
}

/** A step's frame (SPEC §12): the app's mark, "Step n of 3", the title and its paragraph, then the step's own content. */
@Composable
private fun WizardStep(
    step: String,
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    FormPage(title = null, confirmLabel = confirmLabel, onConfirm = onConfirm, confirmEnabled = confirmEnabled, contentSpacing = 0.dp) {
        Spacer(Modifier.height(16.dp))
        AppLogo(titleInset = TITLE_INSET)
        Text(step, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = TITLE_INSET))
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = TITLE_INSET, end = TITLE_INSET, top = 4.dp).semantics { heading() })
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = TITLE_INSET, end = TITLE_INSET, top = 12.dp, bottom = 20.dp))
        content()
    }
}

/** A segmented row that only informs, with its icon (SPEC §12.1). */
@Composable
private fun InfoRow(index: Int, count: Int, @DrawableRes icon: Int, title: String, supporting: String) {
    SegmentedListItem(
        shapes = groupShapes(index, count),
        colors = settingsRowColors(),
        leadingContent = { RowIcon(icon) },
        supportingContent = { Text(supporting) },
    ) {
        Text(title)
    }
}

/** Step 1 (SPEC §12.1): what the access is for, Allow access, and Not now. Either way the backup step follows. */
@Composable
private fun AccessStep(step: String, onNext: () -> Unit) {
    val ask = rememberContactsAccessAsk(onNext)
    WizardStep(step, stringResource(R.string.app_name), stringResource(R.string.msg_wizard_access), stringResource(R.string.button_allow_access), ask) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            InfoRow(0, 2, R.drawable.ic_cloud_off, stringResource(R.string.label_no_internet), stringResource(R.string.desc_no_internet))
            InfoRow(1, 2, R.drawable.ic_sync, stringResource(R.string.label_sync_by_others), stringResource(R.string.desc_sync_by_others))
        }
        TextButton(onClick = onNext, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.button_not_now)) }
    }
}

/** Step 2 (SPEC §12.2): the family's question – start fresh or restore a backup – and Next, which acts on the choice. */
@Composable
private fun BackupStep(step: String, last: Boolean, snackbars: SnackbarHostState, messageScope: CoroutineScope, onRestored: () -> Unit, onNext: () -> Unit) {
    var restore by rememberSaveable { mutableStateOf(false) }
    val restoreAction = rememberRestoreAction(snackbars, messageScope, onRestored = onRestored)
    WizardStep(step, stringResource(R.string.title_backup), stringResource(R.string.msg_wizard_backup), stringResource(if (last) R.string.button_done else R.string.button_next), onConfirm = { if (restore) restoreAction.pick() else onNext() }) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SettingsRadioRow(groupShapes(0, 2), stringResource(R.string.label_start_fresh), selected = !restore, onSelect = { restore = false }, supporting = stringResource(R.string.desc_start_fresh))
            SettingsRadioRow(groupShapes(1, 2), stringResource(R.string.label_restore_backup), selected = restore, onSelect = { restore = true }, supporting = stringResource(R.string.desc_restore_backup_wizard))
        }
    }
}

/**
 * Step 3 (SPEC §12.3–§12.5): the accounts found, with the switches of the Accounts page. Messaging
 * apps' accounts start hidden, unless a restored backup already settled them. A phone without any
 * account gets the note that local contacts work without one.
 */
@Composable
private fun AccountsStep(step: String, restored: Boolean, onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    val scope = rememberCoroutineScope()
    val accounts by produceState<List<AccountInfo>?>(null) { value = runCatching { app.contacts.accounts() }.getOrDefault(emptyList()) }
    val hidden by app.preferences.hiddenAccounts.collectAsStateWithLifecycle(emptySet())
    var settled by rememberSaveable { mutableStateOf(restored) }
    LaunchedEffect(accounts) {
        val found = accounts ?: return@LaunchedEffect
        if (settled) return@LaunchedEffect
        try {
            val current = app.preferences.hiddenAccounts.first()
            found.filter { it.account.type in MessagingAccounts.TYPES && it.account.key !in current }.forEach { app.preferences.setAccountShown(it.account.key, false) }
            // The default account for new contacts, set without asking (SPEC §12.3): the first writable account, or local contacts
            if (app.preferences.defaultAccount.first() == null) {
                val targets = app.contacts.targets()
                app.preferences.setDefaultAccount(targets.first().account.key)
            }
        } catch (_: Exception) {
            // The storage refused to answer (access taken away): the wizard can still end, the defaults stay
        }
        settled = true
    }
    val synced = accounts.orEmpty().filter { !it.local }
    if (accounts != null && synced.isEmpty()) {
        WizardStep(step, stringResource(R.string.title_accounts), stringResource(R.string.msg_wizard_no_account), stringResource(R.string.button_done), onDone, confirmEnabled = settled) {
            SegmentedListItem(
                shapes = groupShapes(0, 1),
                colors = settingsRowColors(),
                leadingContent = { RowIcon(R.drawable.ic_smartphone) },
                supportingContent = { Text(stringResource(R.string.desc_new_contacts_here)) },
            ) {
                Text(stringResource(R.string.label_this_phone))
            }
            Text(stringResource(R.string.msg_wizard_export_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
        }
        return
    }
    // Done waits for the setup above: leaving earlier cancelled it, and messaging apps' accounts stayed in the list with no default account set
    WizardStep(step, stringResource(R.string.title_accounts), stringResource(R.string.msg_wizard_accounts), stringResource(R.string.button_done), onDone, confirmEnabled = settled) {
        val found = accounts.orEmpty()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            found.forEachIndexed { i, account ->
                AccountSwitchRow(groupShapes(i, found.size), account, shown = account.account.key !in hidden, onShownChange = { shown ->
                    scope.launch { app.preferences.setAccountShown(account.account.key, shown) }
                })
            }
        }
    }
}

/** Start inset of the title and texts, which the logo lines up with. */
private val TITLE_INSET = 8.dp
