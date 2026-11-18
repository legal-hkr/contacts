package sh.hnet.contacts.ui.transfer

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.data.AccountInfo
import sh.hnet.contacts.data.Existing
import sh.hnet.contacts.data.ImageCodec
import sh.hnet.contacts.model.NotVCardException
import sh.hnet.contacts.model.VCardFile
import sh.hnet.contacts.model.VCardReader

/**
 * The import page's file (SPEC §10.2): read and parsed off the main thread, the contacts that
 * already exist found (§10.3), the target account preset to the default one, and what the user
 * picks. Several files shared at once are one import.
 */
class ImportViewModel(private val app: ContactsApplication, private val uris: List<String>) : ViewModel() {
    sealed interface State {
        data object Loading : State

        data object Unreadable : State

        data object Empty : State

        /** [existing]: per contact, the one it already is; [preview]: the one contact's photo as a file address. */
        data class Ready(val file: VCardFile, val existing: List<Existing?>, val preview: String?) : State
    }

    var state by mutableStateOf<State>(State.Loading)
        private set

    /** The files' names, for the subtitle. */
    var fileName by mutableStateOf("")
        private set

    var targets by mutableStateOf<List<AccountInfo>>(emptyList())
        private set
    var account by mutableStateOf<AccountInfo?>(null)

    /** "Skip existing contacts" (SPEC §10.2): on at first. */
    var skipExisting by mutableStateOf(true)
        private set

    /** The chosen contacts, by their place in the file. */
    var selected by mutableStateOf<Set<Int>>(emptySet())
        private set

    /** One contact that already exists: imported only when this is on (owner's decision of 18 Nov 2026). */
    var importAnyway by mutableStateOf(false)

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val resolver = app.contentResolver
        val addresses = uris.map { it.toUri() }
        fileName = addresses.joinToString(", ") { nameOf(it) }
        val file = try {
            val bytes = withContext(Dispatchers.IO) {
                ByteArrayOutputStream().use { out ->
                    for (uri in addresses) {
                        (resolver.openInputStream(uri) ?: throw IOException("No input")).use { it.copyTo(out) }
                        out.write("\r\n".toByteArray())
                    }
                    out.toByteArray()
                }
            }
            withContext(Dispatchers.Default) { VCardReader.read(bytes) }
        } catch (_: NotVCardException) {
            null
        } catch (_: Exception) {
            // IOException, SecurityException (the grant is gone), OutOfMemoryError isn't caught: a file that size isn't a contacts file
            null
        }
        if (file == null) {
            state = State.Unreadable
            return
        }
        if (file.contacts.isEmpty()) {
            state = State.Empty
            return
        }
        val found = runCatching { app.contacts.targets() }.getOrDefault(emptyList())
        targets = found
        if (found.isNotEmpty()) account = app.contacts.resolveDefault(found, app.preferences.defaultAccount.first())
        val hidden = app.preferences.hiddenAccounts.first()
        val existing = if (app.contacts.hasAccess()) runCatching { app.contacts.existing(file.contacts, hidden) }.getOrNull() else null
        val known = existing ?: file.contacts.map { null }
        selected = known.indices.filter { known[it] == null }.toSet()
        val preview = if (file.contacts.size == 1) file.contacts[0].photo?.let { previewPhoto(it) } else null
        state = State.Ready(file, known, preview)
    }

    private fun nameOf(uri: Uri): String = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    /** The one contact's photo in the cache, for the preview's avatar; the share folder is emptied on the next start. */
    private suspend fun previewPhoto(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val jpeg = ImageCodec.jpeg(bytes, PREVIEW) ?: return@withContext null
        val file = File(File(app.cacheDir, "share").apply { mkdirs() }, "import-preview-${System.nanoTime()}.jpg")
        file.writeBytes(jpeg)
        Uri.fromFile(file).toString()
    }

    fun setSkip(skip: Boolean) {
        val ready = state as? State.Ready ?: return
        skipExisting = skip
        // Off checks them all; on leaves the existing ones out again (SPEC §10.2)
        selected = if (skip) selected - ready.existing.indices.filter { ready.existing[it] != null }.toSet() else ready.file.contacts.indices.toSet()
    }

    fun toggle(index: Int) {
        selected = if (index in selected) selected - index else selected + index
    }

    fun selectAll(all: Boolean) {
        val ready = state as? State.Ready ?: return
        selected = if (all) ready.file.contacts.indices.toSet() else emptySet()
    }

    /** Starts the import in the app's scope; [thisPhone] names local contacts in the message. */
    fun import(thisPhone: String) {
        val ready = state as? State.Ready ?: return
        val target = account ?: return
        val single = ready.file.contacts.size == 1
        val chosen = if (single) listOf(0).filter { ready.existing[0] == null || importAnyway } else selected.sorted()
        if (chosen.isEmpty()) return
        val skipped = ready.existing.indices.count { ready.existing[it] != null && it !in chosen }
        app.imports.start(chosen.map { ready.file.contacts[it] }, target.account, if (target.local) thisPhone else target.account.name, skipped, single)
    }

    private companion object {
        const val PREVIEW = 512
    }
}
