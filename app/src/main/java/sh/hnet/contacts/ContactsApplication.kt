package sh.hnet.contacts

import android.app.Application
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.hnet.contacts.backup.BackupManager
import sh.hnet.contacts.data.ContactSource
import sh.hnet.contacts.data.ContactWriter
import sh.hnet.contacts.data.DeleteQueue
import sh.hnet.contacts.data.ImportJob
import sh.hnet.contacts.data.Sharing
import sh.hnet.contacts.data.UploadWatch
import sh.hnet.contacts.model.ContactDetails
import sh.hnet.contacts.model.DupGroup
import sh.hnet.contacts.model.Duplicates

class ContactsApplication : Application() {
    /** Work that outlives a screen; lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob())

    val preferences: Preferences by lazy { Preferences(this) }

    /** The system's contacts storage. */
    val contacts: ContactSource by lazy { ContactSource(this) }

    /** The editor's reads and writes. */
    val writer: ContactWriter by lazy { ContactWriter(this, contacts) }

    /** Whether the apps of accounts that sync sent what the app wrote (the owner's D1–D5, 18 Nov 2026). */
    val uploads: UploadWatch by lazy { UploadWatch(appScope, writer, contacts) }

    /** Deletes waiting for their Undo snackbar to go. */
    val deletes: DeleteQueue by lazy { DeleteQueue(appScope, writer, uploads) }

    /** The vCard import, which outlives its page (SPEC §10.4). */
    val imports: ImportJob by lazy { ImportJob(appScope, writer) }

    /**
     * The duplicate suggestions (SPEC §9.2), worked out again 2 s after the storage changes, while
     * something shows them (the list's card, Settings, the Duplicates page).
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val duplicates: StateFlow<List<DupGroup>> by lazy {
        combine(contacts.changes.debounce(2_000).onStart { emit(Unit) }, preferences.hiddenAccounts) { _, hidden -> hidden }
            .mapLatest { hidden ->
                if (!contacts.hasAccess()) return@mapLatest emptyList()
                try {
                    Duplicates.find(contacts.dupContacts(hidden), contacts.separatePairs())
                } catch (_: Exception) {
                    emptyList()
                }
            }
            .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    /** How many suggestions there were when the list's card was put away with "Not now" (SPEC §4.4): a count only, kept on the phone, never in the backup. */
    var duplicatesDismissed: Int
        get() = getSharedPreferences("local", MODE_PRIVATE).getInt("duplicates_dismissed", 0)
        set(value) = getSharedPreferences("local", MODE_PRIVATE).edit { putInt("duplicates_dismissed", value) }

    /** A name the list is asked to search for, taken once (a relation on a contact page that names no one contact, SPEC §6.4). */
    val pendingSearch = MutableStateFlow<String?>(null)

    /** Backup and restore of the settings. */
    val backup: BackupManager by lazy { BackupManager(this, getSharedPreferences("stamps", MODE_PRIVATE)) }

    /** A contact read ahead, when a finger touched its row, before the tap opens its page; with when it started. */
    private var prefetched: Triple<String, Long, Deferred<ContactDetails?>>? = null

    /**
     * Starts reading a contact as soon as its row is touched (the owner, 17 Nov 2026): the tap
     * comes a moment later, and the page finds the contact read or nearly so. One at a time; a
     * touch elsewhere replaces it.
     */
    fun prefetch(lookupKey: String, id: Long) {
        val key = "$lookupKey/$id"
        val now = SystemClock.elapsedRealtime()
        // The same row touched again a moment later: the reading under way serves
        if (prefetched?.first == key && now - prefetched!!.second < FRESH_MS) return
        prefetched?.third?.cancel()
        prefetched = Triple(key, now, appScope.async(Dispatchers.IO) {
            try {
                contacts.details(lookupKey, id, preferences.listSettings.first().lastFirst)
            } catch (_: SecurityException) {
                null
            }
        })
    }

    /** A touch that turned into a scroll: the reading is of no use. */
    fun cancelPrefetch() {
        prefetched?.third?.cancel()
        prefetched = null
    }

    /** The contact read ahead for [lookupKey] / [id], taken once; null when none was or it failed. */
    suspend fun takePrefetched(lookupKey: String, id: Long): ContactDetails? {
        // Older than a few seconds (a touch long ago, the name order or a sync may have changed since): read anew
        val ahead = prefetched?.takeIf { it.first == "$lookupKey/$id" && SystemClock.elapsedRealtime() - it.second < FRESH_MS } ?: return null
        prefetched = null
        return try {
            ahead.third.await()
        } catch (_: CancellationException) {
            null
        }
    }

    private companion object {
        /** How long a read-ahead is good for: a tap follows its touch within a second. */
        const val FRESH_MS = 3_000L
    }

    override fun onCreate() {
        super.onCreate()
        // The account apps and sync adapters, read while the list loads rather than when the first contact opens
        appScope.launch(Dispatchers.IO) {
            contacts.warmUp()
            // Files handed to other apps last time (SPEC §11.4): they had their moment
            Sharing.clear(this@ContactsApplication)
        }
    }
}
