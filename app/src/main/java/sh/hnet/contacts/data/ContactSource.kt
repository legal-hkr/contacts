package sh.hnet.contacts.data

import android.Manifest
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Organization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import sh.hnet.contacts.model.AccountKey
import sh.hnet.contacts.model.ContactRow
import sh.hnet.contacts.model.ListSettings

/**
 * An account as Settings, the first run and later the Accounts page show it (SPEC §8.1).
 *
 * @param app The label of the app the account belongs to ("DAVx⁵"); null for local contacts or when the system doesn't say
 * @param writable The app can save contacts there: local contacts, or an account whose sync adapter uploads changes
 * @param count Contacts with an entry in the account
 */
data class AccountInfo(
    val account: AccountKey,
    val local: Boolean,
    val app: String?,
    val appPackage: String?,
    val iconId: Int,
    val writable: Boolean,
    val count: Int,
)

/** The list as read: the contacts of the shown accounts, and how many contacts the system has in all. */
data class LoadedContacts(val contacts: List<ContactRow>, val total: Int)

/**
 * The system's contacts storage (`ContactsContract`), read for the UI. The app never keeps a copy:
 * everything is read when shown and again when the storage says it changed. Nothing is logged.
 */
class ContactSource(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    /** Both halves of the one contacts permission the app asks for. */
    fun hasAccess(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Emits once at once, then whenever the storage changes (a sync, another app). Collect only with access. */
    val changes: Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(ContactsContract.AUTHORITY_URI, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    /** The platform's local account (Android 14 and later), if the phone has one; entries without any account are local too. */
    private val localAccount: AccountKey? by lazy {
        val type = ContactsContract.RawContacts.getLocalAccountType(context)
        val name = ContactsContract.RawContacts.getLocalAccountName(context)
        if (type == null && name == null) null else AccountKey(type.orEmpty(), name.orEmpty())
    }

    fun isLocal(account: AccountKey): Boolean = (account.type.isEmpty() && account.name.isEmpty()) || account == localAccount

    /**
     * Every account that has contacts or could take them: the accounts of the entries in the
     * storage, plus the accounts of apps that sync contacts and have none yet. Local contacts are
     * listed when they exist. Writable ones first, then by name.
     */
    suspend fun accounts(): List<AccountInfo> = withContext(Dispatchers.IO) {
        val counts = HashMap<AccountKey, MutableSet<Long>>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID, ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME),
            "${ContactsContract.RawContacts.DELETED} = 0", null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val account = AccountKey(cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty())
                counts.getOrPut(account) { HashSet() }.add(cursor.getLong(0))
            }
        }
        val uploads = contactsAdapters()
        val manager = AccountManager.get(context)
        val authenticators = manager.authenticatorTypes.associateBy { it.type }
        // Accounts of apps that sync contacts, visible to an app holding the contacts permission, even without contacts yet
        val synced = manager.accounts.filter { it.type in uploads }.map { AccountKey(it.type, it.name) }
        (counts.keys + synced).distinct().map { account ->
            val local = isLocal(account)
            val authenticator = authenticators[account.type]
            AccountInfo(
                account = account,
                local = local,
                app = if (local) null else authenticator?.let { label(it.packageName, it.labelId) },
                appPackage = authenticator?.packageName,
                iconId = authenticator?.iconId ?: 0,
                writable = local || uploads[account.type] == true,
                count = counts[account]?.size ?: 0,
            )
        }.sortedWith(compareBy<AccountInfo> { it.local }.thenBy { !it.writable }.thenBy { it.account.name.lowercase() })
    }

    /** Account types with a contacts sync adapter, and whether it sends changes to its server (read-only accounts don't, SPEC §6.5). */
    private fun contactsAdapters(): Map<String, Boolean> =
        ContentResolver.getSyncAdapterTypes().filter { it.authority == ContactsContract.AUTHORITY }.associate { it.accountType to it.supportsUploading() }

    private fun label(packageName: String, labelId: Int): String? = try {
        if (labelId != 0) context.packageManager.getResourcesForApplication(packageName).getString(labelId)
        else context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        null
    }

    /**
     * The contacts that have an entry in at least one account not in [hidden] (SPEC §8.1), with the
     * names and second line [settings] ask for. Unsorted: the list sorts in the app's language.
     */
    suspend fun contacts(settings: ListSettings, hidden: Set<String>): LoadedContacts = withContext(Dispatchers.IO) {
        val shown = HashSet<Long>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID, ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME),
            "${ContactsContract.RawContacts.DELETED} = 0", null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (AccountKey(cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty()).key !in hidden) shown.add(cursor.getLong(0))
            }
        }
        val companies = if (settings.companyLine) companies() else emptyMap()
        val rows = ArrayList<ContactRow>()
        var total = 0
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.DISPLAY_NAME_ALTERNATIVE,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                ContactsContract.Contacts.STARRED,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                total++
                val id = cursor.getLong(0)
                if (id !in shown) continue
                val primary = cursor.getString(2).orEmpty()
                val alternative = cursor.getString(3)?.takeIf { it.isNotBlank() } ?: primary
                rows += ContactRow(
                    id = id,
                    lookupKey = cursor.getString(1).orEmpty(),
                    name = if (settings.lastFirst) alternative else primary,
                    sortName = if (settings.sortByLast) alternative else primary,
                    photo = cursor.getString(4),
                    starred = cursor.getInt(5) != 0,
                    company = companies[id],
                )
            }
        }
        LoadedContacts(rows, total)
    }

    /** "company, title", or whichever exists, per contact: the first organization the storage gives. */
    private fun companies(): Map<Long, String> {
        val result = HashMap<Long, String>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, Organization.COMPANY, Organization.TITLE),
            "${ContactsContract.Data.MIMETYPE} = ?", arrayOf(Organization.CONTENT_ITEM_TYPE), null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                if (id in result) continue
                val text = listOfNotNull(cursor.getString(1), cursor.getString(2)).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
                if (text.isNotEmpty()) result[id] = text
            }
        }
        return result
    }
}
