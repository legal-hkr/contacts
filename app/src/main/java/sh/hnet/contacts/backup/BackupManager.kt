package sh.hnet.contacts.backup

import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import sh.hnet.contacts.BuildConfig
import sh.hnet.contacts.ContactsApplication
import sh.hnet.contacts.model.AccountKey
import sh.hnet.contacts.model.ListSettings

/**
 * Backup and restore (SPEC §13.4), as Calendar's: writes a file the user picked and reads one back;
 * a restore replaces the settings and the accounts' visibility with the file's. Nothing is logged.
 */
class BackupManager(private val app: ContactsApplication, private val stamps: SharedPreferences) {
    private val resolver = app.contentResolver
    private val _lastBackup = MutableStateFlow(stamps.getLong(LAST_BACKUP, 0L).takeIf { it > 0 })

    /** When the last backup was saved on this phone; null before the first. Not a setting. */
    val lastBackup: StateFlow<Long?> = _lastBackup.asStateFlow()

    sealed interface Created {
        data class Saved(val fileName: String) : Created
        data class Failed(val fileName: String, val details: String) : Created
    }

    /** A picked file: checked ([check]), or not readable at all ([check] null, with [details]). */
    data class Picked(val fileName: String, val check: BackupCheck?, val details: String? = null)

    suspend fun create(uri: Uri): Created = withContext(Dispatchers.IO) {
        val fileName = displayName(uri) ?: uri.lastPathSegment.orEmpty()
        try {
            val text = Backup.encode(snapshot())
            (resolver.openOutputStream(uri, "wt") ?: throw IOException("No output")).use { it.write(text.toByteArray()) }
            val now = System.currentTimeMillis()
            stamps.edit { putLong(LAST_BACKUP, now) }
            _lastBackup.value = now
            Created.Saved(fileName)
        } catch (e: IOException) {
            Created.Failed(fileName, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } catch (e: SecurityException) {
            Created.Failed(fileName, e.javaClass.simpleName)
        }
    }

    /** Reads the picked file once and checks it; nothing changes yet. */
    suspend fun read(uri: Uri): Picked = withContext(Dispatchers.IO) {
        val fileName = displayName(uri) ?: uri.lastPathSegment.orEmpty()
        try {
            val bytes = (resolver.openInputStream(uri) ?: throw IOException("No input")).use { input ->
                val read = input.readNBytes(MAX_BYTES + 1)
                if (read.size > MAX_BYTES) return@withContext Picked(fileName, BackupCheck.NotOurs("Larger than ${MAX_BYTES / 1_048_576} MB"))
                read
            }
            Picked(fileName, Backup.check(bytes.toString(Charsets.UTF_8)))
        } catch (e: IOException) {
            Picked(fileName, null, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } catch (e: SecurityException) {
            Picked(fileName, null, e.javaClass.simpleName)
        }
    }

    /** Replaces the settings and the accounts' visibility with the backup's; what it leaves out returns to its default. */
    suspend fun restore(backup: BackupFile) {
        val s = backup.settings
        val hidden = backup.accounts.filter { !it.shown }.map { AccountKey(it.type, it.name).key }.toSet()
        app.preferences.replace(ListSettings(sortByLast = s.sortBy == SORT_LAST, lastFirst = s.nameOrder == ORDER_LAST_FIRST, companyLine = s.companyLine ?: false), hidden)
    }

    private suspend fun snapshot(): BackupFile {
        val list = app.preferences.listSettings.first()
        val hidden = app.preferences.hiddenAccounts.first()
        // Every account the phone knows, shown or not, so a restore on another phone settles each one once its account is back
        val known = if (app.contacts.hasAccess()) app.contacts.accounts().map { it.account.key }.toSet() else emptySet()
        return BackupFile(
            appVersion = BuildConfig.VERSION_NAME.removePrefix("v"),
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            createdAt = Instant.now().toString(),
            settings = BackupSettings(
                sortBy = if (list.sortByLast) SORT_LAST else SORT_FIRST,
                nameOrder = if (list.lastFirst) ORDER_LAST_FIRST else ORDER_FIRST_LAST,
                companyLine = list.companyLine,
            ),
            accounts = (known + hidden).mapNotNull { key -> AccountKey.of(key)?.let { BackupAccount(it.type, it.name, key !in hidden) } }.sortedWith(compareBy({ it.type }, { it.name })),
        )
    }

    /** The name the picker shows for [uri]; null when its provider doesn't say. Never logged. */
    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val LAST_BACKUP = "last_backup"
        const val SORT_FIRST = "first"
        const val SORT_LAST = "last"
        const val ORDER_FIRST_LAST = "first_last"
        const val ORDER_LAST_FIRST = "last_first"
        /** Far above any real backup: a few settings and a line per account. */
        const val MAX_BYTES = 1_048_576
    }
}
