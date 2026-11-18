@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.IOException
import java.text.NumberFormat
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.data.AccountInfo
import sh.hnet.contacts.model.AccountKey
import sh.hnet.contacts.model.VCardWriter
import sh.hnet.contacts.ui.accounts.AccountIcon
import sh.hnet.contacts.ui.accounts.accountName
import sh.hnet.contacts.ui.accounts.accountSummary
import sh.hnet.contacts.ui.components.ErrorDetails
import sh.hnet.contacts.ui.components.ErrorDetailsDialog
import sh.hnet.contacts.ui.components.SettingsDescription
import sh.hnet.contacts.ui.components.SettingsGroup
import sh.hnet.contacts.ui.components.SettingsPage
import sh.hnet.contacts.ui.components.SettingsSwitchRow
import sh.hnet.contacts.ui.components.reportFailure
import sh.hnet.contacts.ui.components.settingsRowColors

/** The export's progress is shown above this many contacts (SPEC §11.3). */
private const val PROGRESS_FROM = 200

/** Contacts read and written at a time, so a large export with photos never sits in memory whole. */
private const val CHUNK = 50

/** Bytes a contact adds besides its photo, for the estimate (SPEC §11.1). */
private const val PER_CONTACT = 1024L

/**
 * Export all contacts (SPEC §11.1–§11.3): the accounts to take (shown ones checked, hidden ones
 * not, read-only ones too), "With photos" and the size it adds, how many contacts go in, and
 * "Save as…". Each contact is one vCard 4.0 with what its entries in the checked accounts hold.
 * Written in the shell's scope, so leaving the page doesn't cut the file short.
 */
@Composable
fun ExportScreen(onBack: () -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val context = LocalContext.current
    val app = context.applicationContext as ContactsApplication
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val hidden by app.preferences.hiddenAccounts.collectAsStateWithLifecycle(null)
    val accounts by produceState<List<AccountInfo>?>(null) { value = runCatching { app.contacts.accounts() }.getOrDefault(emptyList()) }
    var checked by rememberSaveable { mutableStateOf<List<String>?>(null) }
    // Preset once both are known: the accounts the list shows
    LaunchedEffect(accounts, hidden) {
        val found = accounts ?: return@LaunchedEffect
        val hiddenNow = hidden ?: return@LaunchedEffect
        if (checked == null) checked = found.map { it.account.key }.filter { it !in hiddenNow }
    }
    var photos by rememberSaveable { mutableStateOf(true) }
    val chosen = accounts.orEmpty().filter { it.account.key in checked.orEmpty() }.map { it.account }.toSet()
    // The contacts in the file and the photos' bytes, worked out again as the choice changes
    val ids by produceState<List<Long>?>(null, chosen) { value = runCatching { app.contacts.contactsIn(chosen) }.getOrDefault(emptyList()) }
    val photoBytes by produceState<Long?>(null, ids) { value = ids?.let { runCatching { app.contacts.photoBytes(it) }.getOrDefault(0L) } }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var errorShown by remember { mutableStateOf<ErrorDetails?>(null) }

    val fileName = stringResource(R.string.label_export_filename, LocalDate.now().toString())
    val saved = stringResource(R.string.msg_exported)
    val failed = stringResource(R.string.error_export_failed)
    val show = stringResource(R.string.button_show)
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(VCARD)) { target ->
        val list = ids ?: return@rememberLauncherForActivityResult
        if (target == null) return@rememberLauncherForActivityResult
        val name = displayName(context, target) ?: fileName
        progress = 0 to list.size
        messageScope.launch {
            try {
                write(app, target, list, chosen, photos) { done -> progress = done to list.size }
                snackbars.showSnackbar(saved.format(format.format(list.size), name), duration = SnackbarDuration.Short)
            } catch (e: Exception) {
                reportFailure(snackbars, failed.format(name), e.toString(), show) { errorShown = it }
            } finally {
                progress = null
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    SettingsPage(title = stringResource(R.string.title_export_all), onBack = onBack) {
        SettingsDescription(stringResource(R.string.desc_export_intro))
        val found = accounts
        if (found != null) {
            SettingsGroup(stringResource(R.string.title_accounts)) {
                for (account in found) {
                    row { shapes ->
                        val on = account.account.key in checked.orEmpty()
                        val summary = accountSummary(account)
                        SegmentedListItem(
                            checked = on,
                            onCheckedChange = { now -> checked = if (now) checked.orEmpty() + account.account.key else checked.orEmpty() - account.account.key },
                            enabled = progress == null,
                            shapes = shapes,
                            colors = settingsRowColors(),
                            leadingContent = { Checkbox(checked = on, onCheckedChange = null, enabled = progress == null) },
                            trailingContent = { AccountIcon(account) },
                            supportingContent = { Text(if (account.account.key in hidden.orEmpty()) stringResource(R.string.label_hidden_in_list_suffix, summary) else summary) },
                        ) { Text(accountName(account)) }
                    }
                }
            }
            SettingsGroup(stringResource(R.string.label_group_file)) {
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, R.drawable.ic_add_a_photo, stringResource(R.string.label_with_photos), photos, onCheckedChange = { if (progress == null) photos = it },
                        supporting = photoBytes?.let { stringResource(R.string.desc_with_photos, size(it + PER_CONTACT * (ids?.size ?: 0))) },
                    )
                }
            }
            ids?.let { SettingsDescription(stringResource(R.string.label_export_count, format.format(it.size))) }
        }
        // Room for the pinned button
        Spacer(Modifier.height(if (progress != null) 120.dp else 72.dp))
    }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            progress?.let { (done, total) ->
                if (total > PROGRESS_FROM) {
                    Text(stringResource(R.string.label_export_progress, format.format(done), format.format(total)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearWavyProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                }
            }
            val busy = progress != null
            Button(
                onClick = { if (!busy) saveAs.launch(fileName) },
                enabled = busy || (chosen.isNotEmpty() && !ids.isNullOrEmpty()),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (busy) {
                    LoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.label_exporting))
                } else {
                    Icon(painterResource(R.drawable.ic_file_save), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.button_save_as))
                }
            }
        }
    }
    errorShown?.let { ErrorDetailsDialog(it) { errorShown = null } }
}

private const val VCARD = "text/vcard"

/** "about 41 MB", or "under 1 MB" (SPEC §11.1). */
@Composable
private fun size(bytes: Long): String {
    val megabytes = bytes / (1024.0 * 1024.0)
    return if (megabytes < 1) stringResource(R.string.label_size_under_1mb) else stringResource(R.string.label_size_mb, Math.round(megabytes).toInt())
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
}.getOrNull()

/** Writes the contacts [ids] to [target], [CHUNK] at a time. */
private suspend fun write(app: ContactsApplication, target: Uri, ids: List<Long>, accounts: Set<AccountKey>, photos: Boolean, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
    (app.contentResolver.openOutputStream(target, "wt") ?: throw IOException("No output")).buffered().use { out ->
        for ((index, chunk) in ids.chunked(CHUNK).withIndex()) {
            for (card in app.writer.cards(chunk, accounts, photos)) out.write(VCardWriter.card(card).toByteArray())
            withContext(Dispatchers.Main) { onProgress(minOf(ids.size, (index + 1) * CHUNK)) }
        }
    }
}
