package sh.hnet.contacts.ui.contacts

import android.app.Application
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.model.ContactList
import sh.hnet.contacts.model.ContactRow
import sh.hnet.contacts.model.ListEntry
import sh.hnet.contacts.model.Search
import sh.hnet.contacts.model.SearchResult
import sh.hnet.contacts.model.favorites

/** The list's states (SPEC §4.8). */
sealed interface ListState {
    data object Loading : ListState
    data object NoAccess : ListState

    /** No contacts in any account. */
    data object NoContacts : ListState

    /** Contacts exist ([total]), but every account that has them is hidden. */
    data class AllHidden(val total: Int) : ListState

    data class Ready(val entries: List<ListEntry>, val favorites: List<ContactRow>, val companyLine: Boolean) : ListState
}

/**
 * The contact list: read from the storage when it opens and again after every change there
 * (debounced 300 ms, SPEC §4.11), after a change of Settings › List or of the accounts shown, and
 * in the app's language, which the screen hands over.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ListViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ContactsApplication
    private val access = MutableStateFlow(app.contacts.hasAccess())
    private val locale = MutableStateFlow(Locale.getDefault())

    val state: StateFlow<ListState> = access.flatMapLatest { granted ->
        if (!granted) return@flatMapLatest flowOf(ListState.NoAccess)
        combine(app.contacts.changes.debounce(300).onStart { emit(Unit) }, app.preferences.listSettings, app.preferences.hiddenAccounts, locale) { _, settings, hidden, locale -> Triple(settings, hidden, locale) }
            .mapLatest { (settings, hidden, locale) ->
                try {
                    app.contacts.contacts(settings, hidden) to (settings to locale)
                } catch (_: SecurityException) {
                    // Access taken away while the app ran
                    access.value = false
                    null
                }
            }
            // Contacts being deleted leave the list at once, before the storage hears of it (SPEC §6.8)
            .combine(app.deletes.hidden) { read, deleting ->
                val (loaded, more) = read ?: return@combine ListState.NoAccess
                val (settings, locale) = more
                val contacts = if (deleting.isEmpty()) loaded.contacts else loaded.contacts.filter { it.id !in deleting }
                when {
                    loaded.total == 0 -> ListState.NoContacts
                    contacts.isEmpty() && loaded.contacts.isEmpty() -> ListState.AllHidden(loaded.total)
                    contacts.isEmpty() -> ListState.NoContacts
                    else -> ListState.Ready(ContactList.build(contacts, locale), ContactList.favorites(contacts, locale), settings.companyLine)
                }
            }
    }
        // Sorting thousands of names by collation keys is no work for the main thread
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListState.Loading)

    /** Back on screen: access may have been given or taken in the system's settings meanwhile. */
    fun refreshAccess() {
        access.value = app.contacts.hasAccess()
    }

    fun setLocale(value: Locale) {
        locale.value = value
    }

    /** The contacts selected for an action on all of them (SPEC §4.7). */
    val selection = Selection()

    /** The search field's text (SPEC §5.1). */
    val searchField = TextFieldState()

    private val _searching = MutableStateFlow(false)

    /** The search is open in the top bar; the list shows its results. */
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** "Search all": hidden accounts searched too, until the query changes (SPEC §5.4). */
    private val searchAll = MutableStateFlow(false)

    val query: StateFlow<String> = snapshotFlow { searchField.text.toString() }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    init {
        viewModelScope.launch { query.collect { searchAll.value = false } }
        // A relation on a contact page that names no one contact: its name, searched (SPEC §6.4)
        viewModelScope.launch {
            app.pendingSearch.collect { text ->
                if (text != null) {
                    app.pendingSearch.value = null
                    openSearch(text)
                }
            }
        }
    }

    fun openSearch(text: String? = null) {
        if (text != null) searchField.setTextAndPlaceCursorAtEnd(text)
        _searching.value = true
    }

    /** Back or the field's arrow: the search clears and closes, the list is where it was. */
    fun closeSearch() {
        _searching.value = false
        searchField.clearText()
    }

    fun clearQuery() = searchField.clearText()

    fun searchAll() {
        searchAll.value = true
    }

    /**
     * Every contact with what search looks in, in the list's order: read while the search is open,
     * again after changes in the storage, and dropped when it closes.
     */
    private val index = _searching.flatMapLatest { open ->
        if (!open || !access.value) return@flatMapLatest flowOf(null)
        combine(app.contacts.changes.debounce(300).onStart { emit(Unit) }, app.preferences.listSettings, app.preferences.hiddenAccounts, locale) { _, settings, hidden, locale -> Triple(settings, hidden, locale) }
            .mapLatest { (settings, hidden, locale) ->
                val contacts = try {
                    app.contacts.searchIndex(settings, hidden)
                } catch (_: SecurityException) {
                    return@mapLatest null
                }
                // The list's order: what the list builds, contacts of hidden accounts after it in the same order
                val order = ContactList.build(contacts.map { it.row }, locale).mapNotNull { (it as? ListEntry.Contact)?.contact?.id }.withIndex().associate { it.value to it.index }
                contacts.sortedBy { order[it.row.id] ?: Int.MAX_VALUE }
            }
    }

    /** What the search shows (SPEC §5.3, §5.4); null while it is closed or reading. */
    val search: StateFlow<SearchState?> = combine(index, query.debounce(150), searchAll, app.deletes.hidden) { all, text, everywhere, deleting -> Triple(all?.filter { it.row.id !in deleting }, text, everywhere) }
        .mapLatest { (contacts, text, all) ->
            contacts ?: return@mapLatest null
            val hiddenExist = contacts.any { !it.shown }
            if (text.isBlank()) {
                // An empty field: the list as it was, without favorites or headers
                SearchState(text, contacts.filter { it.shown }.map { SearchResult(it.row, true, null, it.row.name, null) }, all, hiddenExist, blank = true)
            } else {
                SearchState(text, Search.find(contacts, text, app.contacts.countryCode(), all), all, hiddenExist, blank = false)
            }
        }
        // Every keystroke folds every field of every contact: off the main thread, or typing stutters
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * The selection (SPEC §4.7): the contacts chosen by long press and taps, by id. Empty when the
 * list isn't selecting.
 */
class Selection {
    var ids by mutableStateOf<Set<Long>>(emptySet())
        private set

    val active: Boolean get() = ids.isNotEmpty()

    fun toggle(id: Long) {
        ids = if (id in ids) ids - id else ids + id
    }

    fun set(all: Set<Long>) {
        ids = all
    }

    fun clear() {
        ids = emptySet()
    }
}

/**
 * The search's results for [query] (SPEC §5.3); with [blank] every shown contact, in the list's order.
 *
 * @param all Hidden accounts are searched too ("Search all" was tapped for this query)
 * @param hiddenExist Some contacts are only in hidden accounts, so "Search all" may find more
 */
data class SearchState(val query: String, val results: List<SearchResult>, val all: Boolean, val hiddenExist: Boolean, val blank: Boolean)
