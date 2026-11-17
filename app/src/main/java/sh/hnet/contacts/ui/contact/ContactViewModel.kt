package sh.hnet.contacts.ui.contact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.model.ContactDetails

sealed interface ContactState {
    data object Loading : ContactState

    /** Deleted or gone in a sync while the page was open, or opened from an old link (SPEC §16.3). */
    data object Gone : ContactState

    data class Ready(val contact: ContactDetails) : ContactState
}

/**
 * One contact's page (SPEC §6): read when it opens and again after every change in the storage – a
 * sync, a join –, following the contact by its lookup key, which survives both.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ContactViewModel(private val app: ContactsApplication, lookupKey: String, id: Long) : ViewModel() {
    private var key = lookupKey
    private var contactId = id

    /** The star as the user just set it, shown before the storage confirms. */
    private val starOverride = MutableStateFlow<Boolean?>(null)

    val state: StateFlow<ContactState> = combine(app.contacts.changes.debounce(300).onStart { emit(Unit) }, app.preferences.listSettings) { _, settings -> settings.lastFirst }
        .mapLatest { lastFirst ->
            val details = try {
                app.contacts.details(key, contactId, lastFirst)
            } catch (_: SecurityException) {
                null
            } ?: return@mapLatest ContactState.Gone
            key = details.lookupKey
            contactId = details.id
            ContactState.Ready(details)
        }
        .combine(starOverride) { state, star -> if (state is ContactState.Ready && star != null) ContactState.Ready(state.contact.copy(starred = star)) else state }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactState.Loading)

    /** Writes the star; [onFailed] runs when the storage refused it, and the star goes back. */
    fun setStarred(starred: Boolean, onFailed: () -> Unit) {
        starOverride.value = starred
        viewModelScope.launch {
            val written = app.contacts.setStarred(contactId, starred)
            starOverride.value = null
            if (!written) onFailed()
        }
    }

    /** "Remember as default" (SPEC §6.3.1). */
    fun setDefault(dataId: Long) {
        viewModelScope.launch { app.contacts.setDefault(dataId) }
    }

    /** The contact a relation names, when exactly one contact has that name (SPEC §6.4). */
    suspend fun onlyContactNamed(name: String): Pair<Long, String>? = app.contacts.onlyContactNamed(name)
}
