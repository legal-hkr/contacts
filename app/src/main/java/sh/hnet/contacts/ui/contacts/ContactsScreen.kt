@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.contacts

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.NumberFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.data.Deletion
import sh.hnet.contacts.model.ContactRow
import sh.hnet.contacts.model.ListEntry
import sh.hnet.contacts.ui.components.Avatar
import sh.hnet.contacts.ui.components.ContactsAccess
import sh.hnet.contacts.ui.components.EmptyState
import sh.hnet.contacts.ui.components.FastScroller
import sh.hnet.contacts.ui.components.TooltipIconButton
import sh.hnet.contacts.ui.components.groupShapes
import sh.hnet.contacts.ui.components.onTouchDown
import sh.hnet.contacts.ui.components.rememberContactsAccessAsk
import sh.hnet.contacts.ui.components.settingsRowColors

/**
 * The app's root (SPEC §4): the top bar with Search and Settings, then favorites and the contact
 * list with letter headers and the fast scroller, or one of its states; with the search open, its
 * field in the bar and its results in place of the list (SPEC §5). The FAB comes with the editor.
 */
@Composable
fun ContactsScreen(
    onOpenSettings: () -> Unit,
    onOpenContact: (ContactRow) -> Unit,
    onNewContact: () -> Unit,
    onImport: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onMerge: (List<Long>) -> Unit,
    onDelete: (Deletion, String) -> Unit,
    snackbars: SnackbarHostState,
    messageScope: CoroutineScope,
    viewModel: ListViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    LaunchedEffect(locale) { viewModel.setLocale(locale) }
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAccess()
        onPauseOrDispose {}
    }
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val selection = viewModel.selection
    // Back ends a selection, then closes the search (SPEC §3.3)
    BackHandler(enabled = searching && !selection.active) { viewModel.closeSearch() }
    BackHandler(enabled = selection.active) { selection.clear() }
    // The rows on the list now, for the selection's actions
    val rows = (state as? ListState.Ready)?.entries.orEmpty().mapNotNull { (it as? ListEntry.Contact)?.contact }
    // A contact deleted or gone meanwhile drops out of the selection
    LaunchedEffect(rows) { if (selection.active) selection.set(selection.ids.filter { id -> rows.any { it.id == id } }.toSet()) }
    // Search only where there is something to search: not without access or contacts (SPEC §4.8)
    val searchable = state !is ListState.NoAccess && state !is ListState.NoContacts
    // The FAB stays while the list scrolls (the owner, 17 Nov 2026; SPEC §4.10 hid it)
    val fab = !searching && !selection.active && (state is ListState.Loading || state is ListState.AllHidden || state is ListState.Ready)
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        if (selection.active) {
            SelectionBar(selection.ids.size, allSelected = selection.ids.size == rows.size, onClose = selection::clear, onSelectAll = {
                if (selection.ids.size == rows.size) selection.clear() else selection.set(rows.map { it.id }.toSet())
            })
        } else if (searching) {
            SearchField(viewModel.searchField, query, onClose = viewModel::closeSearch, onClear = viewModel::clearQuery)
        } else {
            TopAppBar(
                title = { Text(stringResource(R.string.title_contacts), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
                actions = {
                    if (searchable) TooltipIconButton(R.drawable.ic_search, stringResource(R.string.content_description_search), { viewModel.openSearch() })
                    TooltipIconButton(R.drawable.ic_settings, stringResource(R.string.content_description_settings), onOpenSettings)
                },
            )
        }
        if (searching && searchable) {
            search?.let { SearchResults(it, onOpenContact, onSearchAll = viewModel::searchAll) }
            return@Column
        }
        when (val current = state) {
            ListState.Loading -> Loading()
            ListState.NoAccess -> NoAccess(onAnswer = viewModel::refreshAccess)
            ListState.NoContacts -> ListEmptyState(stringResource(R.string.msg_no_contacts), stringResource(R.string.desc_no_contacts)) {
                Button(onClick = onNewContact) {
                    Icon(painterResource(R.drawable.ic_person_add), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.button_new_contact))
                }
                FilledTonalButton(onClick = onImport) {
                    Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.button_import_vcard))
                }
            }
            is ListState.AllHidden -> ListEmptyState(
                stringResource(R.string.msg_all_hidden),
                stringResource(R.string.desc_all_hidden, NumberFormat.getIntegerInstance(locale).format(current.total)),
            ) {
                FilledTonalButton(onClick = onOpenAccounts) {
                    Icon(painterResource(R.drawable.ic_tune), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.button_choose_accounts))
                }
            }
            is ListState.Ready -> ContactList(current, selection, onOpenContact, onOpenDuplicates)
        }
    }
        AnimatedVisibility(
            visible = fab,
            enter = scaleIn(),
            exit = scaleOut(),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
        ) {
            FloatingActionButton(onClick = onNewContact, containerColor = MaterialTheme.colorScheme.primaryContainer) {
                Icon(painterResource(R.drawable.ic_person_add), contentDescription = stringResource(R.string.content_description_new_contact))
            }
        }
        if (selection.active) {
            SelectionToolbar(
                rows.filter { it.id in selection.ids }, snackbars, messageScope,
                onDone = selection::clear,
                onMerge = onMerge,
                onDelete = onDelete,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
            )
        }
    }
}

/** The loading indicator, only once loading takes a moment (SPEC §4.8). */
@Composable
private fun Loading() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(300)
        shown = true
    }
    if (shown) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
}

/** An empty state of the list (SPEC §4.9): the app's mark in the family's shape, the sentence, a supporting line and at most two buttons. */
@Composable
private fun ListEmptyState(text: String, supporting: String, buttons: (@Composable () -> Unit)? = null) {
    // The app's mark is drawn on the launcher icon's canvas, hence the larger size
    EmptyState(R.drawable.ic_launcher_monochrome, text, iconSize = 108.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.widthIn(max = 296.dp)) {
            Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            buttons?.invoke()
        }
    }
}

/**
 * No access (SPEC §4.8): the button asks while the system still shows its dialog, then leads to the
 * app's page in the system settings; the list loads by itself once access is given. Also the one-contact widget's choice of a contact.
 */
@Composable
internal fun NoAccess(onAnswer: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val ask = rememberContactsAccessAsk(onAnswer)
    var mayAsk by remember { mutableStateOf(ContactsAccess.mayAsk(context, activity)) }
    LifecycleResumeEffect(Unit) {
        mayAsk = ContactsAccess.mayAsk(context, activity)
        onPauseOrDispose {}
    }
    ListEmptyState(stringResource(R.string.msg_no_permission), stringResource(R.string.desc_no_permission)) {
        if (mayAsk) {
            Button(onClick = ask) { Text(stringResource(R.string.button_allow_access)) }
        } else {
            Button(onClick = { ContactsAccess.openAppSettings(context) }) { Text(stringResource(R.string.button_open_app_settings)) }
        }
    }
}

/**
 * The duplicates card (SPEC §4.4): a merge icon, "Possible duplicates: 4", one sentence, Not now and
 * Review. Not now puts it away until there are more suggestions than now.
 */
@Composable
private fun DuplicatesCard(count: Int, onReview: () -> Unit, onNotNow: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_call_merge), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column {
                Text(stringResource(R.string.title_possible_duplicates, count), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.desc_possible_duplicates), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.align(Alignment.End).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onNotNow) { Text(stringResource(R.string.button_not_now)) }
            FilledTonalButton(onClick = onReview) { Text(stringResource(R.string.button_review)) }
        }
    }
}

/** A selected row's avatar (SPEC §4.7): a primary circle with a check. */
@Composable
private fun SelectedMark(size: Dp) {
    Box(Modifier.size(size).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
    }
}

/** The favorites row's place in the fast scroller's index (SPEC §4.6). */
private const val STAR = "★"

/**
 * The list (SPEC §4.3–§4.6): the favorites row first, then letter headers and rows, in one lazy
 * column keyed by the contacts' ids, so a change keeps the first visible row in place; the fast
 * scroller with the letter index on the end edge.
 */
@Composable
private fun ContactList(state: ListState.Ready, selection: Selection, onOpenContact: (ContactRow) -> Unit, onOpenDuplicates: () -> Unit) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    val duplicates by app.duplicates.collectAsStateWithLifecycle()
    var dismissed by remember { mutableIntStateOf(app.duplicatesDismissed) }
    // Fewer suggestions than when put away (some merged): the next new one shows the card again
    LaunchedEffect(duplicates.size) { if (duplicates.size < dismissed) { dismissed = duplicates.size; app.duplicatesDismissed = duplicates.size } }
    val listState = rememberLazyListState()
    val favorites = state.favorites.isNotEmpty()
    // The letter of every item, for the fast scroller's bubble
    val sections = remember(state, duplicates.size > dismissed, selection.active) {
        val sections = ArrayList<String>(state.entries.size + 2)
        if (favorites && !selection.active) sections += STAR
        // The duplicates card files with the first letter's place
        if (duplicates.size > dismissed && !selection.active) sections += state.entries.firstOrNull()?.let { (it as? ListEntry.Header)?.letter }.orEmpty()
        var letter = ""
        for (entry in state.entries) {
            if (entry is ListEntry.Header) letter = entry.letter
            sections += letter
        }
        sections
    }
    // Each letter's rows are one segmented group (the owner, 18 Nov 2026, as News's days): a row's place and its group's size
    val places = remember(state) {
        val result = HashMap<Any, Pair<Int, Int>>()
        var group = ArrayList<Any>()
        fun close() {
            group.forEachIndexed { i, key -> result[key] = i to group.size }
            group = ArrayList()
        }
        for (entry in state.entries) if (entry is ListEntry.Header) close() else group += entry.key
        close()
        result
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Room for the selection's toolbar under the last row
            contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + if (selection.active) 88.dp else 0.dp),
        ) {
            // Favorites leave the list while selecting (SPEC §4.7)
            if (favorites && !selection.active) {
                item(key = "favorites", contentType = "favorites") { Favorites(state.favorites, onOpenContact, onSelect = selection::toggle) }
            }
            // The duplicates card (SPEC §4.4) while there are more suggestions than when it was put away
            if (!selection.active && duplicates.size > dismissed) {
                item(key = "duplicates", contentType = "duplicates") {
                    DuplicatesCard(duplicates.size, onReview = onOpenDuplicates, onNotNow = {
                        app.duplicatesDismissed = duplicates.size
                        dismissed = duplicates.size
                    })
                }
            }
            items(state.entries, key = { it.key }, contentType = { it::class }) { entry ->
                when (entry) {
                    is ListEntry.Header -> Text(
                        entry.letter,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
                    )
                    is ListEntry.Contact -> {
                        val contact = entry.contact
                        val company = contact.company.takeIf { state.companyLine }
                        val selected = contact.id in selection.ids
                        val (place, size) = places[entry.key] ?: (0 to 1)
                        SegmentedListItem(
                            // Selecting: a tap toggles the row; otherwise it opens the contact, and a long press starts the selection
                            onClick = { if (selection.active) selection.toggle(contact.id) else onOpenContact(contact) },
                            onLongClick = { selection.toggle(contact.id) },
                            shapes = groupShapes(place, size),
                            modifier = Modifier
                                .padding(start = 16.dp, end = 16.dp, bottom = if (place < size - 1) 2.dp else 0.dp)
                                .onTouchDown(contact.id, onPress = { if (!selection.active) app.prefetch(contact.lookupKey, contact.id) }, onMove = app::cancelPrefetch),
                            colors = if (selected) ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else settingsRowColors(),
                            leadingContent = { if (selected) SelectedMark(40.dp) else Avatar(contact.name, contact.lookupKey, contact.photo, 40.dp, initials = contact.initials) },
                            supportingContent = company?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                        ) {
                            Text(contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        FastScroller(
            listState, sections,
            Modifier.align(Alignment.TopEnd).padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        )
    }
}

/**
 * Favorites (SPEC §4.3): a header and one row of 56 dp avatars with a short name under each, which
 * scrolls sideways however many there are; a tap opens the contact.
 */
@Composable
private fun Favorites(favorites: List<ContactRow>, onOpenContact: (ContactRow) -> Unit, onSelect: (Long) -> Unit) {
    val app = LocalContext.current.applicationContext as ContactsApplication
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Text(
            stringResource(R.string.label_favorites),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp).semantics { heading() },
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(favorites, key = { it.id }) { contact ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(72.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .onTouchDown(contact.id, onPress = { app.prefetch(contact.lookupKey, contact.id) }, onMove = app::cancelPrefetch)
                        .combinedClickable(onLongClick = { onSelect(contact.id) }) { onOpenContact(contact) }
                        .padding(vertical = 4.dp)
                        .semantics(mergeDescendants = true) { contentDescription = contact.name },
                ) {
                    Avatar(contact.name, contact.lookupKey, contact.photo, 56.dp, initials = contact.initials)
                    Text(
                        contact.shortName ?: contact.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics {},
                    )
                }
            }
        }
    }
}
