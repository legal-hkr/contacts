@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.duplicates

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.data.EditEntry
import sh.hnet.contacts.data.MergeSource
import sh.hnet.contacts.data.Saved
import sh.hnet.contacts.model.EditKind
import sh.hnet.contacts.model.FormValue
import sh.hnet.contacts.ui.components.Avatar
import sh.hnet.contacts.ui.components.SettingsRadioRow
import sh.hnet.contacts.ui.components.TooltipIconButton
import sh.hnet.contacts.ui.components.groupShapes
import sh.hnet.contacts.ui.components.settingsRowColors
import sh.hnet.contacts.ui.contact.dateText
import sh.hnet.contacts.ui.editor.typeName

/**
 * "Same person?" (SPEC §9.4, the owner's decisions of 17 Nov 2026): the contacts side by side,
 * then the two ways – "Merge into one contact" (the entry that stays, the name and photo from one
 * side, every value checked and uncheckable; asks first, no Undo) and "Only join" (the entries stay
 * in their accounts; Undo) –, and "Not the same person" for a suggestion, Cancel for a pair the
 * user chose.
 *
 * @param suggested The group came from the suggestions (else the user joins by hand: no "Not the same person")
 */
@Composable
fun ReviewScreen(
    ids: List<Long>,
    suggested: Boolean,
    onBack: () -> Unit,
    onMerged: (Saved, String) -> Unit,
    onJoined: (Map<Pair<Long, Long>, Int>, String) -> Unit,
    onApart: (Map<Pair<Long, Long>, Int>) -> Unit,
    onFailed: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    val scope = rememberCoroutineScope()
    val source by produceState<MergeSource?>(null, ids) { value = app.writer.group(ids) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.title_same_person), maxLines = 1) },
            navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
        )
        val read = source
        if (read == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@Column
        }
        Review(read, ids, suggested, onBack, onMerged = { saved, name -> onMerged(saved, name) }, onJoin = {
            scope.launch {
                val before = app.writer.join(listOf(ids))
                onJoined(before, read.each.firstOrNull()?.let { nameOf(it.values) }.orEmpty())
            }
        }, onApart = {
            scope.launch { onApart(app.writer.keepApart(ids)) }
        }, onFailed = onFailed)
    }
}

/** A contact's name as its name value gives it, for messages. */
private fun nameOf(values: List<FormValue>): String =
    values.firstOrNull { it.kind == EditKind.NAME }?.let { listOf("data4", "data2", "data5", "data3", "data6").mapNotNull { c -> it.parts[c]?.takeIf { p -> p.isNotBlank() } }.joinToString(" ") }.orEmpty()

@Composable
private fun Review(
    source: MergeSource,
    ids: List<Long>,
    suggested: Boolean,
    onCancel: () -> Unit,
    onMerged: (Saved, String) -> Unit,
    onJoin: () -> Unit,
    onApart: () -> Unit,
    onFailed: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    val scope = rememberCoroutineScope()
    val rows = rememberRows()
    val thisPhone = stringResource(R.string.label_this_phone)
    fun accountOf(entry: EditEntry) = if (entry.local) thisPhone else entry.account.name
    var permanent by rememberSaveable { mutableStateOf(true) }
    // The entry that stays: writable, an account before the phone (the owner's default)
    val writable = source.all.entries.filter { !it.readOnly }
    var target by rememberSaveable { mutableStateOf(writable.sortedBy { it.local }.firstOrNull()?.rawContactId) }
    val values = source.all.values.filter { it.kind != EditKind.NAME }
    var kept by remember { mutableStateOf(values.map { it.key }.toSet()) }
    val names = source.each.mapNotNull { snapshot -> snapshot.values.firstOrNull { it.kind == EditKind.NAME }?.let { snapshot.contactId to it } }.distinctBy { nameOf(listOf(it.second)) }
    var nameFrom by rememberSaveable { mutableStateOf(source.all.entries.firstOrNull { it.rawContactId == target }?.contactId ?: names.firstOrNull()?.first) }
    val photos = source.each.filter { it.photo != null }
    var photoFrom by rememberSaveable { mutableStateOf(photos.firstOrNull { snapshot -> snapshot.entries.any { it.rawContactId == target } }?.contactId ?: photos.firstOrNull()?.contactId) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val deleted = writable.filter { it.rawContactId != target }
    val joinedReadOnly = source.all.entries.filter { it.readOnly }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The contacts side by side; more than two scroll sideways
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ids.forEach { id ->
                    val row = rows[id]
                    val entries = source.all.entries.filter { it.contactId == id }
                    Column(
                        Modifier.width(if (ids.size > 2) 156.dp else 160.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp)).padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Avatar(row?.name.orEmpty(), row?.lookupKey.orEmpty(), row?.photo, 56.dp, initials = row?.initials, large = row?.largePhoto)
                        Text(row?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        Text(entries.joinToString(", ") { accountOf(it) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // The two ways (the owner, 17 Nov 2026)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                listOf(true to R.string.label_merge_permanent, false to R.string.label_merge_join).forEachIndexed { index, (value, label) ->
                    ToggleButton(
                        checked = permanent == value,
                        onCheckedChange = { if (it) permanent = value },
                        enabled = value == false || writable.isNotEmpty(),
                        shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                    ) { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Text(
                stringResource(if (permanent) R.string.desc_merge_permanent else R.string.desc_merge_join),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (permanent) {
                Header(R.string.label_entry_stays)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    writable.forEachIndexed { index, entry ->
                        SettingsRadioRow(groupShapes(index, writable.size), accountOf(entry), selected = entry.rawContactId == target, onSelect = { target = entry.rawContactId }, supporting = rows[entry.contactId]?.name)
                    }
                }
                for (entry in deleted) Note(stringResource(R.string.msg_entry_deleted, accountOf(entry)))
                for (entry in joinedReadOnly) Note(stringResource(R.string.msg_entry_joined, accountOf(entry)))
                if (names.size > 1) {
                    Header(R.string.label_merge_name)
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        names.forEachIndexed { index, (contactId, name) ->
                            SettingsRadioRow(groupShapes(index, names.size), nameOf(listOf(name)), selected = contactId == nameFrom, onSelect = { nameFrom = contactId })
                        }
                    }
                }
                if (photos.size > 1) {
                    Header(R.string.label_merge_photo)
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        photos.forEachIndexed { index, snapshot ->
                            SegmentedListItem(
                                selected = snapshot.contactId == photoFrom,
                                onClick = { photoFrom = snapshot.contactId },
                                shapes = groupShapes(index, photos.size),
                                colors = settingsRowColors(),
                                leadingContent = { Avatar("", "", snapshot.photo, 40.dp) },
                            ) { Text(rows[snapshot.contactId]?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
            Header(R.string.label_after_merging)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                values.forEachIndexed { index, value ->
                    val inContacts = value.origins.mapNotNull { origin -> source.all.entries.firstOrNull { it.rawContactId == origin.rawContactId }?.contactId }.toSet()
                    val tag = when {
                        inContacts.size == ids.size -> stringResource(if (ids.size == 2) R.string.label_tag_both else R.string.label_tag_all)
                        ids.size == 2 -> stringResource(if (ids.first() in inContacts) R.string.label_tag_left else R.string.label_tag_right)
                        else -> source.all.entries.firstOrNull { it.rawContactId == value.origins.first().rawContactId }?.let { accountOf(it) }.orEmpty()
                    }
                    SegmentedListItem(
                        checked = !permanent || value.key in kept,
                        onCheckedChange = { if (permanent) kept = if (it) kept + value.key else kept - value.key },
                        shapes = groupShapes(index, values.size),
                        colors = settingsRowColors(),
                        leadingContent = if (permanent) ({ Checkbox(checked = value.key in kept, onCheckedChange = null) }) else null,
                        trailingContent = {
                            Text(tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                        },
                        supportingContent = typeName(value).takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                    ) {
                        Text(valueText(value), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        // Pinned, stacked: the Polish label of the second is long
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (permanent) confirming = true else onJoin() },
                enabled = !busy && (!permanent || target != null),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (busy) LoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current)
                else {
                    Icon(painterResource(R.drawable.ic_call_merge), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (permanent) R.string.button_merge_permanent else R.string.button_merge))
                }
            }
            OutlinedButton(onClick = if (suggested) onApart else onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (suggested) R.string.button_not_same_person else R.string.button_cancel))
            }
        }
    }
    if (confirming) {
        val accounts = deleted.joinToString(", ") { accountOf(it) }
        AlertDialog(
            onDismissRequest = { confirming = false },
            icon = { Icon(painterResource(R.drawable.ic_call_merge), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.title_merge_permanent)) },
            text = { Text(if (deleted.isEmpty()) stringResource(R.string.msg_merge_permanent_none) else stringResource(R.string.msg_merge_permanent, accounts)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    val chosenTarget = target ?: return@TextButton
                    busy = true
                    scope.launch {
                        val name = names.firstOrNull { it.first == nameFrom }?.second
                        // An earlier save's watch of these entries ends: the merge rewrites them
                        app.uploads.forget(source.all.entries.map { it.rawContactId })
                        val saved = app.writer.mergePermanently(source, chosenTarget, kept, name, photoFrom)
                        busy = false
                        if (saved != null) onMerged(saved, name?.let { nameOf(listOf(it)) } ?: rows[ids.first()]?.name.orEmpty()) else onFailed()
                    }
                }) { Text(stringResource(R.string.button_merge_permanent), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.button_cancel)) } },
        )
    }
}

@Composable
private fun Header(label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 8.dp).semantics { heading() })
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
}

/** A value on one line as the review lists it: the number, address parts, date as on the page. */
@Composable
private fun valueText(value: FormValue): String = when (value.kind) {
    EditKind.DATE -> dateText(value.part("data1"))
    EditKind.ADDRESS -> listOf("data4", "data9", "data7", "data8", "data10").mapNotNull { value.parts[it]?.takeIf { p -> p.isNotBlank() } }.joinToString(", ")
    EditKind.COMPANY -> listOf("data1", "data4").mapNotNull { value.parts[it]?.takeIf { p -> p.isNotBlank() } }.joinToString(", ")
    else -> value.part("data1")
}
