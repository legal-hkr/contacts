@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.transfer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.NumberFormat
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.data.ImportJob
import sh.hnet.contacts.model.CardContact
import sh.hnet.contacts.model.CardValue
import sh.hnet.contacts.model.Detail
import sh.hnet.contacts.model.DetailKind
import sh.hnet.contacts.model.EditKind
import sh.hnet.contacts.ui.accounts.AccountPicker
import sh.hnet.contacts.ui.components.Avatar
import sh.hnet.contacts.ui.components.EmptyState
import sh.hnet.contacts.ui.components.RowIcon
import sh.hnet.contacts.ui.components.TooltipIconButton
import sh.hnet.contacts.ui.components.groupShapes
import sh.hnet.contacts.ui.components.settingsRowColors
import sh.hnet.contacts.ui.contact.DetailGroup
import sh.hnet.contacts.ui.contact.Header

/** The import's progress is shown above this many contacts (SPEC §10.4); fewer show only the busy button. */
private const val PROGRESS_FROM = 50

/**
 * Import (SPEC §10.2–§10.6), on the pattern of Calendar's ICS import: Close, the title, the file's
 * name; the target account; for one contact its page as it will be, for several a list to choose
 * from with the existing ones left out; "Import" pinned. The import itself runs in the app
 * ([ImportJob]); the shell reports its outcome.
 *
 * @param onChooseOther Picks another file; null when another app opened this one (then Close)
 */
@Composable
fun ImportScreen(uris: List<String>, onClose: () -> Unit, onChooseOther: (() -> Unit)?) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    val viewModel: ImportViewModel = viewModel(key = "import:${uris.joinToString()}") { ImportViewModel(app, uris) }
    val job by app.imports.state.collectAsStateWithLifecycle()
    val running = job as? ImportJob.State.Running
    val state = viewModel.state
    val count = (state as? ImportViewModel.State.Ready)?.file?.contacts?.size ?: 0
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(if (count == 1) R.string.title_import_contact else R.string.title_import_contacts), maxLines = 1, modifier = Modifier.semantics { heading() })
                    if (viewModel.fileName.isNotEmpty()) {
                        Text(viewModel.fileName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            navigationIcon = { TooltipIconButton(R.drawable.ic_close, stringResource(R.string.button_close), onClose) },
        )
        when (state) {
            ImportViewModel.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
            ImportViewModel.State.Unreadable -> {
                Box(Modifier.weight(1f).padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(20.dp)).padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(stringResource(R.string.error_unreadable_file), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OtherFileButton(onClose, onChooseOther)
            }
            ImportViewModel.State.Empty -> {
                Box(Modifier.weight(1f)) { EmptyState(R.drawable.ic_contact_page, stringResource(R.string.msg_file_no_contacts)) }
                OtherFileButton(onClose, onChooseOther)
            }
            is ImportViewModel.State.Ready -> Ready(viewModel, state, running, Modifier.weight(1f))
        }
    }
}

@Composable
private fun OtherFileButton(onClose: () -> Unit, onChooseOther: (() -> Unit)?) {
    FilledTonalButton(
        onClick = onChooseOther ?: onClose,
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp),
    ) { Text(stringResource(if (onChooseOther != null) R.string.button_choose_other_file else R.string.button_close)) }
}

@Composable
private fun Ready(viewModel: ImportViewModel, state: ImportViewModel.State.Ready, running: ImportJob.State.Running?, modifier: Modifier) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val contacts = state.file.contacts
    val single = contacts.size == 1
    val busy = running != null
    val thisPhone = stringResource(R.string.label_this_phone)
    val exists = stringResource(R.string.label_already_exists_suffix)
    LazyColumn(modifier.fillMaxWidth()) {
        item(key = "account") {
            viewModel.account?.let { current ->
                AccountPicker(
                    stringResource(R.string.label_import_to_account), current, viewModel.targets, onChoose = { viewModel.account = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), enabled = !busy,
                )
            }
        }
        // Fields the app has no place for (SPEC §10.4): up to three names, then "…"
        if (state.file.skipped.isNotEmpty()) {
            item(key = "skipped") {
                val names = state.file.skipped.take(3).joinToString(", ") + if (state.file.skipped.size > 3) ", …" else ""
                Note(R.drawable.ic_info, stringResource(R.string.msg_fields_skipped, names, state.file.skippedContacts))
            }
        }
        if (single) {
            val existing = state.existing[0]
            if (existing != null) {
                item(key = "exists") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Note(R.drawable.ic_person_add, stringResource(R.string.msg_contact_exists, existing.name, if (existing.local) thisPhone else existing.account.name), padded = false)
                        SegmentedListItem(
                            checked = viewModel.importAnyway,
                            onCheckedChange = { viewModel.importAnyway = it },
                            enabled = !busy,
                            shapes = groupShapes(0, 1),
                            colors = settingsRowColors(),
                            trailingContent = { Switch(checked = viewModel.importAnyway, onCheckedChange = null, enabled = !busy) },
                        ) { Text(stringResource(R.string.label_import_anyway)) }
                    }
                }
            }
            item(key = "preview") { Preview(contacts[0], state.preview, app.contacts.region()) }
        } else {
            item(key = "skip") {
                SegmentedListItem(
                    checked = viewModel.skipExisting,
                    onCheckedChange = viewModel::setSkip,
                    enabled = !busy,
                    shapes = groupShapes(0, 1),
                    colors = settingsRowColors(),
                    leadingContent = { RowIcon(R.drawable.ic_call_merge) },
                    supportingContent = { Text(stringResource(R.string.desc_skip_existing)) },
                    trailingContent = { Switch(checked = viewModel.skipExisting, onCheckedChange = null, enabled = !busy) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(stringResource(R.string.label_skip_existing)) }
            }
            item(key = "count") {
                val all = viewModel.selected.size
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    TriStateCheckbox(
                        state = when (all) {
                            0 -> ToggleableState.Off
                            contacts.size -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        },
                        onClick = { viewModel.selectAll(all != contacts.size) },
                        enabled = !busy,
                    )
                    Text(
                        stringResource(R.string.label_import_count, contacts.size, all),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(contacts) { index, contact ->
                val second = contact.values[EditKind.PHONE]?.firstOrNull()?.part("data1") ?: contact.values[EditKind.EMAIL]?.firstOrNull()?.part("data1")
                SegmentedListItem(
                    checked = index in viewModel.selected,
                    onCheckedChange = { viewModel.toggle(index) },
                    enabled = !busy,
                    shapes = groupShapes(index, contacts.size),
                    colors = settingsRowColors(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
                    leadingContent = { Avatar(contact.displayName, contact.displayName, null, 40.dp) },
                    supportingContent = second?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    trailingContent = { Checkbox(checked = index in viewModel.selected, onCheckedChange = null, enabled = !busy) },
                ) {
                    // "(already exists)" after the name, in the error colour (the owner, 18 Nov 2026)
                    val name = buildAnnotatedString {
                        append(contact.displayName)
                        if (state.existing[index] != null) withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) { append(" "); append(exists) }
                    }
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    // Pinned: the progress of a large import over the button (SPEC §10.4)
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running != null && running.total > PROGRESS_FROM) {
            Text(
                stringResource(R.string.label_import_progress, format.format(running.done), format.format(running.total)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearWavyProgressIndicator(progress = { running.done.toFloat() / running.total }, modifier = Modifier.fillMaxWidth())
        }
        val canImport = viewModel.account != null && if (single) state.existing[0] == null || viewModel.importAnyway else viewModel.selected.isNotEmpty()
        Button(
            onClick = { if (!busy) viewModel.import(thisPhone) },
            // Busy keeps its fill and stops answering (SPEC §10.4)
            enabled = canImport || busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (busy) {
                LoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.label_importing))
            } else {
                Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.button_import))
            }
        }
    }
}

/** An outlined information card, as the Accounts page's note. */
@Composable
private fun Note(icon: Int, text: String, padded: Boolean = true) {
    Row(
        Modifier
            .then(if (padded) Modifier.padding(horizontal = 16.dp, vertical = 8.dp) else Modifier)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The one contact as its page will show it (SPEC §10.2): the header and the details, without quick actions, Sources or Actions. */
@Composable
private fun Preview(contact: CardContact, photo: String?, region: String) {
    val company = contact.values[EditKind.COMPANY]?.firstOrNull()?.let { listOf(it.part("data1"), it.part("data4")).filter { p -> p.isNotEmpty() }.joinToString(", ") }?.ifEmpty { null }
    val phonetic = listOfNotNull(contact.name["data7"], contact.name["data9"]).joinToString(" ").ifEmpty { null }
    Column {
        Header(contact.displayName, contact.displayName, photo, contact.values[EditKind.NICKNAME]?.firstOrNull()?.part("data1"), phonetic, company, null)
        val details = details(contact)
        for (kind in DetailKind.entries) {
            val values = details[kind] ?: continue
            DetailGroup(kind, values, region, onCopy = {}, onMessage = {}, actions = false) {}
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** The contact's values as the page's details. */
private fun details(contact: CardContact): Map<DetailKind, List<Detail>> {
    val kinds = mapOf(
        EditKind.PHONE to DetailKind.PHONE, EditKind.EMAIL to DetailKind.EMAIL, EditKind.ADDRESS to DetailKind.ADDRESS, EditKind.WEBSITE to DetailKind.WEBSITE,
        EditKind.DATE to DetailKind.DATE, EditKind.RELATION to DetailKind.RELATION, EditKind.IM to DetailKind.IM, EditKind.SIP to DetailKind.SIP, EditKind.NOTE to DetailKind.NOTE,
    )
    var id = 0L
    return kinds.mapNotNull { (edit, kind) ->
        val values = contact.values[edit].orEmpty().map { value -> Detail(kind, text(edit, value), value.type, value.label, value.isDefault, 0, id++, value.protocol) }
        if (values.isEmpty()) null else kind to values
    }.toMap()
}

/** A value's text as the storage would give it: an address as lines (street, postcode and city, region, country). */
private fun text(kind: EditKind, value: CardValue): String = when (kind) {
    EditKind.ADDRESS -> listOf(
        value.part("data4"),
        listOf(value.part("data9"), value.part("data7")).filter { it.isNotEmpty() }.joinToString(" "),
        value.part("data8"),
        value.part("data10"),
    ).filter { it.isNotEmpty() }.joinToString("\n")
    else -> value.part("data1")
}
