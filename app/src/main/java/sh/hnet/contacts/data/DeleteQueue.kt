package sh.hnet.contacts.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Deleting with Undo (SPEC §6.8), as Calendar's, and a move's deleting of the old entries (§8.4): the contacts leave the list, search and favorites
 * at once, but the storage is told only when the Undo snackbar has gone – or the app has left the
 * screen, which counts as the user's decision too. Until then Undo only shows them again. One
 * delete waits at a time: the next one carries out the one before.
 */
class DeleteQueue(private val scope: CoroutineScope, private val writer: ContactWriter) {
    private val _waiting = MutableStateFlow<Deletion?>(null)

    /** The delete that can still be undone. */
    val waiting: StateFlow<Deletion?> = _waiting

    private val _hidden = MutableStateFlow<Set<Long>>(emptySet())

    /** The contacts the views leave out: the waiting delete's, and those being deleted until the views have read the storage again. */
    val hidden: StateFlow<Set<Long>> = _hidden

    fun delete(deletion: Deletion) {
        commit()
        _hidden.update { it + deletion.contacts }
        _waiting.value = deletion
    }

    fun undo() {
        val deletion = _waiting.value ?: return
        _waiting.value = null
        _hidden.update { it - deletion.contacts }
        // A move's Undo: the copies go, the originals were never touched
        if (deletion.undoRawContactIds.isNotEmpty()) scope.launch {
            try {
                writer.delete(deletion.undoRawContactIds)
            } catch (_: Exception) {
                // The copies stay joined with the originals: one contact with two entries, nothing lost
            }
        }
    }

    /** Carries the waiting delete out. Safe to call when nothing waits. */
    fun commit() {
        val deletion = _waiting.value ?: return
        _waiting.value = null
        // In the app's scope, not a screen's: the delete is done even when the page that asked for it is gone
        scope.launch {
            try {
                writer.delete(deletion.rawContactIds)
                // The views read the storage again a moment after it changed; until then the contacts stay out of sight
                delay(LINGER)
            } catch (_: Exception) {
                // Access gone or the storage refused: the contacts show again, as they are still there
            } finally {
                _hidden.update { it - deletion.contacts }
            }
        }
    }

    private companion object {
        const val LINGER = 1_000L
    }
}
