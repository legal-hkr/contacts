package sh.hnet.contacts.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.data.AccountInfo
import sh.hnet.contacts.data.EditSnapshot
import sh.hnet.contacts.data.PhotoChange
import sh.hnet.contacts.data.Saved
import sh.hnet.contacts.model.EditKind
import sh.hnet.contacts.model.EditorDiff
import sh.hnet.contacts.model.FormValue
import sh.hnet.contacts.model.UploadOutcome

/** What the editor opens with besides a contact: values handed over by another app's "Create contact" (SPEC §3.4). */
data class Prefill(val name: String? = null, val phone: String? = null, val email: String? = null)

/** The editor's place in its life (SPEC §7): reading, gone before it opened, or the form. */
enum class EditorPhase { LOADING, GONE, READY }

/** Changed underneath while the form was open (SPEC §7.7). */
enum class Underneath { NONE, CHANGED, DELETED }

/**
 * The editor (SPEC §7): a new contact, or the contact [lookupKey] / [id]. The form is a list of
 * values the screen edits in place; Save works out the writes against what was read. While the
 * form is open, a change of the contact in the storage (a sync) is noticed by the entries' versions.
 */
@OptIn(FlowPreview::class)
class EditorViewModel(private val app: ContactsApplication, private val lookupKey: String?, private val id: Long, prefill: Prefill) : ViewModel() {
    var phase by mutableStateOf(EditorPhase.LOADING)
        private set

    /** The form's values, in the order they were read or added. */
    val values = mutableStateListOf<FormValue>()

    /** The accounts a new contact can go to, and the one chosen (SPEC §7.2). */
    var targets by mutableStateOf<List<AccountInfo>>(emptyList())
        private set
    var account by mutableStateOf<AccountInfo?>(null)

    /** The contact as read; null for a new contact. */
    var snapshot by mutableStateOf<EditSnapshot?>(null)
        private set

    /** The photo shown: the stored one's address, a new crop, or none. */
    var photo by mutableStateOf<PhotoChange>(PhotoChange.None)
    val storedPhoto: String? get() = snapshot?.photo

    var underneath by mutableStateOf(Underneath.NONE)
        private set

    var saving by mutableStateOf(false)
        private set

    /** Saved, waiting for the account's app to send it (D1): the form is locked meanwhile. */
    var uploading by mutableStateOf(false)
        private set

    /** The account's app took the save back (D1): the account's name for the card; the form still holds the user's values. */
    var rejected by mutableStateOf<String?>(null)
        private set

    /** The save the app took back, for "Discard changes". */
    private var rejectedSave: Saved? = null

    /** The values as read, to tell whether anything changed (the discard dialog, SPEC §7.7). */
    private var original: List<FormValue> = emptyList()

    init {
        viewModelScope.launch {
            val all = app.contacts.targets()
            targets = all
            account = app.contacts.resolveDefault(all, app.preferences.defaultAccount.first())
            if (lookupKey == null) {
                fill(emptyList(), prefill)
                phase = EditorPhase.READY
            } else {
                load()
                watch()
            }
        }
    }

    private suspend fun load() {
        val read = app.writer.load(lookupKey!!, id)
        if (read == null) {
            phase = EditorPhase.GONE
            return
        }
        snapshot = read
        fill(read.values, Prefill())
        underneath = Underneath.NONE
        photo = PhotoChange.None
        phase = EditorPhase.READY
    }

    /** The form shows every kind that has a value; the always-visible ones (photo, name, a phone, an e-mail) are there empty when they have none (SPEC §7.4.1). */
    private fun fill(read: List<FormValue>, prefill: Prefill) {
        values.clear()
        values += read
        if (values.none { it.kind == EditKind.NAME }) values += FormValue(app.writer.newKey(), EditKind.NAME, prefill.name?.let { mapOf("data2" to it) }.orEmpty())
        if (values.none { it.kind == EditKind.PHONE }) values += FormValue(app.writer.newKey(), EditKind.PHONE, prefill.phone?.let { mapOf("data1" to it) }.orEmpty(), type = DEFAULT_TYPES.getValue(EditKind.PHONE))
        if (values.none { it.kind == EditKind.EMAIL }) values += FormValue(app.writer.newKey(), EditKind.EMAIL, prefill.email?.let { mapOf("data1" to it) }.orEmpty(), type = DEFAULT_TYPES.getValue(EditKind.EMAIL))
        original = values.toList()
    }

    /**
     * Notices a sync changing or deleting the contact while the form is open (SPEC §7.7). A newer
     * version of an entry alone isn't enough: the storage bumps versions for its own bookkeeping too
     * (the owner saw the card on reopening a contact he had just saved), so the contact is read again
     * and the card shows only when what it holds differs from what the form was made from.
     */
    private fun watch() {
        viewModelScope.launch {
            app.contacts.changes.debounce(300).collect {
                // The editor's own save and its sending change the contact too: that's the upload check's business, not the changed card's
                if (saving || rejected != null) return@collect
                val read = snapshot ?: return@collect
                val now = app.writer.versions(read.entries.map { it.rawContactId })
                if (now.isEmpty()) {
                    underneath = Underneath.DELETED
                    return@collect
                }
                if (read.entries.all { now[it.rawContactId] == it.version }) return@collect
                val fresh = app.writer.load(lookupKey!!, id)
                when {
                    fresh == null -> underneath = Underneath.DELETED
                    // A photo's address changes when the storage finishes processing a new one: only whether there is a photo counts
                    // An entry gone (a sync, a move's Undo window over) is a change even with the same values: Save would write into it
                    read.entries.any { it.rawContactId !in now } -> underneath = Underneath.CHANGED
                    EditorDiff.content(fresh.values) == EditorDiff.content(read.values) && (fresh.photo == null) == (read.photo == null) -> {
                        // Only the versions moved: the form stays valid, compared from now on against the new ones
                        snapshot = read.copy(entries = read.entries.map { entry -> entry.copy(version = now.getValue(entry.rawContactId)) })
                    }
                    else -> underneath = Underneath.CHANGED
                }
            }
        }
    }

    /** "Reload": the user's edits go, the contact as it is now comes in (SPEC §7.7). */
    fun reload() {
        viewModelScope.launch { load() }
    }

    fun update(value: FormValue) {
        val at = values.indexOfFirst { it.key == value.key }
        if (at >= 0) values[at] = value
    }

    /** A new empty value of [kind], placed after the last of its kind or in the canonical order (SPEC §7.4.2); returns its key, to focus it. */
    fun add(kind: EditKind): Long {
        val value = FormValue(app.writer.newKey(), kind, type = DEFAULT_TYPES[kind] ?: 0, protocol = if (kind == EditKind.IM) -1 else null, label = if (kind == EditKind.IM) "Matrix" else null)
        val lastOfKind = values.indexOfLast { it.kind == kind }
        val at = if (lastOfKind >= 0) lastOfKind + 1 else values.indexOfFirst { it.kind.ordinal > kind.ordinal }.let { if (it < 0) values.size else it }
        values.add(at, value)
        return value.key
    }

    fun remove(value: FormValue) {
        values.removeAll { it.key == value.key }
        // An always-visible kind keeps one empty field
        if ((value.kind == EditKind.PHONE || value.kind == EditKind.EMAIL) && values.none { it.kind == value.kind }) add(value.kind)
    }

    /** Only one default per kind: making one the default takes it from the others. */
    fun setDefault(value: FormValue, on: Boolean) {
        for ((i, v) in values.withIndex()) {
            if (v.kind != value.kind) continue
            values[i] = if (v.key == value.key) v.copy(isDefault = on) else if (on) v.copy(isDefault = false) else v
        }
    }

    val hasContent: Boolean get() = EditorDiff.hasContent(values, photo is PhotoChange.Set || (photo == PhotoChange.None && storedPhoto != null))

    /** Anything changed since the form opened: the discard dialog asks only then. */
    val changed: Boolean
        get() = photo != PhotoChange.None || values.size != original.size || values.zip(original).any { (a, b) -> a.key != b.key || !a.sameContent(b) }

    /** Saves and hands the contact to open to [onSaved]; [onFailed] when the storage refused. */
    fun save(onSaved: (Saved) -> Unit, onFailed: () -> Unit) {
        if (saving) return
        saving = true
        // A watch of an earlier save of this contact ends: this save changes the rows it compares
        snapshot?.let { read -> app.uploads.forget(read.entries.map { it.rawContactId }) }
        viewModelScope.launch {
            // Created anew only when the contact is gone now, not because the card once said so
            val gone = underneath == Underneath.DELETED && snapshot?.let { read -> app.writer.versions(read.entries.map { it.rawContactId }).isEmpty() } == true
            val saved = app.writer.save(snapshot, values.toList(), account?.account, photo, recreate = gone)
            if (saved == null) {
                saving = false
                onFailed()
                return@launch
            }
            if (saved.upload == null) {
                saving = false
                onSaved(saved)
                return@launch
            }
            // An account that syncs: the editor stays until its app sent the change, at most 10 s (D1, D2)
            uploading = true
            val outcome = app.uploads.await(saved)
            uploading = false
            saving = false
            when (outcome) {
                UploadOutcome.CONFIRMED -> onSaved(saved)
                UploadOutcome.PENDING -> {
                    app.uploads.follow(saved, formName())
                    onSaved(saved.copy(unconfirmed = true))
                }
                UploadOutcome.REJECTED -> {
                    rejectedSave = saved
                    // The account that took the change back: of the entries that sync, not the target, which may be a local entry of a joined contact (the code review of 18 Nov 2026)
                    rejected = (snapshot?.entries?.firstOrNull { it.rawContactId in saved.upload.entries }?.account ?: account?.account)?.name.orEmpty()
                }
            }
        }
    }

    /** "Save on this phone" after a rejection (D1): the form as a new contact without an account, joined with the one the account's app kept, if any. */
    fun keepOnPhone(onSaved: (Saved) -> Unit, onFailed: () -> Unit) {
        if (saving) return
        saving = true
        viewModelScope.launch {
            val local = app.writer.save(null, values.toList(), app.contacts.localTarget(), photo)
            val kept = rejectedSave
            if (local != null && kept != null && snapshot != null) {
                try {
                    app.writer.join(listOf(listOf(kept.contactId, local.contactId)))
                } catch (_: Exception) {
                    // Saved on the phone anyway, as a contact of its own
                }
            }
            saving = false
            if (local == null) onFailed() else {
                rejected = null
                onSaved(local.copy(account = null))
            }
        }
    }

    /** The name in the form, given, middle and family name, for a snackbar about a contact that may be gone by then. */
    private fun formName(): String = values.firstOrNull { it.kind == EditKind.NAME }?.parts?.let { parts ->
        listOf("data2", "data5", "data3").mapNotNull { parts[it]?.trim()?.takeIf { part -> part.isNotEmpty() } }.joinToString(" ")
    }.orEmpty()

    /** "Discard changes" after a rejection (D1): the save the account's app took back, for the shell to close the editor as after any save; null for a new contact, which is gone. */
    fun discardRejected(): Saved? = rejectedSave.takeIf { snapshot != null }

    companion object {
        /** The type a new value of a kind starts with (SPEC §7.4.1): Mobile, Personal, Home, Website, Birthday, Other (SIP), Relative (a relation's type is chosen, this is the most general). */
        val DEFAULT_TYPES = mapOf(EditKind.PHONE to 2, EditKind.EMAIL to 1, EditKind.ADDRESS to 1, EditKind.WEBSITE to 1, EditKind.DATE to 3, EditKind.SIP to 3, EditKind.RELATION to 12)
    }
}
