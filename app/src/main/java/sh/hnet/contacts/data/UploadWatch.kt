package sh.hnet.contacts.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.hnet.contacts.model.EditorDiff
import sh.hnet.contacts.model.UploadCheck
import sh.hnet.contacts.model.UploadOutcome

/**
 * Whether the app of an account that syncs sent what the app wrote (the owner's decisions D1–D5, 18 Nov 2026). DAVx⁵ doesn't mark a read-only address book in the storage, so a change there is only seen to fail when DAVx⁵ writes the server's version back; this watches for that. Nothing asks for a sync (D5): the account's app syncs after a change by itself, or the user turned that off.
 */
class UploadWatch(private val scope: CoroutineScope, private val writer: ContactWriter, private val source: ContactSource) {
    /** A change the account's app took back after the editor stopped waiting, or a delete it undid (D3, D4). */
    sealed interface Rejection {
        data class Change(val name: String, val lookupKey: String, val contactId: Long) : Rejection
        data class Deletion(val contacts: Int) : Rejection
    }

    private val _rejections = MutableSharedFlow<Rejection>(extraBufferCapacity = 8)

    /** The shell shows each as a snackbar, wherever the user is. */
    val rejections: SharedFlow<Rejection> = _rejections

    /** The editor's wait (D1, D2): the outcome within [timeout] ms, else PENDING. */
    suspend fun await(saved: Saved, timeout: Long = EDITOR_WAIT): UploadOutcome {
        val upload = saved.upload ?: return UploadOutcome.CONFIRMED
        return withTimeoutOrNull(timeout) {
            var outcome = check(saved, upload)
            while (outcome == UploadOutcome.PENDING) {
                delay(POLL)
                outcome = check(saved, upload)
            }
            outcome
        } ?: UploadOutcome.PENDING
    }

    /** After the editor stopped waiting (D3): keeps watching while the app runs, and reports a change taken back. Gone with the process. */
    fun follow(saved: Saved) {
        val upload = saved.upload ?: return
        scope.launch {
            val outcome = withTimeoutOrNull(FOLLOW) {
                var outcome = check(saved, upload)
                while (outcome == UploadOutcome.PENDING) {
                    delay(FOLLOW_POLL)
                    outcome = check(saved, upload)
                }
                outcome
            }
            if (outcome == UploadOutcome.REJECTED) {
                val name = try {
                    source.details(saved.lookupKey, saved.contactId, false)?.name
                } catch (_: Exception) {
                    null
                }
                _rejections.emit(Rejection.Change(name.orEmpty(), saved.lookupKey, saved.contactId))
            }
        }
    }

    /** A delete carried out (D4): its entries in accounts that sync are watched until their app sent it or put them back. */
    fun followDeletion(rawContactIds: List<Long>, contacts: Int) {
        if (rawContactIds.isEmpty()) return
        scope.launch {
            val entries = try {
                writer.syncedEntries(rawContactIds)
            } catch (_: Exception) {
                emptyList()
            }
            if (entries.isEmpty()) return@launch
            val outcome = withTimeoutOrNull(FOLLOW) {
                var outcome = UploadOutcome.PENDING
                while (outcome == UploadOutcome.PENDING) {
                    delay(FOLLOW_POLL)
                    outcome = try {
                        UploadCheck.deletion(writer.entryStates(entries), entries)
                    } catch (_: Exception) {
                        UploadOutcome.PENDING
                    }
                }
                outcome
            }
            if (outcome == UploadOutcome.REJECTED) _rejections.emit(Rejection.Deletion(contacts))
        }
    }

    private suspend fun check(saved: Saved, upload: Upload): UploadOutcome = withContext(Dispatchers.IO) {
        try {
            val states = writer.entryStates(upload.entries)
            val rows = writer.dataRows(upload.entries.filter { it in states })
            // The contact is read again only when the rows were replaced and nothing waits any more: then its content decides
            val settled = upload.entries.all { states[it]?.let { state -> !state.dirty && !state.deleted } == true }
            val same = settled && rows != upload.rows && writer.load(saved.lookupKey, saved.contactId)?.values.orEmpty().filter { !it.blank }.let(EditorDiff::content) == upload.content
            UploadCheck.edit(states, upload.entries, upload.rows, rows) { same }
        } catch (_: Exception) {
            // The storage didn't answer (access taken away): nothing known yet
            UploadOutcome.PENDING
        }
    }

    private companion object {
        /** How long the editor waits for the account's app (D2). */
        const val EDITOR_WAIT = 10_000L
        const val POLL = 500L
        /** How long a change is watched after the editor closed (D3): a sync that late is no longer the user's save. */
        const val FOLLOW = 10 * 60_000L
        const val FOLLOW_POLL = 5_000L
    }
}
