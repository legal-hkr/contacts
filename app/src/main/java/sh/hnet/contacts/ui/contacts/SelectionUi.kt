@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.contacts

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.data.Deletion
import sh.hnet.contacts.data.Sharing
import sh.hnet.contacts.model.ContactRow
import sh.hnet.contacts.ui.components.DialogConfirmButton
import sh.hnet.contacts.ui.components.DialogDismissButton
import sh.hnet.contacts.ui.components.TooltipIconButton

/** The selection's top bar (SPEC §4.7): Close, "Selected: 3", Select all (again: none). */
@Composable
fun SelectionBar(count: Int, allSelected: Boolean, onClose: () -> Unit, onSelectAll: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.title_selected, count), maxLines = 1) },
        navigationIcon = { TooltipIconButton(R.drawable.ic_close, stringResource(R.string.content_description_close_selection), onClose) },
        actions = {
            TooltipIconButton(
                R.drawable.ic_select_all,
                stringResource(R.string.content_description_select_all),
                onSelectAll,
                colors = if (allSelected) IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary) else IconButtonDefaults.iconButtonColors(),
            )
        },
    )
}

/**
 * The selection's actions (SPEC §4.7) in a floating toolbar: Star (or Remove from favorites when
 * every one is starred), Share, Save as file, Delete – last, in the error color, asking first.
 * Merge (2 to 10 contacts) opens the review. After every action the selection ends.
 */
@Composable
fun SelectionToolbar(
    selected: List<ContactRow>,
    snackbars: SnackbarHostState,
    messageScope: CoroutineScope,
    onDone: () -> Unit,
    onMerge: (List<Long>) -> Unit,
    onDelete: (Deletion, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as ContactsApplication
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<Deletion?>(null) }
    val allStarred = selected.isNotEmpty() && selected.all { it.starred }
    val fileName = stringResource(R.string.label_export_filename, selected.size.toString())
    val saved = stringResource(R.string.msg_exported)
    val failed = stringResource(R.string.error_export_failed)
    val starFailed = stringResource(R.string.error_star_failed)
    val starred = stringResource(R.string.msg_starred)
    val unstarred = stringResource(R.string.msg_unstarred)
    val deleted = stringResource(R.string.msg_contacts_deleted)
    val readOnly = stringResource(R.string.desc_read_only_contacts)
    val ids = selected.map { it.id }
    var deletable by remember(ids) { mutableStateOf(true) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Sharing.MIME)) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val chosen = ids
        onDone()
        messageScope.launch {
            val message = try {
                Sharing.save(context, app.writer.cards(chosen), uri)
                saved.format(chosen.size.toString(), fileName)
            } catch (_: Exception) {
                failed.format(fileName)
            }
            snackbars.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainer),
        expandedShadowElevation = 3.dp,
        modifier = modifier,
    ) {
        TooltipIconButton(
            if (allStarred) R.drawable.ic_star_filled else R.drawable.ic_star,
            stringResource(if (allStarred) R.string.tooltip_unstar else R.string.tooltip_star),
            {
                // The selection ends, so the stars show at once in the favorites row (the owner, 17 Nov 2026; the SPEC kept it)
                val chosen = ids
                onDone()
                messageScope.launch {
                    val ok = chosen.all { app.contacts.setStarred(it, !allStarred) }
                    // What happened, said, as the selection that showed it is gone (the owner, 17 Nov 2026)
                    val message = if (!ok) starFailed else (if (allStarred) unstarred else starred).format(chosen.size)
                    snackbars.currentSnackbarData?.dismiss()
                    snackbars.showSnackbar(message, duration = SnackbarDuration.Short)
                }
            },
        )
        TooltipIconButton(R.drawable.ic_share, stringResource(R.string.tooltip_share), {
            val chosen = ids
            onDone()
            messageScope.launch {
                try {
                    Sharing.share(context, Sharing.write(context, app.writer.cards(chosen), fileName))
                } catch (_: Exception) {
                    snackbars.showSnackbar(failed.format(fileName), duration = SnackbarDuration.Short)
                }
            }
        })
        TooltipIconButton(R.drawable.ic_file_save, stringResource(R.string.tooltip_save_as_file), { saveAs.launch(fileName) })
        // Merge: for 2 to 10 contacts (SPEC §4.7), the review as for a pair chosen by hand
        if (ids.size in 2..10) {
            TooltipIconButton(R.drawable.ic_call_merge, stringResource(R.string.tooltip_merge), {
                val chosen = ids
                onDone()
                onMerge(chosen)
            })
        }
        Box(Modifier.semantics { if (!deletable) stateDescription = readOnly }) {
            TooltipIconButton(
                R.drawable.ic_delete,
                stringResource(R.string.tooltip_delete),
                {
                    scope.launch {
                        val deletion = app.writer.deletion(ids)
                        // Every one only in read-only accounts: nothing to delete
                        if (deletion.contacts.isEmpty()) deletable = false else asking = deletion
                    }
                },
                enabled = deletable,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error),
            )
        }
    }
    asking?.let { deletion ->
        // Many contacts at once is the family's case for asking first (SPEC §6.8.3); then the same delayed delete with Undo
        AlertDialog(
            onDismissRequest = { asking = null },
            icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.title_delete_contacts, deletion.contacts.size)) },
            text = {
                val text = stringResource(R.string.msg_delete_contacts)
                Text(if (deletion.skipped > 0) text + " " + stringResource(R.string.msg_delete_skips_read_only, deletion.skipped) else text)
            },
            confirmButton = {
                DialogConfirmButton(onClick = {
                    asking = null
                    onDone()
                    onDelete(deletion, deleted.format(deletion.contacts.size))
                }, destructive = true) { Text(stringResource(R.string.button_delete)) }
            },
            dismissButton = { DialogDismissButton(onClick = { asking = null }) { Text(stringResource(R.string.button_cancel)) } },
        )
    }
}
