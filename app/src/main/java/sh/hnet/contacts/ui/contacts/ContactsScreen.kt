@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.NumberFormat
import kotlinx.coroutines.delay
import sh.hnet.contacts.R
import sh.hnet.contacts.model.ListEntry
import sh.hnet.contacts.ui.components.Avatar
import sh.hnet.contacts.ui.components.ContactsAccess
import sh.hnet.contacts.ui.components.EmptyState
import sh.hnet.contacts.ui.components.TooltipIconButton
import sh.hnet.contacts.ui.components.rememberContactsAccessAsk

/**
 * The app's root (SPEC §4): the top bar with Settings, then the contact list with letter headers,
 * or one of its states. Search, favorites, the contact page and the FAB come with later stages.
 */
@Composable
fun ContactsScreen(onOpenSettings: () -> Unit, viewModel: ListViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    LaunchedEffect(locale) { viewModel.setLocale(locale) }
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAccess()
        onPauseOrDispose {}
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.title_contacts), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
            actions = { TooltipIconButton(R.drawable.ic_settings, stringResource(R.string.content_description_settings), onOpenSettings) },
        )
        when (val current = state) {
            ListState.Loading -> Loading()
            ListState.NoAccess -> NoAccess(onAnswer = viewModel::refreshAccess)
            ListState.NoContacts -> ListEmptyState(stringResource(R.string.msg_no_contacts), stringResource(R.string.desc_no_contacts))
            is ListState.AllHidden -> ListEmptyState(
                stringResource(R.string.msg_all_hidden),
                stringResource(R.string.desc_all_hidden, NumberFormat.getIntegerInstance(locale).format(current.total)),
            )
            is ListState.Ready -> ContactList(current)
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
 * app's page in the system settings; the list loads by itself once access is given.
 */
@Composable
private fun NoAccess(onAnswer: () -> Unit) {
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

/** The list (SPEC §4.5): letter headers and rows, one lazy column keyed by the contacts' ids, so a change keeps the first visible row in place. */
@Composable
private fun ContactList(state: ListState.Ready) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = WindowInsets.navigationBars.asPaddingValues(),
    ) {
        items(state.entries, key = { it.key }, contentType = { it::class }) { entry ->
            when (entry) {
                is ListEntry.Header -> Text(
                    entry.letter,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
                )
                is ListEntry.Contact -> {
                    val contact = entry.contact
                    val company = contact.company.takeIf { state.companyLine }
                    ListItem(
                        leadingContent = { Avatar(contact.name, contact.lookupKey, contact.photo, 40.dp) },
                        supportingContent = company?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    ) {
                        Text(contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
