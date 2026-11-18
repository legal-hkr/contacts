@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.editor

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.data.PhotoChange
import sh.hnet.contacts.data.Saved
import sh.hnet.contacts.model.EditKind
import sh.hnet.contacts.model.FormValue
import sh.hnet.contacts.ui.accounts.AccountPicker
import sh.hnet.contacts.ui.components.Avatar
import sh.hnet.contacts.ui.components.EmptyState
import sh.hnet.contacts.ui.components.TooltipIconButton

/** The section icon, the field label and "Add …" of each kind (SPEC §7.4). */
private class KindLook(@param:DrawableRes val icon: Int, @param:StringRes val label: Int, @param:StringRes val add: Int?, @param:StringRes val menu: Int?)

private val LOOKS = mapOf(
    EditKind.NAME to KindLook(R.drawable.ic_person_add, R.string.label_given_name, null, null),
    EditKind.PHONE to KindLook(R.drawable.ic_call, R.string.label_phone, R.string.button_add_phone, null),
    EditKind.EMAIL to KindLook(R.drawable.ic_mail, R.string.label_email, R.string.button_add_email, null),
    EditKind.ADDRESS to KindLook(R.drawable.ic_location_on, R.string.label_street, R.string.button_add_address, R.string.menu_field_address),
    EditKind.COMPANY to KindLook(R.drawable.ic_work, R.string.label_company, null, R.string.menu_field_company),
    EditKind.WEBSITE to KindLook(R.drawable.ic_public, R.string.label_website, R.string.button_add_website, R.string.menu_field_website),
    EditKind.DATE to KindLook(R.drawable.ic_event, R.string.menu_field_date, R.string.button_add_date, R.string.menu_field_date),
    EditKind.RELATION to KindLook(R.drawable.ic_family_restroom, R.string.menu_field_relation, R.string.button_add_relation, R.string.menu_field_relation),
    EditKind.NICKNAME to KindLook(R.drawable.ic_badge, R.string.menu_field_nickname, null, R.string.menu_field_nickname),
    EditKind.IM to KindLook(R.drawable.ic_forum, R.string.menu_field_im, R.string.button_add_im, R.string.menu_field_im),
    EditKind.SIP to KindLook(R.drawable.ic_dialer_sip, R.string.menu_field_sip, R.string.button_add_sip, R.string.menu_field_sip),
    EditKind.NOTE to KindLook(R.drawable.ic_notes, R.string.menu_field_note, null, R.string.menu_field_note),
)

/**
 * The editor (SPEC §7, board 06): the family's full-screen form for a new contact or one being
 * edited – Close, the title and Save in the top bar; the account; the photo; the name with its
 * other parts behind one button; a phone and an e-mail always, every other kind that has a value,
 * More fields for the rest. Leaving with changes asks first.
 *
 * @param lookupKey The contact to edit, or null for a new one
 */
@Composable
fun EditorScreen(
    lookupKey: String?,
    id: Long,
    session: Long,
    prefill: Prefill,
    snackbars: SnackbarHostState,
    messageScope: CoroutineScope,
    onClose: () -> Unit,
    onSaved: (Saved) -> Unit,
) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    val viewModel: EditorViewModel = viewModel(key = "editor:$session") { EditorViewModel(app, lookupKey, id, prefill) }
    var discarding by rememberSaveable { mutableStateOf(false) }
    var cropping by remember { mutableStateOf<CropSource?>(null) }
    val saveFailed = stringResource(R.string.error_save_failed)
    val photoFailed = stringResource(R.string.error_photo_not_saved)
    val unreadable = stringResource(R.string.error_photo_unreadable)
    // Nothing closes the editor while it waits for the account's app (D1): the save is already in the storage. After a rejection the form holds changes found nowhere else: Back asks too (the code review of 18 Nov 2026)
    val close: () -> Unit = { if (viewModel.uploading) Unit else if (viewModel.changed || viewModel.rejected != null) discarding = true else onClose() }
    val focus = LocalFocusManager.current
    LaunchedEffect(viewModel.uploading) { if (viewModel.uploading) focus.clearFocus() }
    BackHandler(enabled = cropping == null) { close() }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) cropping = CropSource.Address(uri) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val empty = stringResource(R.string.desc_save_disabled)
        TopAppBar(
            title = { Text(stringResource(if (lookupKey == null) R.string.title_new_contact else R.string.title_edit_contact), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { TooltipIconButton(R.drawable.ic_close, stringResource(R.string.button_close), close, enabled = !viewModel.uploading) },
            actions = {
                Button(
                    onClick = {
                        viewModel.save({ saved ->
                            if (saved.photoFailed) messageScope.launch { snackbars.showSnackbar(photoFailed, duration = SnackbarDuration.Short) }
                            onSaved(saved)
                        }) { messageScope.launch { snackbars.showSnackbar(saveFailed, duration = SnackbarDuration.Short) } }
                    },
                    // After a rejection Save would only send to the same account again: the card's two ways out instead
                    enabled = viewModel.phase == EditorPhase.READY && viewModel.hasContent && !viewModel.saving && viewModel.rejected == null,
                    modifier = Modifier.padding(end = 12.dp).height(40.dp).semantics { if (!viewModel.hasContent) stateDescription = empty },
                ) {
                    if (viewModel.saving) LoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current) else Text(stringResource(R.string.button_save))
                }
            },
        )
        when (viewModel.phase) {
            EditorPhase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            EditorPhase.GONE -> EmptyState(R.drawable.ic_person_off, stringResource(R.string.msg_contact_gone)) {
                Button(onClick = onClose) { Text(stringResource(R.string.button_back_to_list)) }
            }
            EditorPhase.READY -> {
                UploadCard(
                    viewModel,
                    onKeep = { viewModel.keepOnPhone(onSaved) { messageScope.launch { snackbars.showSnackbar(saveFailed, duration = SnackbarDuration.Short) } } },
                    onDiscard = { viewModel.discardRejected()?.let(onSaved) ?: onClose() },
                )
                Box(Modifier.fillMaxSize()) {
                    Form(
                        viewModel,
                        onPickPhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onEditPhoto = {
                            // The photo as it is now: a crop made in this form, else the stored one
                            cropping = (viewModel.photo as? PhotoChange.Set)?.let { CropSource.Bytes(it.jpeg) } ?: viewModel.storedPhoto?.let { CropSource.Address(it.substringBefore('#').toUri()) }
                        },
                    )
                    // The form is locked while the save is being sent (D1): a change made now would be closed away unsaved
                    if (viewModel.uploading) {
                        Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
                    }
                }
            }
        }
    }

    cropping?.let { source ->
        CropScreen(
            source,
            onDone = { jpeg ->
                viewModel.photo = PhotoChange.Set(jpeg)
                cropping = null
            },
            onClose = { cropping = null },
            onUnreadable = {
                cropping = null
                messageScope.launch { snackbars.showSnackbar(unreadable, duration = SnackbarDuration.Short) }
            },
        )
    }

    if (discarding) {
        AlertDialog(
            onDismissRequest = { discarding = false },
            title = { Text(stringResource(R.string.title_discard_changes)) },
            text = { Text(stringResource(R.string.msg_discard_changes)) },
            confirmButton = { TextButton(onClick = { discarding = false; onClose() }) { Text(stringResource(R.string.button_discard)) } },
            dismissButton = { TextButton(onClick = { discarding = false }) { Text(stringResource(R.string.button_keep_editing)) } },
        )
    }
}

@Composable
private fun Form(viewModel: EditorViewModel, onPickPhoto: () -> Unit, onEditPhoto: () -> Unit) {
    var dating by remember { mutableStateOf<FormValue?>(null) }
    var moreMenu by remember { mutableStateOf(false) }
    val values = viewModel.values
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Underneath(viewModel)
        val snapshot = viewModel.snapshot
        if (snapshot != null && snapshot.entries.size > 1) {
            val target = snapshot.target
            val targetName = target?.let { if (it.local) stringResource(R.string.label_this_phone) else it.account.name }.orEmpty()
            Card(R.drawable.ic_call_merge, stringResource(R.string.msg_joined_editing, snapshot.entries.size, targetName))
        }
        AccountField(viewModel)
        PhotoRow(viewModel, onPickPhoto, onEditPhoto)
        for (kind in EditKind.entries) {
            val ofKind = values.filter { it.kind == kind }
            if (ofKind.isEmpty()) continue
            Section(kind) {
                ofKind.forEach { value ->
                    // Each field's own state (the phone's country, its cursor) stays with its value, not its place: a removed value no longer hands it to the next (the code review of 18 Nov 2026)
                    key(value.key) {
                        ValueFields(value, onChange = viewModel::update, onRemove = { viewModel.remove(value) }, onDate = { dating = value }, onDefault = { on -> viewModel.setDefault(value, on) }, onlyOne = ofKind.size == 1)
                    }
                }
                LOOKS.getValue(kind).add?.let { add ->
                    TextButton(onClick = { viewModel.add(kind) }) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(add))
                    }
                }
            }
        }
        // More fields (SPEC §7.4.2): the kinds not shown yet; repeatable ones always
        Box {
            OutlinedButton(onClick = { moreMenu = true }) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.button_more_fields))
            }
            val offered = EditKind.entries.filter { it != EditKind.NAME && it != EditKind.PHONE && it != EditKind.EMAIL && (it.repeatable || values.none { v -> v.kind == it }) }
            DropdownMenuPopup(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                    offered.forEachIndexed { index, kind ->
                        val look = LOOKS.getValue(kind)
                        DropdownMenuItem(
                            text = { Text(stringResource(look.menu ?: look.label), maxLines = 1) },
                            leadingIcon = { Icon(painterResource(look.icon), contentDescription = null, modifier = Modifier.size(20.dp)) },
                            shape = MenuDefaults.itemShape(index, offered.size).shape,
                            onClick = {
                                moreMenu = false
                                viewModel.add(kind)
                            },
                        )
                    }
                }
            }
        }
    }
    dating?.let { value ->
        DateDialog(value, onDone = { date ->
            viewModel.values.firstOrNull { it.key == value.key }?.let { viewModel.update(it.copy(parts = it.parts + ("data1" to date))) }
            dating = null
        }, onDismiss = { dating = null })
    }
}

/**
 * The save and the account that syncs it (D1): "Sending…" while its app hasn't answered; when it took the save back, the card on the error container with the two ways out – the form saved on the phone, or the changes dropped.
 */
@Composable
private fun UploadCard(viewModel: EditorViewModel, onKeep: () -> Unit, onDiscard: () -> Unit) {
    val rejected = viewModel.rejected
    if (viewModel.uploading) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoadingIndicator(Modifier.size(24.dp))
            Text(stringResource(R.string.label_sending, (viewModel.snapshot?.entries?.firstOrNull { !it.local && !it.readOnly } ?: viewModel.snapshot?.target)?.account?.name ?: viewModel.account?.account?.name.orEmpty()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else if (rejected != null) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_sync_problem), contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(stringResource(R.string.msg_upload_rejected, rejected), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onDiscard, enabled = !viewModel.saving) { Text(stringResource(R.string.button_discard_changes)) }
                TextButton(onClick = onKeep, enabled = !viewModel.saving) { Text(stringResource(R.string.button_save_on_phone)) }
            }
        }
    }
}

/** A changed or deleted contact while the form is open (SPEC §7.7): the card on the error container, with Reload for a change. */
@Composable
private fun Underneath(viewModel: EditorViewModel) {
    if (viewModel.underneath == Underneath.NONE) return
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_sync_problem), contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                stringResource(if (viewModel.underneath == Underneath.DELETED) R.string.msg_deleted_while_editing else R.string.msg_changed_while_editing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        if (viewModel.underneath == Underneath.CHANGED) {
            TextButton(onClick = viewModel::reload, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.button_reload)) }
        }
    }
}

@Composable
private fun Card(@DrawableRes icon: Int, text: String) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The account (SPEC §7.2): chosen for a new contact until Save; for an existing one only shown. */
@Composable
private fun AccountField(viewModel: EditorViewModel) {
    val snapshot = viewModel.snapshot
    if (snapshot != null) {
        val entry = snapshot.target ?: snapshot.entries.firstOrNull() ?: return
        val name = if (entry.local) stringResource(R.string.label_this_phone) else entry.account.name
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(if (entry.local) R.drawable.ic_smartphone else R.drawable.ic_cloud_sync), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.label_account, name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val current = viewModel.account ?: return
    AccountPicker(stringResource(R.string.label_save_to_account), current, viewModel.targets, onChoose = { viewModel.account = it })
}

/**
 * The photo (SPEC §7.3, owner's change of 17 Nov 2026: no button beside it): a 72 dp circle. Without
 * a photo a tap opens the picker; with one, a menu: Edit photo (crop it again), Change photo, Remove photo.
 */
@Composable
private fun PhotoRow(viewModel: EditorViewModel, onPickPhoto: () -> Unit, onEditPhoto: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val photo = viewModel.photo
    val newPhoto = remember(photo) { (photo as? PhotoChange.Set)?.jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    val stored = if (photo == PhotoChange.None) viewModel.storedPhoto else null
    val has = newPhoto != null || stored != null
    val label = stringResource(if (has) R.string.button_change_photo else R.string.button_add_photo)
    Box {
        Box(
            Modifier.size(72.dp).clip(CircleShape).clickable(onClickLabel = label) { if (has) menu = true else onPickPhoto() }.semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            when {
                newPhoto != null -> Image(newPhoto, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp))
                stored != null -> Avatar("", "", stored, 72.dp)
                else -> Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_add_a_photo), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(28.dp))
                }
            }
        }
        DropdownMenuPopup(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_edit_photo)) }, shape = MenuDefaults.itemShape(0, 3).shape, onClick = { menu = false; onEditPhoto() })
                DropdownMenuItem(text = { Text(stringResource(R.string.button_change_photo)) }, shape = MenuDefaults.itemShape(1, 3).shape, onClick = { menu = false; onPickPhoto() })
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_remove_photo)) }, shape = MenuDefaults.itemShape(2, 3).shape, onClick = { menu = false; viewModel.photo = PhotoChange.Remove })
            }
        }
    }
}

/** A kind's fields with its icon beside the first one (SPEC §7.1). */
@Composable
private fun Section(kind: EditKind, content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(painterResource(LOOKS.getValue(kind).icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 18.dp).size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
