@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.contacts.ui.contacts

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.R
import sh.hnet.contacts.model.ContactRow
import sh.hnet.contacts.model.Detail
import sh.hnet.contacts.model.DetailKind
import sh.hnet.contacts.model.MatchKind
import sh.hnet.contacts.model.SearchResult
import sh.hnet.contacts.ui.components.Avatar
import sh.hnet.contacts.ui.components.EmptyState
import sh.hnet.contacts.ui.components.TooltipIconButton
import sh.hnet.contacts.ui.components.groupShapes
import sh.hnet.contacts.ui.components.onTouchDown
import sh.hnet.contacts.ui.components.settingsRowColors
import sh.hnet.contacts.ui.contact.typeLabel

/**
 * The search field in place of the top bar (SPEC §5.1), as Music's: a pill with the arrow that
 * closes the search at the start and Clear at the end once there is text. The keyboard opens with it.
 */
@Composable
fun SearchField(field: TextFieldState, query: String, onClose: () -> Unit, onClear: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    // The input alone: a collapsed SearchBar takes no typing (it hands that to its expanded form), so the field carries the bar's container itself
    SearchBarDefaults.InputField(
        textFieldState = field,
        searchBarState = rememberSearchBarState(),
        onSearch = { keyboard?.hide() },
        placeholder = { Text(stringResource(R.string.hint_search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onClose) },
        trailingIcon = if (query.isNotEmpty()) {
            { TooltipIconButton(R.drawable.ic_close, stringResource(R.string.content_description_clear), onClear) }
        } else null,
        colors = SearchBarDefaults.inputFieldColors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
            .focusRequester(focus),
    )
}

/**
 * The results (SPEC §5.3, §5.4): rows as the list's, in up to two groups – Names, then Other
 * fields, headed only when both are there –, the matched part highlighted, and a second line
 * "type: value" when the match is elsewhere than in the name. No results: the query, what is
 * searched, and Search all when hidden accounts may hold more.
 */
@Composable
fun SearchResults(state: SearchState, onOpenContact: (ContactRow) -> Unit, onSearchAll: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    if (state.results.isEmpty()) {
        EmptyState(R.drawable.ic_search_off, stringResource(R.string.msg_no_results, state.query.trim()), Modifier.imePadding()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 296.dp)) {
                val scope = stringResource(R.string.desc_search_scope)
                val line = if (state.hiddenExist && !state.all) scope + " " + stringResource(R.string.desc_hidden_not_searched) else scope
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                if (state.hiddenExist && !state.all) TextButton(onClick = onSearchAll) { Text(stringResource(R.string.button_search_all)) }
            }
        }
        return
    }
    val listState = rememberLazyListState()
    // Scrolling the results puts the keyboard away
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) keyboard?.hide() }
    val names = state.results.filter { it.inNames }
    val others = state.results.filterNot { it.inNames }
    val headed = names.isNotEmpty() && others.isNotEmpty()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().imePadding(), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
        for ((group, title) in listOf(names to R.string.label_search_names, others to R.string.label_search_other_fields)) {
            if (group.isEmpty()) continue
            if (headed) {
                item(key = "header:$title") {
                    Text(
                        stringResource(title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
                    )
                }
            }
            // One segmented group per kind of match, as the list's letters and News's results (the owner, 18 Nov 2026)
            itemsIndexed(group, key = { _, it -> it.contact.id }) { place, result -> ResultRow(result, place, group.size, onOpenContact) }
        }
    }
}

@Composable
private fun ResultRow(result: SearchResult, place: Int, size: Int, onOpenContact: (ContactRow) -> Unit) {
    val contact = result.contact
    val keyboard = LocalSoftwareKeyboardController.current
    val headline = if (result.field == null) highlighted(contact.name, result.range) else AnnotatedString(contact.name)
    val app = LocalContext.current.applicationContext as ContactsApplication
    SegmentedListItem(
        onClick = {
            keyboard?.hide()
            onOpenContact(contact)
        },
        shapes = groupShapes(place, size),
        colors = settingsRowColors(),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = if (place < size - 1) 2.dp else 0.dp).onTouchDown(contact.id, onPress = { app.prefetch(contact.lookupKey, contact.id) }, onMove = app::cancelPrefetch),
        leadingContent = { Avatar(contact.name, contact.lookupKey, contact.photo, 40.dp, initials = contact.initials) },
        supportingContent = result.field?.let { field -> { Text(matchLine(field.kind, field.type, field.label, result.text, result.range), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
    ) {
        Text(headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The matched part in the primary color, medium weight (SPEC §5.3). */
@Composable
private fun highlighted(text: String, range: IntRange?): AnnotatedString {
    val style = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    return buildAnnotatedString {
        if (range == null || range.first < 0 || range.last >= text.length) {
            append(text)
        } else {
            append(text.substring(0, range.first))
            withStyle(style) { append(text.substring(range)) }
            append(text.substring(range.last + 1))
        }
    }
}

/** "Mobile: 601 234 567", "Nickname: Janek", "Note: …words…" (SPEC §5.3), the value highlighted. */
@Composable
private fun matchLine(kind: MatchKind, type: Int, label: String?, value: String, range: IntRange?): AnnotatedString {
    val marker = "\u0000"
    val template = when (kind) {
        MatchKind.PHONE -> {
            val typeName = typeLabel(Detail(DetailKind.PHONE, value, type, label, false, 0, 0)).orEmpty()
            stringResource(R.string.label_match_phone, typeName, marker)
        }
        else -> stringResource(templateOf(kind), marker)
    }
    val before = template.substringBefore(marker)
    val after = template.substringAfter(marker, "")
    val highlightedValue = highlighted(value, range)
    return buildAnnotatedString {
        append(before)
        append(highlightedValue)
        append(after)
    }
}

@StringRes
private fun templateOf(kind: MatchKind): Int = when (kind) {
    MatchKind.NICKNAME -> R.string.label_match_nickname
    MatchKind.PHONETIC -> R.string.label_match_phonetic
    MatchKind.EMAIL -> R.string.label_match_email
    MatchKind.COMPANY -> R.string.label_match_company
    MatchKind.NOTE -> R.string.label_match_note
    // Never asked for: names have no second line, phones their own template
    MatchKind.NAME, MatchKind.PHONE -> R.string.label_match_nickname
}
