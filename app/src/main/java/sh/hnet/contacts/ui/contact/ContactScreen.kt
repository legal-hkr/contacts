@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.contact

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.model.ContactDate
import sh.hnet.contacts.model.ContactDetails
import sh.hnet.contacts.model.Detail
import sh.hnet.contacts.model.DetailKind
import sh.hnet.contacts.model.Source
import sh.hnet.contacts.ui.components.Avatar
import sh.hnet.contacts.ui.components.EmptyState
import sh.hnet.contacts.ui.components.TooltipIconButton
import sh.hnet.contacts.ui.components.groupShapes
import sh.hnet.contacts.ui.components.settingsRowColors

/**
 * A contact's page (SPEC §6.1–§6.6): the header, the quick actions, every group of details with its
 * taps, long press to copy and long values scrolling sideways, where the contact comes from, and
 * the star in the top bar. Edit, the actions and Delete join with their stages.
 */
@Composable
fun ContactScreen(
    lookupKey: String,
    id: Long,
    snackbars: SnackbarHostState,
    messageScope: CoroutineScope,
    onBack: () -> Unit,
    onOpenContact: (Long, String) -> Unit,
    onSearch: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    val viewModel: ContactViewModel = viewModel(key = "contact:$lookupKey") { ContactViewModel(app, lookupKey, id) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val headerGone by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val starFailed = stringResource(R.string.error_star_failed)
    val contact = (state as? ContactState.Ready)?.contact

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                // The name comes up in the bar once the header has scrolled away
                AnimatedVisibility(visible = headerGone && contact != null, enter = fadeIn(), exit = fadeOut()) {
                    Text(contact?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
            actions = {
                if (contact != null) {
                    TooltipIconButton(
                        if (contact.starred) R.drawable.ic_star_filled else R.drawable.ic_star,
                        stringResource(if (contact.starred) R.string.tooltip_unstar else R.string.tooltip_star),
                        onClick = {
                            viewModel.setStarred(!contact.starred) {
                                messageScope.launch { snackbars.showSnackbar(starFailed, duration = SnackbarDuration.Short) }
                            }
                        },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = if (contact.starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            },
        )
        when (val current = state) {
            ContactState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            ContactState.Gone -> EmptyState(R.drawable.ic_person_off, stringResource(R.string.msg_contact_gone)) {
                Button(onClick = onBack) { Text(stringResource(R.string.button_back_to_list)) }
            }
            is ContactState.Ready -> ContactContent(current.contact, listState, viewModel, snackbars, messageScope, onOpenContact, onSearch)
        }
    }
}

@Composable
private fun ContactContent(
    contact: ContactDetails,
    listState: LazyListState,
    viewModel: ContactViewModel,
    snackbars: SnackbarHostState,
    messageScope: CoroutineScope,
    onOpenContact: (Long, String) -> Unit,
    onSearch: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as ContactsApplication
    val region = remember { app.contacts.region() }
    var choosing by remember { mutableStateOf<Choice?>(null) }
    val scope = rememberCoroutineScope()
    val noApp = stringResource(R.string.error_no_app)
    val noMaps = stringResource(R.string.msg_no_maps_app)
    val clipboard = LocalClipboard.current
    val copy: (String) -> Unit = { text ->
        // No snackbar: the system shows its own confirmation of a copy (Android 13 and later)
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))) }
    }
    val reportNoApp: (String) -> Unit = { what -> messageScope.launch { snackbars.showSnackbar(noApp.format(what), duration = SnackbarDuration.Short) } }
    val phones = contact.of(DetailKind.PHONE)
    val emails = contact.of(DetailKind.EMAIL)
    val call = stringResource(R.string.tooltip_call)
    val message = stringResource(R.string.tooltip_message)
    val email = stringResource(R.string.tooltip_email)
    val map = stringResource(R.string.tooltip_map)
    val website = stringResource(R.string.label_website)

    fun dial(number: String) = open(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))) { reportNoApp(call) }
    fun text(number: String) = open(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))) { reportNoApp(message) }
    fun mail(address: String) = open(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null))) { reportNoApp(email) }
    fun showOnMap(address: String) = open(context, Intent(Intent.ACTION_VIEW, ("geo:0,0?q=" + Uri.encode(address)).toUri())) {
        copy(address)
        messageScope.launch { snackbars.showSnackbar(noMaps, duration = SnackbarDuration.Short) }
    }

    /** Which number or address a quick action uses (SPEC §6.3.1): the default, the only one, or the user's choice. */
    fun pick(values: List<Detail>, action: Action, run: (String) -> Unit) {
        val default = values.firstOrNull { it.isDefault }
        when {
            default != null -> run(default.value)
            values.size == 1 -> run(values[0].value)
            else -> choosing = Choice(action, values)
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
        item(key = "header") { Header(contact) }
        if (contact.readOnly) {
            item(key = "read-only") { ReadOnlyCard(contact.sources) }
        }
        item(key = "actions") {
            val buttons = buildList {
                if (phones.isNotEmpty()) {
                    add(QuickAction(R.drawable.ic_call, call) { pick(phones, Action.CALL, ::dial) })
                    add(QuickAction(R.drawable.ic_chat, message) { pick(phones, Action.MESSAGE, ::text) })
                }
                if (emails.isNotEmpty()) add(QuickAction(R.drawable.ic_mail, email) { pick(emails, Action.EMAIL, ::mail) })
                contact.of(DetailKind.ADDRESS).firstOrNull()?.let { address -> add(QuickAction(R.drawable.ic_map, map) { showOnMap(address.value) }) }
            }
            QuickActions(buttons)
        }
        for (kind in DetailKind.entries) {
            val values = contact.of(kind)
            if (values.isEmpty()) continue
            item(key = "group:$kind") {
                DetailGroup(kind, values, region, onCopy = copy, onMessage = ::text) { detail ->
                    when (kind) {
                        DetailKind.PHONE -> dial(detail.value)
                        DetailKind.EMAIL -> mail(detail.value)
                        DetailKind.ADDRESS -> showOnMap(detail.value)
                        DetailKind.WEBSITE -> {
                            val url = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(detail.value)) detail.value else "https://${detail.value}"
                            open(context, Intent(Intent.ACTION_VIEW, url.toUri())) { reportNoApp(website) }
                        }
                        // The day in a calendar app, if one handles it; else nothing
                        DetailKind.DATE -> dayMillis(detail.value)?.let { millis ->
                            open(context, Intent(Intent.ACTION_VIEW, "content://com.android.calendar/time/$millis".toUri())) {}
                        }
                        // Exactly one contact of that name opens; otherwise the list searches for the name
                        DetailKind.RELATION -> scope.launch {
                            val only = viewModel.onlyContactNamed(detail.value)
                            if (only != null) onOpenContact(only.first, only.second) else onSearch(detail.value)
                        }
                        DetailKind.IM -> copy(detail.value)
                        DetailKind.SIP -> open(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("sip", detail.value, null))) { reportNoApp(call) }
                        DetailKind.NOTE -> Unit
                    }
                }
            }
        }
        item(key = "sources") { Sources(contact.sources) }
        item(key = "end") { Spacer(Modifier.height(24.dp)) }
    }

    choosing?.let { choice ->
        WhichSheet(contact.name, choice, region, onPick = { detail, remember ->
            choosing = null
            if (remember) viewModel.setDefault(detail.dataId)
            when (choice.action) {
                Action.CALL -> dial(detail.value)
                Action.MESSAGE -> text(detail.value)
                Action.EMAIL -> mail(detail.value)
            }
        }, onDismiss = { choosing = null })
    }
}

/** Starts another app's activity; [onMissing] when none can take it. */
private fun open(context: Context, intent: Intent, onMissing: () -> Unit) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        onMissing()
    }
}

/** The start of a date's day this year or in its year, in the phone's zone, for a calendar app (SPEC §6.4). */
private fun dayMillis(value: String): Long? {
    val day = when (val date = ContactDate.parse(value)) {
        is ContactDate.Full -> date.date
        is ContactDate.NoYear -> date.day.atYear(LocalDate.now().year)
        null -> return null
    }
    return day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

enum class Action { CALL, MESSAGE, EMAIL }

class Choice(val action: Action, val values: List<Detail>)

private class QuickAction(@param:DrawableRes val icon: Int, val label: String, val onClick: () -> Unit)

/** The header (SPEC §6.2): the photo or letter avatar at 112 dp, the name, then nickname, phonetic name and company, each only when present. */
@Composable
private fun Header(contact: ContactDetails) {
    val photoLabel = stringResource(R.string.content_description_photo, contact.name)
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(contact.name, contact.lookupKey, contact.photo, 112.dp, Modifier.semantics { if (contact.photo != null) contentDescription = photoLabel })
        Text(
            contact.name,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp).semantics { heading() },
        )
        for (line in listOfNotNull(contact.nickname, contact.phonetic, contact.company)) {
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** A contact only in read-only accounts (SPEC §6.6): says where it can be changed. */
@Composable
private fun ReadOnlyCard(sources: List<Source>) {
    val accounts = sources.map { it.account.name.ifEmpty { it.app.orEmpty() } }.distinct().joinToString(", ")
    val apps = sources.mapNotNull { it.app }.distinct().joinToString(", ").ifEmpty { accounts }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.msg_read_only_contact, accounts, apps), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The quick actions (SPEC §6.3): icon-only tonal buttons in one connected group, 56 dp high and of
 * equal width, only those that apply; outer corners 20 dp, inner 6 dp. Absent when none applies.
 */
@Composable
private fun QuickActions(actions: List<QuickAction>) {
    if (actions.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        actions.forEachIndexed { index, action ->
            val start = if (index == 0) 20.dp else 6.dp
            val end = if (index == actions.lastIndex) 20.dp else 6.dp
            // The weight on a box of the row itself: given to the tooltip's own wrapper it didn't reach the row, and the first button took the whole width
            Box(Modifier.weight(1f)) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                    tooltip = { PlainTooltip { Text(action.label) } },
                    state = rememberTooltipState(),
                ) {
                    FilledTonalButton(
                        onClick = action.onClick,
                        shape = RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end),
                        modifier = Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = action.label },
                    ) {
                        Icon(painterResource(action.icon), contentDescription = null)
                    }
                }
            }
        }
    }
}

@DrawableRes
private fun icon(kind: DetailKind, detail: Detail): Int = when (kind) {
    DetailKind.PHONE -> R.drawable.ic_call
    DetailKind.EMAIL -> R.drawable.ic_mail
    DetailKind.ADDRESS -> R.drawable.ic_location_on
    DetailKind.WEBSITE -> R.drawable.ic_public
    DetailKind.DATE -> if (detail.type == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) R.drawable.ic_cake else R.drawable.ic_event
    DetailKind.RELATION -> R.drawable.ic_family_restroom
    DetailKind.IM -> R.drawable.ic_forum
    DetailKind.SIP -> R.drawable.ic_dialer_sip
    DetailKind.NOTE -> R.drawable.ic_notes
}

/**
 * One group of details (SPEC §6.4) as a segmented list: the kind's icon in a box on the first row,
 * the value, its type under it ("…, default" for the default), a tap that hands it on, long press
 * to copy. One-line values scroll sideways instead of wrapping (SPEC §16.2); a note wraps as prose.
 */
@Composable
private fun DetailGroup(kind: DetailKind, values: List<Detail>, region: String, onCopy: (String) -> Unit, onMessage: (String) -> Unit, onTap: (Detail) -> Unit) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    val message = stringResource(R.string.tooltip_message)
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        values.forEachIndexed { index, detail ->
            val type = typeLabel(detail)
            val supporting = if (detail.isDefault && type != null) stringResource(R.string.label_default_suffix, type) else type
            val shown = when (kind) {
                DetailKind.PHONE -> app.contacts.displayNumber(detail.value, region)
                DetailKind.DATE -> dateText(detail.value)
                else -> detail.value
            }
            SegmentedListItem(
                onClick = { onTap(detail) },
                onLongClick = { onCopy(detail.value) },
                shapes = groupShapes(index, values.size),
                colors = settingsRowColors(),
                leadingContent = {
                    if (index == 0) {
                        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                            Icon(painterResource(icon(kind, detail)), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    } else {
                        Spacer(Modifier.width(40.dp))
                    }
                },
                supportingContent = supporting?.let { { Text(it) } },
                trailingContent = if (kind == DetailKind.PHONE) ({ TooltipIconButton(R.drawable.ic_chat, message, { onMessage(detail.value) }) }) else null,
            ) {
                if (kind == DetailKind.NOTE) {
                    Text(shown)
                } else {
                    // Every stored line of an address is its own line; each scrolls sideways on its own
                    Column {
                        for (line in shown.lines().filter { it.isNotBlank() }) {
                            Text(line, maxLines = 1, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()))
                        }
                    }
                }
            }
        }
    }
}

/** Where the contact comes from (SPEC §6.5): one row per entry, its account and the account's app; read-only ones marked. */
@Composable
private fun Sources(sources: List<Source>) {
    if (sources.isEmpty()) return
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp)) {
        Text(
            stringResource(R.string.title_sources),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp).semantics { heading() },
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            sources.forEachIndexed { index, source ->
                val app = if (source.local) stringResource(R.string.label_local_contacts) else source.app ?: source.account.type
                SegmentedListItem(
                    shapes = groupShapes(index, sources.size),
                    colors = settingsRowColors(),
                    leadingContent = {
                        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                            Icon(painterResource(if (source.local) R.drawable.ic_smartphone else R.drawable.ic_cloud_sync), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    },
                    supportingContent = { Text(if (source.readOnly) stringResource(R.string.label_read_only_suffix, app) else app) },
                    trailingContent = if (source.readOnly) ({ Icon(painterResource(R.drawable.ic_lock), contentDescription = null) }) else null,
                ) {
                    Text(if (source.local) stringResource(R.string.label_this_phone) else source.account.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
