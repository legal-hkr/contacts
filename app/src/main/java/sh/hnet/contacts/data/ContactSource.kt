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
import android.provider.ContactsContract.CommonDataKinds.Photo
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
import sh.hnet.contacts.model.Avatars
import sh.hnet.contacts.model.CardContact
import sh.hnet.contacts.model.ContactDetails
import sh.hnet.contacts.model.ContactList
import sh.hnet.contacts.model.ContactRow
import sh.hnet.contacts.model.Detail
import sh.hnet.contacts.model.DetailKind
import sh.hnet.contacts.model.Details
import sh.hnet.contacts.model.DupContact
import sh.hnet.contacts.model.Duplicates
import sh.hnet.contacts.model.EditKind
import sh.hnet.contacts.model.ListSettings
import sh.hnet.contacts.model.MatchKind
import sh.hnet.contacts.model.SearchContact
import sh.hnet.contacts.model.SearchField
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

/** A contact that an imported one already is (SPEC §10.3): its name and the account of its first entry. */
data class Existing(val id: Long, val name: String, val account: AccountKey, val local: Boolean)

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

    /** Emits whenever the storage changes (a sync, another app, the app itself); not at once – readers start with `onStart`. Without access it never emits: the system refuses to watch the storage then (SecurityException), which crashed the pages that watch it (Settings, Accounts, the duplicates) when access was refused. */
    val changes: Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val watching = hasAccess() && try {
            resolver.registerContentObserver(ContactsContract.AUTHORITY_URI, true, observer)
            true
        } catch (_: SecurityException) {
            false
        }
        awaitClose { if (watching) resolver.unregisterContentObserver(observer) }
    }

    /** The platform's local account (Android 14 and later), if the phone has one; entries without any account are local too. */
    private val localAccount: AccountKey? by lazy {
        val type = ContactsContract.RawContacts.getLocalAccountType(context)
        val name = ContactsContract.RawContacts.getLocalAccountName(context)
        if (type == null && name == null) null else AccountKey(type.orEmpty(), name.orEmpty())
    }

    /** Where a new local contact goes (SPEC §8.3): the platform's local account if the phone has one, else no account. */
    fun localTarget(): AccountKey = localAccount ?: AccountKey("", "")

    /**
     * The accounts a new contact can be saved to (SPEC §7.2): the writable synced ones, then "On this
     * phone", which always exists, whether local contacts do yet or not.
     */
    suspend fun targets(): List<AccountInfo> = withContext(Dispatchers.IO) {
        // Without counting contacts: the editor opens at once
        syncedAccounts(uploading = true).map { info(it, 0) }.sortedBy { it.account.name.lowercase() } + info(localTarget(), 0)
    }

    /** The default account's key if it is still a target, else the first target (SPEC §12.3, §13.3). */
    fun resolveDefault(targets: List<AccountInfo>, chosen: String?): AccountInfo = targets.firstOrNull { it.account.key == chosen } ?: targets.first()

    fun isLocal(account: AccountKey): Boolean = (account.type.isEmpty() && account.name.isEmpty()) || account == localAccount

    /**
     * Every account that has contacts or could take them: the accounts of the entries in the
     * storage, plus the accounts of apps that sync contacts and have none yet. Local contacts are
     * listed when they exist. Writable ones first, then by name.
     */
    /** The app an account type belongs to: its label, package and icon. */
    private class AccountApp(val label: String?, val pkg: String, val iconId: Int)

    /**
     * Account types' apps and the sync adapters, read once per process: asking the system for them
     * (and an app's label through its resources) on every page made opening a contact slow.
     * Accounts of an app installed later show its type until the process restarts.
     */
    private val apps: Map<String, AccountApp> by lazy {
        AccountManager.get(context).authenticatorTypes.associate { it.type to AccountApp(label(it.packageName, it.labelId), it.packageName, it.iconId) }
    }
    private val adapters: Map<String, Boolean> by lazy {
        ContentResolver.getSyncAdapterTypes().filter { it.authority == ContactsContract.AUTHORITY }.associate { it.accountType to it.supportsUploading() }
    }

    /**
     * Reads once what every page needs and the system answers slowly – the account apps' labels, the
     * sync adapters, the local account –, so the first contact page opens as fast as the next ones.
     * Called off the main thread when the app starts.
     */
    fun warmUp() {
        apps.size
        adapters.size
        localAccount
    }

    /** The label of the app an account type belongs to ("DAVx⁵"); null when the system doesn't say. */
    fun appLabel(type: String): String? = apps[type]?.label

    private fun info(account: AccountKey, count: Int): AccountInfo {
        val local = isLocal(account)
        val app = apps[account.type]
        return AccountInfo(account, local, if (local) null else app?.label, app?.pkg, app?.iconId ?: 0, local || adapters[account.type] == true, count)
    }

    /**
     * Accounts of apps that sync contacts, visible to an app holding the contacts permission, even
     * without contacts yet; with [uploading] only those that send changes to a server (new contacts can go there).
     */
    private fun syncedAccounts(uploading: Boolean): List<AccountKey> {
        // AccountManager shows only the accounts their app lets other apps see: DAVx⁵ hides its address books' accounts there (setAccountVisibility), so the editor offered only "On this phone". The storage's per-account settings, which the syncing apps write, name them too, and stay small, unlike reading every entry.
        val visible = AccountManager.get(context).accounts.map { AccountKey(it.type, it.name) }
        return (visible + settingsAccounts()).distinct().filter { if (uploading) adapters[it.type] == true else it.type in adapters }
    }

    /** The accounts with a row in the storage's settings (`ContactsContract.Settings`). */
    private fun settingsAccounts(): List<AccountKey> = try {
        val found = ArrayList<AccountKey>()
        resolver.query(ContactsContract.Settings.CONTENT_URI, arrayOf(ContactsContract.Settings.ACCOUNT_TYPE, ContactsContract.Settings.ACCOUNT_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val type = cursor.getString(0)
                val name = cursor.getString(1)
                if (!type.isNullOrEmpty() && !name.isNullOrEmpty()) found += AccountKey(type, name)
            }
        }
        found
    } catch (_: Exception) {
        emptyList()
    }

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
        (counts.keys + syncedAccounts(uploading = false)).distinct().map { account -> info(account, counts[account]?.size ?: 0) }.sortedWith(compareBy<AccountInfo> { it.local }.thenBy { !it.writable }.thenBy { it.account.name.lowercase() })
    }

    /** Account types with a contacts sync adapter, and whether it sends changes to its server (read-only accounts don't, SPEC §6.5). */
    fun contactsAdapters(): Map<String, Boolean> = adapters

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
        val all = rows(settings, hidden)
        LoadedContacts(all.filter { it.second }.map { it.first }, all.size)
    }

    /** Every contact as a list row, and whether it has an entry in an account not in [hidden]. */
    private fun rows(settings: ListSettings, hidden: Set<String>): List<Pair<ContactRow, Boolean>> {
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
        val names = nameParts()
        val rows = ArrayList<Pair<ContactRow, Boolean>>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.DISPLAY_NAME_ALTERNATIVE,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                ContactsContract.Contacts.STARRED,
                ContactsContract.Contacts.PHOTO_FILE_ID,
                ContactsContract.Contacts.PHOTO_ID,
                ContactsContract.Contacts.PHOTO_URI,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val primary = cursor.getString(2).orEmpty()
                val alternative = cursor.getString(3)?.takeIf { it.isNotBlank() } ?: primary
                rows += ContactRow(
                    id = id,
                    lookupKey = cursor.getString(1).orEmpty(),
                    name = if (settings.lastFirst) alternative else primary,
                    sortName = names[id].let { parts -> ContactList.sortName(parts?.given, parts?.middle, parts?.family, settings.sortByLast, if (settings.sortByLast) alternative else primary) },
                    photo = photoAddress(cursor.getString(4), cursor.getString(6), cursor.getString(7)),
                    starred = cursor.getInt(5) != 0,
                    company = companies[id],
                    shortName = shortNames[id],
                    initials = names[id]?.let { Avatars.initials(it.given, it.family, Locale.getDefault()) },
                    largePhoto = photoAddress(cursor.getString(8), cursor.getString(6), cursor.getString(7)),
                ) to (id in shown)
            }
        }
        return rows
    }

    /**
     * Every contact with what search looks in (SPEC §5.2): the parts of the name, nickname, phonetic
     * name, numbers with their types, e-mails, company and title, notes. Hidden accounts' contacts are
     * in it too, marked, for "Search all". Kept in memory only while the search is open.
     */
    suspend fun searchIndex(settings: ListSettings, hidden: Set<String>): List<SearchContact> = withContext(Dispatchers.IO) {
        val fields = HashMap<Long, MutableList<SearchField>>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3, ContactsContract.Data.DATA4,
                ContactsContract.Data.DATA5, ContactsContract.Data.DATA7, ContactsContract.Data.DATA8, ContactsContract.Data.DATA9,
            ),
            "${ContactsContract.Data.MIMETYPE} IN (?, ?, ?, ?, ?, ?)",
            arrayOf(StructuredName.CONTENT_ITEM_TYPE, Nickname.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE, Note.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val list = fields.getOrPut(cursor.getLong(0)) { ArrayList() }
                fun text(column: Int) = cursor.getString(column)?.trim()?.takeIf { it.isNotEmpty() }
                fun add(kind: MatchKind, column: Int) = text(column)?.let { list += SearchField(kind, it) }
                when (cursor.getString(1)) {
                    StructuredName.CONTENT_ITEM_TYPE -> {
                        // Given, family, middle; then the phonetic given, middle, family
                        for (column in intArrayOf(3, 4, 6)) add(MatchKind.NAME, column)
                        listOfNotNull(text(7), text(8), text(9)).joinToString(" ").takeIf { it.isNotEmpty() }?.let { list += SearchField(MatchKind.PHONETIC, it) }
                    }
                    Nickname.CONTENT_ITEM_TYPE -> add(MatchKind.NICKNAME, 2)
                    Phone.CONTENT_ITEM_TYPE -> text(2)?.let { list += SearchField(MatchKind.PHONE, it, if (cursor.isNull(3)) 0 else cursor.getInt(3), text(4)) }
                    Email.CONTENT_ITEM_TYPE -> add(MatchKind.EMAIL, 2)
                    Organization.CONTENT_ITEM_TYPE -> {
                        add(MatchKind.COMPANY, 2)
                        add(MatchKind.COMPANY, 5)
                    }
                    Note.CONTENT_ITEM_TYPE -> add(MatchKind.NOTE, 2)
                }
            }
        }
        rows(settings, hidden).map { (row, shown) -> SearchContact(row, shown, fields[row.id].orEmpty()) }
    }

    /** The country calling code of the phone's region ("48"), for numbers typed with or without it (SPEC §5.2); null when unknown. */
    fun countryCode(region: String = region()): String? {
        val national = "600000000"
        val international = PhoneNumberUtils.formatNumberToE164(national, region) ?: return null
        return international.removePrefix("+").removeSuffix(national.trimStart('0')).takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 3 }
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

    private class NameParts(val given: String?, val middle: String?, val family: String?)

    /** The parts of each contact's name, from its first name row with any: the letter avatar's letters (the owner, 18 Nov 2026) and the name the list sorts by, without a title. */
    private fun nameParts(): Map<Long, NameParts> {
        val result = HashMap<Long, NameParts>()
        // The entry the shown name comes from wins (NAME_RAW_CONTACT_ID): a joined contact "Anna Nowak" with a local entry "Mama" filed under M otherwise (the code review of 18 Nov 2026)
        val fromShown = HashSet<Long>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, StructuredName.GIVEN_NAME, StructuredName.MIDDLE_NAME, StructuredName.FAMILY_NAME, ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.Contacts.NAME_RAW_CONTACT_ID),
            "${ContactsContract.Data.MIMETYPE} = ?", arrayOf(StructuredName.CONTENT_ITEM_TYPE), null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                if (id in fromShown) continue
                val shown = !cursor.isNull(5) && cursor.getLong(4) == cursor.getLong(5)
                if (id in result && !shown) continue
                val parts = NameParts(cursor.getString(1), cursor.getString(2), cursor.getString(3))
                if (listOf(parts.given, parts.middle, parts.family).any { !it.isNullOrBlank() }) {
                    result[id] = parts
                    if (shown) fromShown += id
                }
            }
        }
        return result
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

    /**
     * A photo's address with what tells this photo from the next: the storage keeps the same address
     * when a contact's photo is replaced, so a remembered thumbnail would stay (the owner saw the old
     * one in the list after cropping again). The file's and the row's ids go into the fragment, which
     * the storage ignores; [sh.hnet.contacts.ui.components.Avatar] reads the address without it.
     */
    fun photoAddress(uri: String?, fileId: String?, photoId: String?): String? = uri?.let { "$it#${fileId.orEmpty()}.${photoId.orEmpty()}" }

    /** The phone's region for numbers without a country code (SPEC §7.8): the network's country, else the app's language's. */
    fun region(): String {
        val network = context.getSystemService(TelephonyManager::class.java)?.networkCountryIso.orEmpty()
        return network.ifEmpty { Locale.getDefault().country }.uppercase(Locale.ROOT)
    }

    /**
     * What makes two numbers the same (SPEC §9.2, §9.5): their last nine digits – the national
     * number in most countries, Poland's included –, so "+48 609 111 222" and "609111222" agree
     * whatever region the phone is in (on the owner's emulator, in the USA, the international form
     * told them apart); shorter numbers whole.
     */
    fun numberKey(number: String, @Suppress("UNUSED_PARAMETER") region: String = ""): String {
        val digits = Details.digits(number)
        return if (digits.length > NATIONAL_DIGITS) digits.takeLast(NATIONAL_DIGITS) else digits
    }

    private companion object {
        const val NATIONAL_DIGITS = 9
    }

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
        var nameEntry = -1L
        resolver.query(
            uri,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.DISPLAY_NAME_ALTERNATIVE, ContactsContract.Contacts.PHOTO_URI, ContactsContract.Contacts.STARRED, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.PHOTO_FILE_ID, ContactsContract.Contacts.PHOTO_ID, ContactsContract.Contacts.NAME_RAW_CONTACT_ID),
            null, null, null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            val primary = cursor.getString(0).orEmpty()
            name = if (lastFirst) cursor.getString(1)?.takeIf { it.isNotBlank() } ?: primary else primary
            photo = photoAddress(cursor.getString(2), cursor.getString(5), cursor.getString(6))
            starred = cursor.getInt(3) != 0
            key = cursor.getString(4) ?: lookupKey
            if (!cursor.isNull(7)) nameEntry = cursor.getLong(7)
        } ?: return@withContext null

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
                sources += Source(cursor.getLong(0), account, local, readOnly, appLabel(account.type))
            }
        }

        var nickname: String? = null
        var phoneticGiven: String? = null
        var phoneticFamily: String? = null
        var company: String? = null
        var initials: String? = null
        var givenName: String? = null
        // The letters and the short name come from the entry the shown name comes from, as the list's (ContactSource.nameParts) and Dialer's: a joined contact's page showed other letters than its row (found in Dialer's session, 26 Nov 2026)
        var namedFromShown = false
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
                        val given = cursor.getString(4)?.trim()?.takeIf { it.isNotEmpty() }
                        val parts = given != null || !cursor.getString(5).isNullOrBlank() || !cursor.getString(7).isNullOrBlank()
                        val shown = raw == nameEntry && parts
                        if (!namedFromShown && (shown || (initials == null && parts))) {
                            initials = Avatars.initials(cursor.getString(4), cursor.getString(5), Locale.getDefault())
                            givenName = given
                            namedFromShown = shown
                        }
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
            initials = initials,
            givenName = givenName,
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

    /**
     * Every contact of the shown accounts as the duplicate search compares them (SPEC §9.2): the
     * folded name, number keys, e-mails, addresses and websites.
     */
    suspend fun dupContacts(hidden: Set<String>): List<DupContact> = withContext(Dispatchers.IO) {
        // Names in the order people write them ("Jan Kowalski"), whatever the list sorts by: imported files and other contacts are compared with them
        val rows = rows(ListSettings(sortByLast = false, lastFirst = false), hidden).filter { it.second }.map { it.first }
        val numbers = HashMap<Long, MutableSet<String>>()
        val emails = HashMap<Long, MutableSet<String>>()
        val others = HashMap<Long, MutableSet<String>>()
        val region = region()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1),
            "${ContactsContract.Data.MIMETYPE} IN (?, ?, ?, ?)",
            arrayOf(Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE, StructuredPostal.CONTENT_ITEM_TYPE, Website.CONTENT_ITEM_TYPE), null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val value = cursor.getString(2)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                when (cursor.getString(1)) {
                    Phone.CONTENT_ITEM_TYPE -> numbers.getOrPut(id) { HashSet() } += numberKey(value, region)
                    Email.CONTENT_ITEM_TYPE -> emails.getOrPut(id) { HashSet() } += value.lowercase()
                    else -> others.getOrPut(id) { HashSet() } += value.lowercase()
                }
            }
        }
        rows.map { DupContact(it.id, Duplicates.foldName(it.sortName), numbers[it.id].orEmpty(), emails[it.id].orEmpty(), others[it.id].orEmpty()) }
    }

    /** The contact pairs the storage keeps apart ("not the same person", SPEC §9.2), from its entries' exceptions. */
    suspend fun separatePairs(): Set<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        val rawPairs = ArrayList<Pair<Long, Long>>()
        resolver.query(
            ContactsContract.AggregationExceptions.CONTENT_URI,
            arrayOf(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, ContactsContract.AggregationExceptions.RAW_CONTACT_ID2),
            "${ContactsContract.AggregationExceptions.TYPE} = ${ContactsContract.AggregationExceptions.TYPE_KEEP_SEPARATE}", null, null,
        )?.use { cursor -> while (cursor.moveToNext()) rawPairs += cursor.getLong(0) to cursor.getLong(1) }
        if (rawPairs.isEmpty()) return@withContext emptySet()
        val contactOf = HashMap<Long, Long>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.CONTACT_ID),
            "${ContactsContract.RawContacts.DELETED} = 0", null, null,
        )?.use { cursor -> while (cursor.moveToNext()) contactOf[cursor.getLong(0)] = cursor.getLong(1) }
        rawPairs.mapNotNull { (a, b) -> val ca = contactOf[a] ?: return@mapNotNull null; val cb = contactOf[b] ?: return@mapNotNull null; ca to cb }.toSet()
    }

    /**
     * For each of [cards], the shown contact it already is (SPEC §10.3): the same name (folded as
     * the duplicate search folds it) and a number or e-mail in common; null for a new one.
     */
    suspend fun existing(cards: List<CardContact>, hidden: Set<String>): List<Existing?> = withContext(Dispatchers.IO) {
        val byName = dupContacts(hidden).filter { it.name.isNotEmpty() }.groupBy { it.name }
        val region = region()
        val found = cards.map { card ->
            // The file's display name, and the name its parts make in either order (a contact saved since shows that one)
            val parts = card.name
            val names = listOf(
                card.displayName,
                listOf("data4", "data2", "data5", "data3", "data6").mapNotNull { parts[it] }.joinToString(" "),
                listOf("data3", "data2", "data5").mapNotNull { parts[it] }.joinToString(" "),
            ).map { Duplicates.foldName(it) }.filter { it.isNotEmpty() }.distinct()
            val candidates = names.flatMap { byName[it].orEmpty() }.ifEmpty { return@map null }
            // Short numbers count here (112, an extension): the same name is needed too, unlike the duplicate search (owner's test, 18 Nov 2026)
            val numbers = card.values[EditKind.PHONE].orEmpty().map { numberKey(it.part("data1"), region) }.filter { it.isNotEmpty() }.toSet()
            val emails = card.values[EditKind.EMAIL].orEmpty().map { it.part("data1").lowercase() }.toSet()
            candidates.firstOrNull { c -> c.numbers.any { it in numbers } || c.emails.any { it in emails } }?.id
        }
        val ids = found.filterNotNull().distinct()
        if (ids.isEmpty()) return@withContext found.map { null }
        // The name and the first entry's account of each, for "This contact already exists (Anna Kowalska, jan@cloud.hnet.sh)"
        val accounts = HashMap<Long, AccountKey>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID, ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME),
            "${ContactsContract.RawContacts.CONTACT_ID} IN (${ids.joinToString(",")}) AND ${ContactsContract.RawContacts.DELETED} = 0", null, ContactsContract.RawContacts._ID,
        )?.use { cursor -> while (cursor.moveToNext()) accounts.putIfAbsent(cursor.getLong(0), AccountKey(cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty())) }
        val names = HashMap<Long, String>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts._ID} IN (${ids.joinToString(",")})", null, null,
        )?.use { cursor -> while (cursor.moveToNext()) names[cursor.getLong(0)] = cursor.getString(1).orEmpty() }
        found.map { id -> id?.let { Existing(it, names[it].orEmpty(), accounts[it] ?: AccountKey("", ""), isLocal(accounts[it] ?: AccountKey("", ""))) } }
    }

    /** The contacts with an entry in any of [accounts], in name order (the export, SPEC §11.1): joined ones once. */
    suspend fun contactsIn(accounts: Set<AccountKey>): List<Long> = withContext(Dispatchers.IO) {
        val ids = LinkedHashSet<Long>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID, ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME),
            "${ContactsContract.RawContacts.DELETED} = 0", null, ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED",
        )?.use { cursor ->
            while (cursor.moveToNext()) if (AccountKey(cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty()) in accounts) ids += cursor.getLong(0)
        }
        ids.toList()
    }

    /**
     * The bytes the photos of [ids] add to a vCard file (SPEC §11.1): each full-size photo's file,
     * or the thumbnail where there is none, with base64's third more.
     */
    suspend fun photoBytes(ids: Collection<Long>): Long = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0L
        val wanted = ids.toSet()
        var total = 0L
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.PHOTO_FILE_ID, ContactsContract.Contacts.PHOTO_ID),
            "${ContactsContract.Contacts.PHOTO_ID} IS NOT NULL", null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getLong(0) !in wanted) continue
                total += if (!cursor.isNull(1)) {
                    try {
                        resolver.openAssetFileDescriptor(ContentUris.withAppendedId(ContactsContract.DisplayPhoto.CONTENT_URI, cursor.getLong(1)), "r")?.use { it.length.coerceAtLeast(0) } ?: 0
                    } catch (_: Exception) {
                        0
                    }
                } else {
                    resolver.query(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, cursor.getLong(2)), arrayOf(Photo.PHOTO), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getBlob(0)?.size?.toLong() ?: 0 else 0 } ?: 0
                }
            }
        }
        total * 137 / 100
    }
}
