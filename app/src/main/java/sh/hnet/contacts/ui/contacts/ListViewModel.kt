package sh.hnet.contacts.ui.contacts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.model.ContactList
import sh.hnet.contacts.model.ContactRow
import sh.hnet.contacts.model.ListEntry
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
                val loaded = try {
                    app.contacts.contacts(settings, hidden)
                } catch (_: SecurityException) {
                    // Access taken away while the app ran
                    access.value = false
                    return@mapLatest ListState.NoAccess
                }
                when {
                    loaded.total == 0 -> ListState.NoContacts
                    loaded.contacts.isEmpty() -> ListState.AllHidden(loaded.total)
                    else -> ListState.Ready(ContactList.build(loaded.contacts, locale), ContactList.favorites(loaded.contacts, locale), settings.companyLine)
                }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListState.Loading)

    /** Back on screen: access may have been given or taken in the system's settings meanwhile. */
    fun refreshAccess() {
        access.value = app.contacts.hasAccess()
    }

    fun setLocale(value: Locale) {
        locale.value = value
    }
}
