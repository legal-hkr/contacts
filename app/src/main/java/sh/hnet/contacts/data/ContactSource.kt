package sh.hnet.contacts.data

import android.Manifest
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import sh.hnet.contacts.model.AccountKey
import sh.hnet.contacts.model.ContactDetails
import sh.hnet.contacts.model.ContactRow
import sh.hnet.contacts.model.Detail
import sh.hnet.contacts.model.DetailKind
import sh.hnet.contacts.model.Details
import sh.hnet.contacts.model.ListSettings
import sh.hnet.contacts.model.Source

/**
 * Messaging and SIP addresses: their kinds (`Im`, `SipAddress`) are deprecated as API in Android 17,
 * but contacts synced from elsewhere still carry them, and the page shows them (SPEC §6.4). Read
 * by their MIME types, without the deprecated classes.
 */
internal const val MIME_IM = "vnd.android.cursor.item/im"
internal const val MIME_SIP = "vnd.android.cursor.item/sip_address"

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
        val shortNames = shortNames()
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
                    shortName = shortNames[id],
                )
            }
        }
        LoadedContacts(rows, total)
    }

    /** The favorites row's label per starred contact (SPEC §4.3): the nickname, else the given name. */
    private fun shortNames(): Map<Long, String> {
        val nicknames = HashMap<Long, String>()
        val given = HashMap<Long, String>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA2),
            "${ContactsContract.Data.STARRED} = 1 AND ${ContactsContract.Data.MIMETYPE} IN (?, ?)",
            arrayOf(Nickname.CONTENT_ITEM_TYPE, StructuredName.CONTENT_ITEM_TYPE), null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                if (cursor.getString(1) == Nickname.CONTENT_ITEM_TYPE) cursor.getString(2)?.trim()?.takeIf { it.isNotEmpty() }?.let { nicknames.putIfAbsent(id, it) }
                else cursor.getString(3)?.trim()?.takeIf { it.isNotEmpty() }?.let { given.putIfAbsent(id, it) }
            }
        }
        return given + nicknames
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

    /** The phone's region for numbers without a country code (SPEC §7.8): the network's country, else the app's language's. */
    fun region(): String {
        val network = context.getSystemService(TelephonyManager::class.java)?.networkCountryIso.orEmpty()
        return network.ifEmpty { Locale.getDefault().country }.uppercase(Locale.ROOT)
    }

    /** What makes two numbers the same (SPEC §9.5): the international form where the region allows, else the digits. */
    fun numberKey(number: String, region: String = region()): String =
        PhoneNumberUtils.formatNumberToE164(number, region)?.let(Details::digits) ?: Details.digits(number)

    /** A number as the page and lists show it (SPEC §7.8); as stored when it can't be formatted. */
    fun displayNumber(number: String, region: String = region()): String = PhoneNumberUtils.formatNumber(number, region) ?: number

    /**
     * The contact a lookup key points to now – after a sync or a join it may have another id – with
     * everything the page shows; null when it is gone (SPEC §16.3).
     */
    suspend fun details(lookupKey: String, id: Long, lastFirst: Boolean): ContactDetails? = withContext(Dispatchers.IO) {
        val uri = ContactsContract.Contacts.lookupContact(resolver, ContactsContract.Contacts.getLookupUri(id, lookupKey)) ?: return@withContext null
        val contactId = ContentUris.parseId(uri)
        var name = ""
        var photo: String? = null
        var starred = false
        var key = lookupKey
        resolver.query(
            uri,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.DISPLAY_NAME_ALTERNATIVE, ContactsContract.Contacts.PHOTO_URI, ContactsContract.Contacts.STARRED, ContactsContract.Contacts.LOOKUP_KEY),
            null, null, null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            val primary = cursor.getString(0).orEmpty()
            name = if (lastFirst) cursor.getString(1)?.takeIf { it.isNotBlank() } ?: primary else primary
            photo = cursor.getString(2)
            starred = cursor.getInt(3) != 0
            key = cursor.getString(4) ?: lookupKey
        } ?: return@withContext null

        val accounts = accounts().associateBy { it.account }
        val adapters = contactsAdapters()
        val sources = ArrayList<Source>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            // Without RAW_CONTACT_IS_READ_ONLY: asking the storage for it here is the suspected cause of the page's crash of 17 Nov 2026; read-only follows the account's sync adapter
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0", arrayOf(contactId.toString()), ContactsContract.RawContacts._ID,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val account = AccountKey(cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty())
                val local = isLocal(account)
                val readOnly = !local && adapters[account.type] != true
                sources += Source(cursor.getLong(0), account, local, readOnly, accounts[account]?.app)
            }
        }

        var nickname: String? = null
        var phoneticGiven: String? = null
        var phoneticFamily: String? = null
        var company: String? = null
        val values = ArrayList<Detail>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID, ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3, ContactsContract.Data.DATA4,
                ContactsContract.Data.DATA5, ContactsContract.Data.DATA6, ContactsContract.Data.DATA7, ContactsContract.Data.DATA9,
                ContactsContract.Data.IS_SUPER_PRIMARY,
            ),
            "${ContactsContract.Data.CONTACT_ID} = ?", arrayOf(contactId.toString()), ContactsContract.Data._ID,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val dataId = cursor.getLong(0)
                val raw = cursor.getLong(1)
                val text = cursor.getString(3)?.trim().orEmpty()
                val type = if (cursor.isNull(4)) 0 else cursor.getInt(4)
                val label = cursor.getString(5)?.takeIf { it.isNotBlank() }
                val isDefault = cursor.getInt(11) != 0
                fun add(kind: DetailKind, protocol: Int? = null, customLabel: String? = label) {
                    if (text.isNotEmpty()) values += Detail(kind, text, type, customLabel, isDefault, raw, dataId, protocol)
                }
                when (cursor.getString(2)) {
                    StructuredName.CONTENT_ITEM_TYPE -> {
                        if (phoneticGiven == null) phoneticGiven = cursor.getString(9)?.trim()?.takeIf { it.isNotEmpty() }
                        if (phoneticFamily == null) phoneticFamily = cursor.getString(10)?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    Nickname.CONTENT_ITEM_TYPE -> if (nickname == null && text.isNotEmpty()) nickname = text
                    Organization.CONTENT_ITEM_TYPE -> if (company == null) {
                        company = listOf(text, cursor.getString(6)?.trim().orEmpty()).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }
                    }
                    Phone.CONTENT_ITEM_TYPE -> add(DetailKind.PHONE)
                    Email.CONTENT_ITEM_TYPE -> add(DetailKind.EMAIL)
                    StructuredPostal.CONTENT_ITEM_TYPE -> add(DetailKind.ADDRESS)
                    Website.CONTENT_ITEM_TYPE -> add(DetailKind.WEBSITE)
                    Event.CONTENT_ITEM_TYPE -> add(DetailKind.DATE)
                    Relation.CONTENT_ITEM_TYPE -> add(DetailKind.RELATION)
                    // The protocol (DATA5) and the custom protocol's name (DATA6); the storage's protocol constants are deprecated, the columns are not
                    MIME_IM -> {
                        val protocol = if (cursor.isNull(7)) null else cursor.getString(7)?.toIntOrNull()
                        add(DetailKind.IM, protocol, if (protocol == -1) cursor.getString(8)?.takeIf { it.isNotBlank() } else label)
                    }
                    MIME_SIP -> add(DetailKind.SIP)
                    Note.CONTENT_ITEM_TYPE -> add(DetailKind.NOTE)
                }
            }
        }
        val region = region()
        val order = sources.map { it.rawContactId }
        val grouped = values.groupBy { it.kind }.mapValues { (_, list) -> Details.distinct(list, order) { numberKey(it, region) } }
        ContactDetails(
            id = contactId,
            lookupKey = key,
            name = name,
            nickname = nickname,
            phonetic = listOfNotNull(phoneticGiven, phoneticFamily).joinToString(" ").ifEmpty { null },
            company = company,
            photo = photo,
            starred = starred,
            details = grouped,
            sources = sources,
        )
    }

    /** The one contact whose name is exactly [name], for a relation's tap (SPEC §6.4); null when there is none or more than one. */
    suspend fun onlyContactNamed(name: String): Pair<Long, String>? = withContext(Dispatchers.IO) {
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ?", arrayOf(name), null,
        )?.use { cursor ->
            if (cursor.count == 1 && cursor.moveToFirst()) cursor.getLong(0) to cursor.getString(1).orEmpty() else null
        }
    }

    /** The star (SPEC §6.1): written to the contact, which the storage hands on to its entries and the accounts sync. */
    suspend fun setStarred(id: Long, starred: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            resolver.update(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id), ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0) }, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    /** Makes a number or e-mail the contact's default (SPEC §6.3.1); the storage clears the flag on the others of its kind. */
    suspend fun setDefault(dataId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put(ContactsContract.Data.IS_PRIMARY, 1)
                put(ContactsContract.Data.IS_SUPER_PRIMARY, 1)
            }
            resolver.update(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId), values, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }
}
