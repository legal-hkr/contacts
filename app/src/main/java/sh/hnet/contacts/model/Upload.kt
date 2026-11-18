package sh.hnet.contacts.model

/** What became of a write to an account that syncs (the owner's decisions D1–D5, 18 Nov 2026), as the storage shows it once the account's app had its turn. */
enum class UploadOutcome { CONFIRMED, REJECTED, PENDING }

/** An entry as the storage holds it: [dirty] while its app hasn't sent a change yet, [deleted] while a delete waits to be sent. */
data class EntryState(val dirty: Boolean, val deleted: Boolean)

/**
 * Tells a write the account's app sent from one it took back. The storage doesn't say "rejected": an app that can't send a change (DAVx⁵ with a read-only address book) clears the dirty flag and writes the server's version back, which replaces the entry's data rows. A sent change keeps its rows. So: still dirty – pending; rows as saved – confirmed; rows replaced – confirmed only if they hold what was saved (the server echoed it), else rejected. Pure Kotlin, unit-tested.
 */
object UploadCheck {
    /** An edit or a new contact in [entries]; [states] holds the entries the storage still has. [sameContent] is asked only when the rows were replaced. */
    fun edit(states: Map<Long, EntryState>, entries: Collection<Long>, rowsSaved: Set<Long>, rowsNow: Set<Long>, sameContent: () -> Boolean): UploadOutcome {
        // A new contact the app couldn't send is removed by it; a deleted entry didn't keep the change either
        if (entries.any { states[it]?.deleted != false }) return UploadOutcome.REJECTED
        if (entries.any { states.getValue(it).dirty }) return UploadOutcome.PENDING
        if (rowsNow == rowsSaved) return UploadOutcome.CONFIRMED
        return if (sameContent()) UploadOutcome.CONFIRMED else UploadOutcome.REJECTED
    }

    /**
     * What a save is checked by: the values stored in [entries] – the entries of accounts that sync it wrote –, not the whole contact, so another entry of a joined contact changing meanwhile (a local one, another account's sync) isn't taken for the server's answer (the code review of 18 Nov 2026).
     */
    fun content(values: List<FormValue>, entries: Collection<Long>): List<String> =
        EditorDiff.content(values.filter { value -> !value.blank && value.origins.any { it.rawContactId in entries } })

    /** A delete of [entries]: sent when the storage dropped them, waiting while they are marked deleted, rejected when one came back. */
    fun deletion(states: Map<Long, EntryState>, entries: Collection<Long>): UploadOutcome {
        val left = entries.filter { it in states }
        return when {
            left.isEmpty() -> UploadOutcome.CONFIRMED
            left.any { states.getValue(it).deleted } -> UploadOutcome.PENDING
            else -> UploadOutcome.REJECTED
        }
    }
}
