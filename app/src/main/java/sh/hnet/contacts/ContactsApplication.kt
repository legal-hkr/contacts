package sh.hnet.contacts

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import sh.hnet.contacts.backup.BackupManager
import sh.hnet.contacts.data.ContactSource

class ContactsApplication : Application() {
    /** Work that outlives a screen; lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob())

    val preferences: Preferences by lazy { Preferences(this) }

    /** The system's contacts storage. */
    val contacts: ContactSource by lazy { ContactSource(this) }

    /** Backup and restore of the settings. */
    val backup: BackupManager by lazy { BackupManager(this, getSharedPreferences("stamps", MODE_PRIVATE)) }
}
