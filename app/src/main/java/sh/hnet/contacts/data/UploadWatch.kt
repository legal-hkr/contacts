package sh.hnet.contacts.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.hnet.contacts.model.UploadCheck
import sh.hnet.contacts.model.UploadOutcome

/**
 * Whether the app of an account that syncs sent what the app wrote (the owner's decisions D1–D5, 18 Nov 2026). DAVx⁵ doesn't mark a read-only address book in the storage, so a change there is only seen to fail when DAVx⁵ writes the server's version back; this watches for that. Nothing asks for a sync (D5): the account's app syncs after a change by itself, or the user turned that off.
 */
class UploadWatch(private val scope: CoroutineScope, private val writer: ContactWriter, private val source: ContactSource) {
    /** A change the account's app took back after the editor stopped waiting, or a delete it undid (D3, D4). */
    sealed interface Rejection {
        /** [exists]: the contact is still there to be opened (a new contact the account's app removed isn't). */
        data class Change(val name: String, val lookupKey: String, val contactId: Long, val exists: Boolean) : Rejection
        data class Deletion(val contacts: Int) : Rejection
    }

    /** Kept until the shell takes them (the code review of 18 Nov 2026: a flow without listeners dropped them while the app was away, the usual case with DAVx⁵'s 52 s); the oldest go beyond eight. */
    private val _rejections = Channel<Rejection>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The shell shows each as a snackbar, wherever the user is, once the app is on screen. */
    val rejections: Flow<Rejection> = _rejections.receiveAsFlow()

    /** The watches under way and the entries each is about. */
    private val watching = ConcurrentHashMap<Job, Collection<Long>>()

    /**
     * The app writes these entries again (an edit, a delete, a move, a merge): a watch of an earlier write to them ends, as their state no longer answers it – the user's own second change was taken for the server's (the code review of 18 Nov 2026).
     */
    fun forget(entries: Collection<Long>) {
        if (entries.isEmpty()) return
        val ids = entries.toSet()
        watching.forEach { (job, watched) -> if (watched.any { it in ids }) job.cancel() }
    }

    private fun watch(entries: Collection<Long>, block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        watching[job] = entries
        job.invokeOnCompletion { watching.remove(job) }
        job.start()
    }

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

    /** After the editor stopped waiting (D3): keeps watching while the app runs, and reports a change taken back, under [name] when the contact is gone. Gone with the process. */
    fun follow(saved: Saved, name: String) {
        val upload = saved.upload ?: return
        watch(upload.entries) {
            val outcome = withTimeoutOrNull(FOLLOW) {
                var outcome = check(saved, upload)
                while (outcome == UploadOutcome.PENDING) {
                    delay(FOLLOW_POLL)
                    outcome = check(saved, upload)
                }
                outcome
            }
            if (outcome == UploadOutcome.REJECTED) {
                val found = try {
                    source.details(saved.lookupKey, saved.contactId, false)?.name
                } catch (_: Exception) {
                    null
                }
                _rejections.trySend(Rejection.Change(found ?: name, saved.lookupKey, saved.contactId, exists = found != null))
            }
        }
    }

    /** A delete carried out (D4): its entries in accounts that sync are watched until their app sent it or put them back. */
    fun followDeletion(rawContactIds: List<Long>, contacts: Int) {
        if (rawContactIds.isEmpty()) return
        watch(rawContactIds) {
            val entries = try {
                writer.syncedEntries(rawContactIds)
            } catch (_: Exception) {
                emptyList()
            }
            if (entries.isEmpty()) return@watch
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
            if (outcome == UploadOutcome.REJECTED) {
                // The contacts that came back, not every one the delete took (the code review of 18 Nov 2026)
                val back = try {
                    writer.entryStates(entries).filterValues { !it.deleted }.keys.mapNotNull { writer.contactOfEntry(it)?.contactId }.distinct().size
                } catch (_: Exception) {
                    0
                }
                _rejections.trySend(Rejection.Deletion(back.takeIf { it > 0 } ?: contacts))
            }
        }
    }

    private suspend fun check(saved: Saved, upload: Upload): UploadOutcome = withContext(Dispatchers.IO) {
        try {
            val states = writer.entryStates(upload.entries)
            val rows = writer.dataRows(upload.entries.filter { it in states })
            // The contact is read again only when the rows were replaced and nothing waits any more: then its content decides
            val settled = upload.entries.all { states[it]?.let { state -> !state.dirty && !state.deleted } == true }
            val same = settled && rows != upload.rows && UploadCheck.content(writer.load(saved.lookupKey, saved.contactId)?.values.orEmpty(), upload.entries) == upload.content
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
