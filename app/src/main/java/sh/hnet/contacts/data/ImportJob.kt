package sh.hnet.contacts.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import sh.hnet.contacts.model.AccountKey
import sh.hnet.contacts.model.CardContact

/**
 * A vCard import running in the app's scope (SPEC §10.4): leaving the page doesn't stop it, and
 * the shell reports the outcome wherever the user is by then.
 */
class ImportJob(private val scope: CoroutineScope, private val writer: ContactWriter) {
    sealed interface State {
        data object Idle : State

        data class Running(val done: Int, val total: Int) : State

        data class Finished(val outcome: ImportOutcome) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /**
     * Imports [cards] into [account] (shown as [accountLabel]); [skipped] contacts that already
     * existed weren't chosen. [single]: the file held one contact, whose page opens afterwards.
     */
    fun start(cards: List<CardContact>, account: AccountKey, accountLabel: String, skipped: Int, single: Boolean) {
        if (_state.value is State.Running) return
        _state.value = State.Running(0, cards.size)
        scope.launch {
            val imported = writer.importCards(cards, account) { done -> _state.value = State.Running(done, cards.size) }
            val opened = if (single && imported.error == null) imported.rawContactIds.firstOrNull()?.let { writer.contactOfEntry(it) } else null
            _state.value = State.Finished(ImportOutcome(imported.rawContactIds, skipped, accountLabel, opened, imported.error))
        }
    }

    /** The outcome was reported. */
    fun clear() {
        if (_state.value is State.Finished) _state.value = State.Idle
    }
}

/** What an import did: the entries it wrote ([rawContactIds], for Undo), the contacts it left out as existing, and the contact to open for a one-contact file. */
data class ImportOutcome(val rawContactIds: List<Long>, val skipped: Int, val accountLabel: String, val opened: Saved?, val error: Exception?)
