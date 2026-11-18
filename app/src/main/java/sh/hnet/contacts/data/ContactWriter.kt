package sh.hnet.contacts.data

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.contacts.model.AccountKey
import sh.hnet.contacts.model.CardContact
import sh.hnet.contacts.model.CardValue
import sh.hnet.contacts.model.EditKind
import sh.hnet.contacts.model.EditorDiff
import sh.hnet.contacts.model.EntryState
import sh.hnet.contacts.model.FormValue
import sh.hnet.contacts.model.Origin
import sh.hnet.contacts.model.UploadCheck
import sh.hnet.contacts.model.Write

/** An entry of the contact being edited, with the version it had when the editor read it (SPEC §7.7). */
data class EditEntry(val rawContactId: Long, val account: AccountKey, val local: Boolean, val readOnly: Boolean, val version: Int, val contactId: Long = 0)

/** A contact as the editor reads it: its entries and every value with the rows it came from. */
data class EditSnapshot(val contactId: Long, val lookupKey: String, val entries: List<EditEntry>, val values: List<FormValue>, val photo: String?) {
    /** New values go here (SPEC §7.6): the first writable entry, in the order the page lists them. */
    val target: EditEntry? get() = entries.firstOrNull { !it.readOnly }
}

/**
 * A change that waits for its Undo snackbar to go (SPEC §6.8, §8.4). A delete: the [contacts] that
 * have writable entries leave the views, their [rawContactIds] are deleted on commit, [skipped]
 * contacts only in read-only accounts stay. A move: nothing leaves the views, the old entries
 * ([rawContactIds]) are deleted on commit, and Undo deletes the copies ([undoRawContactIds]).
 */
data class Deletion(val contacts: Set<Long>, val rawContactIds: List<Long>, val skipped: Int, val undoRawContactIds: List<Long> = emptyList())

/** A move done as far as copying: the old entries to delete and the copies made, for Undo; [failed] contacts couldn't be copied. */
data class Moved(val oldRawContactIds: List<Long>, val newRawContactIds: List<Long>, val failed: Int)

/** What an import wrote ([rawContactIds], for Undo), and the failure that stopped it, if one did. */
data class Imported(val rawContactIds: List<Long>, val error: Exception?)

/** A duplicate group read for the review: everything of all its contacts together ([all]) and each contact's own reading ([each]). */
data class MergeSource(val all: EditSnapshot, val each: List<EditSnapshot>)

/** What the editor does with the photo on Save. */
sealed interface PhotoChange {
    data object None : PhotoChange
    data object Remove : PhotoChange

    /** A cropped square JPEG (SPEC §7.3). */
    class Set(val jpeg: ByteArray) : PhotoChange
}

/** A saved contact, to open its page; [photoFailed] when the contact was saved but its photo couldn't be written. */
/**
 * A saved contact. [upload] is set when the save went to entries of accounts that sync: the editor waits for their app to send it (the owner's D1, 18 Nov 2026); [unconfirmed] when it stopped waiting before an answer (D2).
 */
data class Saved(val contactId: Long, val lookupKey: String, val photoFailed: Boolean = false, val account: AccountKey? = null, val upload: Upload? = null, val unconfirmed: Boolean = false)

/** What a save wrote to entries of accounts that sync, to tell later whether their app sent it: the [entries], their data [rows] right after the save, and the contact's [content] as it was then (`EditorDiff.content`). */
data class Upload(val entries: List<Long>, val rows: Set<Long>, val content: List<String>)

/** The storage's columns of each kind and the type and label columns of those that have them. */
private object Columns {
    val mime = mapOf(
        EditKind.NAME to StructuredName.CONTENT_ITEM_TYPE,
        EditKind.PHONE to Phone.CONTENT_ITEM_TYPE,
        EditKind.EMAIL to Email.CONTENT_ITEM_TYPE,
        EditKind.ADDRESS to StructuredPostal.CONTENT_ITEM_TYPE,
        EditKind.COMPANY to Organization.CONTENT_ITEM_TYPE,
        EditKind.WEBSITE to Website.CONTENT_ITEM_TYPE,
        EditKind.DATE to Event.CONTENT_ITEM_TYPE,
        EditKind.RELATION to Relation.CONTENT_ITEM_TYPE,
        EditKind.NICKNAME to Nickname.CONTENT_ITEM_TYPE,
        EditKind.IM to MIME_IM,
        EditKind.SIP to MIME_SIP,
        EditKind.NOTE to Note.CONTENT_ITEM_TYPE,
    )
    val kindOf = mime.entries.associate { (kind, type) -> type to kind }

    /** The parts the form edits: name prefix, given, middle, family, suffix, phonetic given, middle, family; address street, city, region, postcode, country; company and title. */
    val parts = mapOf(
        EditKind.NAME to listOf("data4", "data2", "data5", "data3", "data6", "data7", "data8", "data9"),
        EditKind.ADDRESS to listOf("data4", "data7", "data8", "data9", "data10"),
        EditKind.COMPANY to listOf("data1", "data4"),
    )

    fun partsOf(kind: EditKind): List<String> = parts[kind] ?: listOf("data1")

    /** Kinds with a type (data2) and a custom label (data3). */
    val typed = setOf(EditKind.PHONE, EditKind.EMAIL, EditKind.ADDRESS, EditKind.WEBSITE, EditKind.DATE, EditKind.RELATION, EditKind.SIP)

    /** The messaging address's type: Other, as the deprecated kind's constant was (3); the protocol says what it is. */
    const val IM_TYPE_OTHER = 3
}

/**
 * The editor's reads and writes of the contacts storage (SPEC §7): the contact with every value
 * and the rows it came from, and Save as one batch of provider operations, worked out by
 * [EditorDiff]. Nothing is logged.
 */
class ContactWriter(private val context: Context, private val source: ContactSource) {
    private companion object {
        /** Contacts per batch of an import (SPEC §10.4). */
        const val IMPORT_BATCH = 50

        /** Deletes per batch: well under the storage's 500 operations per batch. */
        const val DELETE_BATCH = 250

        /** The longer side of an imported photo, in pixels (SPEC §10.4). */
        const val IMPORT_PHOTO = 720
    }

    private val resolver = context.contentResolver
    private val keys = AtomicLong(1)

    /** A key for a new value of the form. */
    fun newKey(): Long = keys.getAndIncrement()

    /** The contact a lookup key points to now, ready for the form; null when it is gone. */
    suspend fun load(lookupKey: String, id: Long): EditSnapshot? = withContext(Dispatchers.IO) {
        val uri = ContactsContract.Contacts.lookupContact(resolver, ContactsContract.Contacts.getLookupUri(id, lookupKey)) ?: return@withContext null
        read(uri, lookupKey)
    }

    /**
     * The contacts [ids] as vCard contacts (SPEC §11.4): the values of all their entries, equal ones
     * once, and the photo in its full size. Contacts gone meanwhile are left out.
     */
    suspend fun cards(ids: List<Long>, accounts: Set<AccountKey>? = null, photos: Boolean = true, onProgress: (Int) -> Unit = {}): List<CardContact> = withContext(Dispatchers.IO) {
        ids.mapIndexedNotNull { index, id ->
            onProgress(index)
            val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
            val read = read(uri, "") ?: return@mapIndexedNotNull null
            // The export of chosen accounts (SPEC §11.3): only what their entries hold
            val entries = if (accounts == null) null else read.entries.filter { it.account in accounts }.map { it.rawContactId }.toSet()
            val values = if (entries == null) read.values else read.values.filter { value -> value.origins.any { it.rawContactId in entries } }
            val name = resolver.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()
            val photo = if (!photos) null else try {
                ContactsContract.Contacts.openContactPhotoInputStream(resolver, uri, true)?.use { it.readBytes() }
            } catch (_: Exception) {
                null
            }
            CardContact(
                displayName = name,
                name = (values.firstOrNull { it.kind == EditKind.NAME } ?: read.values.firstOrNull { it.kind == EditKind.NAME })?.parts.orEmpty(),
                values = values.filter { it.kind != EditKind.NAME }.groupBy { it.kind }.mapValues { (_, list) -> list.map { CardValue(it.parts, it.type, it.label, it.isDefault, it.protocol) } },
                photo = photo,
            )
        }
    }

    /**
     * Imports [cards] into [account] (SPEC §10.4): 50 contacts per batch, each a new entry with its
     * name and values, then its photo as JPEG of at most 720 px. Stops at the first batch the
     * storage refuses; what was written before stays, and is returned for Undo.
     */
    suspend fun importCards(cards: List<CardContact>, account: AccountKey, onProgress: (Int) -> Unit): Imported = withContext(Dispatchers.IO) {
        val created = ArrayList<Long>()
        var error: Exception? = null
        for (chunk in cards.chunked(IMPORT_BATCH)) {
            val operations = ArrayList<ContentProviderOperation>()
            val starts = ArrayList<Int>()
            for (card in chunk) {
                val start = operations.size
                starts += start
                operations += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type.ifEmpty { null })
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account.name.ifEmpty { null })
                    .withYieldAllowed(true)
                    .build()
                val name = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, start)
                    .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                // The parts and the file's own display name (FN) both: the storage keeps them as given, and splits the name itself when there are no parts
                for (column in Columns.partsOf(EditKind.NAME)) name.withValue(column, card.name[column]?.trim()?.ifEmpty { null })
                name.withValue(StructuredName.DISPLAY_NAME, card.displayName.ifEmpty { null })
                operations += name.build()
                for ((kind, values) in card.values) for (value in values) {
                    operations += insert(FormValue(0, kind, value.parts, value.type, value.label, value.protocol, value.isDefault), null, true, start)
                }
            }
            try {
                val results = resolver.applyBatch(ContactsContract.AUTHORITY, operations)
                val ids = starts.map { ContentUris.parseId(requireNotNull(results[it].uri)) }
                chunk.forEachIndexed { i, card ->
                    // A photo that can't be decoded or written: the contact stays without it
                    card.photo?.let { bytes -> runCatching { ImageCodec.jpeg(bytes, IMPORT_PHOTO)?.let { writePhoto(PhotoChange.Set(it), listOf(ids[i])) } } }
                }
                created += ids
                onProgress(created.size)
            } catch (e: Exception) {
                error = e
                break
            }
        }
        Imported(created, error)
    }

    /**
     * The contact at [uri] for the form; with [others], those contacts' entries and values too, as
     * one set (the review of a merge, SPEC §9.4): equal values of all of them once.
     */
    private fun read(uri: Uri, lookupKey: String, others: List<Long> = emptyList()): EditSnapshot? {
        val contactId = ContentUris.parseId(uri)
        val ids = (listOf(contactId) + others).distinct()
        val inIds = ids.joinToString(",")
        var key = lookupKey
        var photo: String? = null
        var nameSource = 0L
        resolver.query(uri, arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.PHOTO_URI, ContactsContract.Contacts.NAME_RAW_CONTACT_ID, ContactsContract.Contacts.PHOTO_FILE_ID, ContactsContract.Contacts.PHOTO_ID), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            key = cursor.getString(0) ?: lookupKey
            photo = source.photoAddress(cursor.getString(1), cursor.getString(3), cursor.getString(4))
            nameSource = cursor.getLong(2)
        } ?: return null
        val adapters = source.contactsAdapters()
        val entries = ArrayList<EditEntry>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.VERSION, ContactsContract.RawContacts.CONTACT_ID),
            "${ContactsContract.RawContacts.CONTACT_ID} IN ($inIds) AND ${ContactsContract.RawContacts.DELETED} = 0", null, ContactsContract.RawContacts._ID,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val account = AccountKey(cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty())
                val local = source.isLocal(account)
                entries += EditEntry(cursor.getLong(0), account, local, !local && adapters[account.type] != true, cursor.getInt(3), cursor.getLong(4))
            }
        }
        // The asked contact's entries first, then the others' in the order they were given
        entries.sortBy { ids.indexOf(it.contactId) }
        val readOnlyEntries = entries.filter { it.readOnly }.map { it.rawContactId }.toSet()
        val region = source.region()
        // Read rows grouped by what makes them equal (SPEC §9.5); the name is one value for the whole contact
        val groups = LinkedHashMap<String, MutableList<Pair<FormValue, Long>>>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID, ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.IS_SUPER_PRIMARY,
                "data1", "data2", "data3", "data4", "data5", "data6", "data7", "data8", "data9", "data10",
            ),
            "${ContactsContract.Data.CONTACT_ID} IN ($inIds)", null, ContactsContract.Data._ID,
        )?.use { cursor ->
            fun column(name: String): String? = cursor.getString(cursor.getColumnIndexOrThrow(name))?.takeIf { it.isNotBlank() }
            while (cursor.moveToNext()) {
                val kind = Columns.kindOf[cursor.getString(2)] ?: continue
                val raw = cursor.getLong(1)
                val parts = Columns.partsOf(kind).mapNotNull { name -> column(name)?.let { name to it } }.toMap().toMutableMap()
                // An address kept only as one formatted text: it goes to the street field whole
                if (kind == EditKind.ADDRESS && parts.isEmpty()) column("data1")?.let { parts["data4"] = it }
                val im = kind == EditKind.IM
                val value = FormValue(
                    key = 0,
                    kind = kind,
                    parts = parts,
                    type = if (kind in Columns.typed) column("data2")?.toIntOrNull() ?: 0 else 0,
                    label = if (im) column("data6") else if (kind in Columns.typed) column("data3") else null,
                    protocol = if (im) column("data5")?.toIntOrNull() else null,
                    isDefault = cursor.getInt(3) != 0,
                    origins = listOf(Origin(raw, cursor.getLong(0))),
                )
                if (value.blank) continue
                val group = if (kind == EditKind.NAME) "name" else groupKey(value, region)
                groups.getOrPut(group) { ArrayList() } += value to raw
            }
        }
        val order = entries.map { it.rawContactId }
        val values = groups.values.map { rows ->
            // The copy shown: the name's source entry for the name, else the first entry in the page's order
            val sorted = rows.sortedBy { (value, raw) -> if (value.kind == EditKind.NAME && raw == nameSource) -1 else order.indexOf(raw).let { if (it < 0) Int.MAX_VALUE else it } }
            val first = sorted.first().first
            first.copy(
                key = newKey(),
                isDefault = rows.any { it.first.isDefault },
                origins = rows.flatMap { it.first.origins },
                readOnly = rows.all { it.second in readOnlyEntries },
            )
        }
        return EditSnapshot(contactId, key, entries, values, photo)
    }

    private fun groupKey(value: FormValue, region: String): String {
        val text = value.parts.toSortedMap().values.joinToString("|") { it.trim() }
        return when (value.kind) {
            EditKind.PHONE -> "phone|" + source.numberKey(value.part("data1"), region)
            EditKind.EMAIL -> "email|" + text.lowercase()
            EditKind.DATE, EditKind.RELATION -> "${value.kind}|${value.type}|${value.label.orEmpty()}|$text"
            else -> "${value.kind}|$text"
        }
    }

    /** The versions of [entries] now; an entry gone is missing (SPEC §7.7). */
    suspend fun versions(entries: Collection<Long>): Map<Long, Int> = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext emptyMap()
        val result = HashMap<Long, Int>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.VERSION),
            "${ContactsContract.RawContacts._ID} IN (${entries.joinToString(",")}) AND ${ContactsContract.RawContacts.DELETED} = 0", null, null,
        )?.use { cursor -> while (cursor.moveToNext()) result[cursor.getLong(0)] = cursor.getInt(1) }
        result
    }

    /**
     * Saves the form: a new contact in [account] when [snapshot] is null, else the changes to it.
     * [recreate] saves a contact deleted meanwhile again, whole, in the account of its first
     * writable entry. Returns the contact to open, or null when the storage refused.
     */
    suspend fun save(snapshot: EditSnapshot?, edited: List<FormValue>, account: AccountKey?, photo: PhotoChange, recreate: Boolean = false): Saved? = withContext(Dispatchers.IO) {
        try {
            val operations = ArrayList<ContentProviderOperation>()
            val newEntry = snapshot == null || recreate
            val target = snapshot?.target
            val writable = snapshot?.entries.orEmpty().filter { !it.readOnly }.map { it.rawContactId }.toSet()
            if (newEntry) {
                val into = if (recreate) target?.account else account
                operations += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, into?.type?.ifEmpty { null })
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, into?.name?.ifEmpty { null })
                    .build()
            }
            val diff = if (newEntry) EditorDiff.recreate(edited) else EditorDiff.writes(snapshot.values, edited, target?.rawContactId)
            // A row a sync deleted while the form was open: the user's value goes in as a new row instead of updating nothing
            val gone = if (newEntry) emptySet() else missingRows(diff.filterIsInstance<Write.Update>().map { it.dataId })
            val writes = diff.mapNotNull { write ->
                when {
                    write is Write.Update && write.dataId in gone -> Write.Insert(target?.rawContactId, write.value).takeIf { target != null }
                    write is Write.Delete && write.dataId in gone -> null
                    else -> write
                }
            }.distinctBy { if (it is Write.Insert) it.value.key to it.rawContactId else it }
            // A value shared by a writable and a read-only entry changes only in the writable one
            val readOnlyRows = snapshot?.values.orEmpty().flatMap { it.origins }.filter { it.rawContactId !in writable }.map { it.dataId }.toSet()
            for (write in writes) {
                if (!newEntry && ((write is Write.Update && write.dataId in readOnlyRows) || (write is Write.Delete && write.dataId in readOnlyRows))) continue
                when (write) {
                    is Write.Insert -> operations += insert(write.value, write.rawContactId, newEntry)
                    is Write.Update -> operations += update(write.dataId, write.value)
                    is Write.Delete -> operations += ContentProviderOperation.newDelete(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, write.dataId)).build()
                }
            }
            // The name is written to every writable entry, so they agree afterwards (SPEC §7.6): entries without a name row get one
            if (!newEntry) {
                val name = edited.firstOrNull { it.kind == EditKind.NAME && !it.blank }
                val oldName = snapshot.values.firstOrNull { it.kind == EditKind.NAME }
                if (name != null && (oldName == null || !oldName.sameContent(name))) {
                    val named = oldName?.origins.orEmpty().map { it.rawContactId }.toSet()
                    for (raw in writable - named) if (raw != target?.rawContactId || oldName != null) operations += insert(name, raw, false)
                }
            }
            val results = resolver.applyBatch(ContactsContract.AUTHORITY, operations)
            val rawIds = if (newEntry) listOfNotNull(results.firstOrNull()?.uri?.let(ContentUris::parseId)) else writable.toList()
            // The contact is saved from here on: nothing after it may report the save as failed, or a retry would save it twice
            val photoFailed = try {
                writePhoto(photo, rawIds)
                false
            } catch (_: Exception) {
                true
            }
            val first = rawIds.firstOrNull() ?: target?.rawContactId
            val found = try {
                first?.let { ContactsContract.RawContacts.getContactLookupUri(resolver, ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, it)) }?.let(::contact)
            } catch (_: Exception) {
                null
            }
            val saved = (found ?: snapshot?.let { Saved(it.contactId, it.lookupKey) } ?: Saved(0, "")).copy(photoFailed = photoFailed, account = if (snapshot == null) account else null)
            // Entries of accounts that sync: what to watch until their app sent the change (D1); never a reason to report the save as failed
            val synced = if (newEntry) rawIds.filter { !source.isLocal((if (recreate) target?.account else account) ?: AccountKey("", "")) } else snapshot.entries.filter { !it.local && it.rawContactId in writable }.map { it.rawContactId }
            val upload = try {
                if (synced.isEmpty() || saved.lookupKey.isEmpty()) null
                else Upload(synced, dataRows(synced), UploadCheck.content(load(saved.lookupKey, saved.contactId)?.values.orEmpty(), synced))
            } catch (_: Exception) {
                null
            }
            saved.copy(upload = upload)
        } catch (_: Exception) {
            // OperationApplicationException, RemoteException, SecurityException: the batch didn't happen, nothing was saved
            null
        }
    }

    /**
     * What deleting the contacts [ids] takes away (SPEC §6.8): the entries in writable accounts.
     * Entries of read-only accounts stay; a contact only in read-only accounts isn't deleted at all.
     */
    suspend fun deletion(ids: Collection<Long>): Deletion = withContext(Dispatchers.IO) {
        val adapters = source.contactsAdapters()
        val writable = HashMap<Long, MutableList<Long>>()
        val all = HashSet<Long>()
        if (ids.isNotEmpty()) {
            resolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.CONTACT_ID, ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME),
                "${ContactsContract.RawContacts.CONTACT_ID} IN (${ids.joinToString(",")}) AND ${ContactsContract.RawContacts.DELETED} = 0", null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val contact = cursor.getLong(1)
                    all += contact
                    val account = AccountKey(cursor.getString(2).orEmpty(), cursor.getString(3).orEmpty())
                    if (source.isLocal(account) || adapters[account.type] == true) writable.getOrPut(contact) { ArrayList() } += cursor.getLong(0)
                }
            }
        }
        Deletion(writable.keys.toSet(), writable.values.flatten(), (all - writable.keys).size)
    }

    /**
     * Copies the contacts [ids] to the account [target] (SPEC §8.4, logic agreed with the owner,
     * 17 Nov 2026): for each, the first entry that is local or writable and not in [target] gets a
     * new entry in [target] with every data row (group memberships aside, which belong to the old
     * account) and its star; the photo is written anew in its full size. The new entry is joined
     * with every other entry of the contact, the old one included, so the contact stays one. The
     * old entries are deleted later, when Undo is no longer offered. [onProgress] gets the count done.
     */
    suspend fun move(ids: List<Long>, target: AccountKey, onProgress: (Int) -> Unit = {}): Moved = withContext(Dispatchers.IO) {
        val adapters = source.contactsAdapters()
        val old = ArrayList<Long>()
        val new = ArrayList<Long>()
        var failed = 0
        ids.forEachIndexed { index, contactId ->
            try {
                val entries = ArrayList<Triple<Long, AccountKey, Int>>()
                resolver.query(
                    ContactsContract.RawContacts.CONTENT_URI,
                    arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.STARRED),
                    "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0", arrayOf(contactId.toString()), ContactsContract.RawContacts._ID,
                )?.use { cursor -> while (cursor.moveToNext()) entries += Triple(cursor.getLong(0), AccountKey(cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty()), cursor.getInt(3)) }
                val from = entries.firstOrNull { (_, account, _) -> account != target && (source.isLocal(account) || adapters[account.type] == true) }
                if (from == null) {
                    failed++
                    return@forEachIndexed
                }
                val copy = copyEntry(from.first, target, from.third)
                // Counted as moved before joining: Undo has to know the copy even if a join below fails
                old += from.first
                new += copy
                for ((raw, _, _) in entries) keepTogether(copy, raw)
            } catch (_: Exception) {
                failed++
            }
            onProgress(index + 1)
        }
        Moved(old, new, failed)
    }

    /** A new entry in [target] with every data row of [rawContactId] but group memberships, and its photo; the new entry's id. */
    private fun copyEntry(rawContactId: Long, target: AccountKey, starred: Int): Long {
        val operations = ArrayList<ContentProviderOperation>()
        operations += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, target.type.ifEmpty { null })
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, target.name.ifEmpty { null })
            .withValue(ContactsContract.RawContacts.STARRED, starred)
            .build()
        val columns = arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.IS_PRIMARY, ContactsContract.Data.IS_SUPER_PRIMARY) + (1..15).map { "data$it" }
        var thumbnail: ByteArray? = null
        resolver.query(ContactsContract.Data.CONTENT_URI, columns, "${ContactsContract.Data.RAW_CONTACT_ID} = ?", arrayOf(rawContactId.toString()), ContactsContract.Data._ID)?.use { cursor ->
            while (cursor.moveToNext()) {
                val mime = cursor.getString(0)
                if (mime == GroupMembership.CONTENT_ITEM_TYPE) continue
                // The photo goes through the display photo below; its small copy only when there is no large one
                if (mime == Photo.CONTENT_ITEM_TYPE) {
                    thumbnail = cursor.getBlob(cursor.getColumnIndexOrThrow(Photo.PHOTO))
                    continue
                }
                val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, mime)
                    .withValue(ContactsContract.Data.IS_PRIMARY, cursor.getInt(1))
                    .withValue(ContactsContract.Data.IS_SUPER_PRIMARY, cursor.getInt(2))
                for (i in 3 until columns.size) {
                    when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> Unit
                        Cursor.FIELD_TYPE_BLOB -> builder.withValue(columns[i], cursor.getBlob(i))
                        Cursor.FIELD_TYPE_INTEGER -> builder.withValue(columns[i], cursor.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> builder.withValue(columns[i], cursor.getDouble(i))
                        else -> builder.withValue(columns[i], cursor.getString(i))
                    }
                }
                operations += builder.build()
            }
        }
        val results = resolver.applyBatch(ContactsContract.AUTHORITY, operations)
        val copy = ContentUris.parseId(results[0].uri!!)
        val large = try {
            resolver.openAssetFileDescriptor(displayPhoto(rawContactId), "r")?.use { it.createInputStream().use { input -> input.readBytes() } }
        } catch (_: Exception) {
            null
        }
        // The copy exists from here on: a photo the storage refuses must not count the move as failed, or the copy would stay without Undo knowing it
        try {
            when {
                large != null -> resolver.openAssetFileDescriptor(displayPhoto(copy), "rw")?.use { it.createOutputStream().use { output -> output.write(large) } }
                thumbnail != null -> resolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, copy)
                    put(ContactsContract.Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                    put(Photo.PHOTO, thumbnail)
                })
            }
        } catch (_: Exception) {
            // Moved without its photo; the old entry, with the photo, stays until Undo is gone
        }
        return copy
    }

    private fun displayPhoto(rawContactId: Long): Uri =
        Uri.withAppendedPath(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId), ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY)

    /** Tells the storage two entries are one person (SPEC §8.4): it joins them and keeps them joined. */
    private fun keepTogether(a: Long, b: Long) {
        if (a == b) return
        resolver.update(ContactsContract.AggregationExceptions.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
            put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, a)
            put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, b)
        }, null, null)
    }

    /** The contacts with an entry among local contacts (SPEC §8.4: "Move to an account" for all of them). */
    suspend fun localContacts(): List<Long> = withContext(Dispatchers.IO) {
        val result = LinkedHashSet<Long>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID, ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME),
            "${ContactsContract.RawContacts.DELETED} = 0", null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) if (source.isLocal(AccountKey(cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty()))) result += cursor.getLong(0)
        }
        result.toList()
    }

    /** Deletes the entries [rawContactIds] the way any app does, so the accounts' apps send the deletion to their servers. */
    suspend fun delete(rawContactIds: List<Long>) = withContext(Dispatchers.IO) {
        // The storage refuses a batch of more than 500 operations between yield points (an import's Undo of 2 400 did nothing,
        // the owner's test of 18 Nov 2026): batches of DELETE_BATCH, each delete a yield point
        for (chunk in rawContactIds.chunked(DELETE_BATCH)) {
            val operations = ArrayList(chunk.map { ContentProviderOperation.newDelete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, it)).withYieldAllowed(true).build() })
            resolver.applyBatch(ContactsContract.AUTHORITY, operations)
        }
    }

    /**
     * A duplicate group for the review (SPEC §9.4): every entry and every value of [ids] together
     * (equal values once), and each contact's own reading for its name and photo.
     */
    suspend fun group(ids: List<Long>): MergeSource? = withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, ids.firstOrNull() ?: return@withContext null)
        val all = read(uri, "", ids.drop(1)) ?: return@withContext null
        val each = ids.mapNotNull { id -> read(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id), "") }
        MergeSource(all, each)
    }

    /** The exception types the storage keeps now for [pairs] of entries; a pair without one is automatic. */
    private fun exceptions(pairs: List<Pair<Long, Long>>): Map<Pair<Long, Long>, Int> {
        val ids = pairs.flatMap { listOf(it.first, it.second) }.distinct()
        val found = HashMap<Pair<Long, Long>, Int>()
        if (ids.isNotEmpty()) {
            resolver.query(
                ContactsContract.AggregationExceptions.CONTENT_URI,
                arrayOf(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, ContactsContract.AggregationExceptions.TYPE),
                // Either end: the storage doesn't promise which entry it keeps first
                "${ContactsContract.AggregationExceptions.RAW_CONTACT_ID1} IN (${ids.joinToString(",")}) OR ${ContactsContract.AggregationExceptions.RAW_CONTACT_ID2} IN (${ids.joinToString(",")})", null, null,
            )?.use { cursor -> while (cursor.moveToNext()) found[minOf(cursor.getLong(0), cursor.getLong(1)) to maxOf(cursor.getLong(0), cursor.getLong(1))] = cursor.getInt(2) }
        }
        return pairs.associateWith { (a, b) -> found[minOf(a, b) to maxOf(a, b)] ?: ContactsContract.AggregationExceptions.TYPE_AUTOMATIC }
    }

    private fun setException(a: Long, b: Long, type: Int) {
        if (a == b) return
        resolver.update(ContactsContract.AggregationExceptions.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.AggregationExceptions.TYPE, type)
            put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, a)
            put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, b)
        }, null, null)
    }

    /** The entries of each of [ids]. */
    private fun entriesOf(ids: Collection<Long>): Map<Long, List<Long>> {
        val result = LinkedHashMap<Long, MutableList<Long>>()
        if (ids.isEmpty()) return result
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.CONTACT_ID),
            "${ContactsContract.RawContacts.CONTACT_ID} IN (${ids.joinToString(",")}) AND ${ContactsContract.RawContacts.DELETED} = 0", null, null,
        )?.use { cursor -> while (cursor.moveToNext()) result.getOrPut(cursor.getLong(1)) { ArrayList() } += cursor.getLong(0) }
        return result
    }

    /**
     * Sets [type] for the entry pairs between different contacts of [groups] (each group joined or
     * kept apart as a whole) and returns what was there before, for Undo.
     */
    private fun across(groups: List<List<Long>>, type: Int): Map<Pair<Long, Long>, Int> {
        val pairs = ArrayList<Pair<Long, Long>>()
        for (group in groups) {
            val entries = entriesOf(group)
            val ids = group.filter { it in entries }
            for (i in ids.indices) for (j in i + 1 until ids.size) for (a in entries.getValue(ids[i])) for (b in entries.getValue(ids[j])) pairs += a to b
        }
        val before = exceptions(pairs)
        for ((a, b) in pairs) setException(a, b, type)
        return before
    }

    /** "Only join" (SPEC §9.4, §9.7): every group becomes one contact; entries stay in their accounts. The exceptions before, for Undo. */
    suspend fun join(groups: List<List<Long>>): Map<Pair<Long, Long>, Int> = withContext(Dispatchers.IO) { across(groups, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER) }

    /** "Not the same person" (SPEC §9.4, the owner's decision of 17 Nov 2026: kept in the storage): the contacts kept apart. The exceptions before, for Undo. */
    suspend fun keepApart(ids: List<Long>): Map<Pair<Long, Long>, Int> = withContext(Dispatchers.IO) { across(listOf(ids), ContactsContract.AggregationExceptions.TYPE_KEEP_SEPARATE) }

    /** Undo of a join or of "not the same person": the exceptions as they were. */
    suspend fun restore(before: Map<Pair<Long, Long>, Int>) = withContext(Dispatchers.IO) {
        for ((pair, type) in before) setException(pair.first, pair.second, type)
    }

    /** Separate (SPEC §6.10): every entry of the contact becomes a contact of its own, kept apart from the others. */
    suspend fun separate(contactId: Long) = withContext(Dispatchers.IO) {
        val entries = entriesOf(listOf(contactId))[contactId].orEmpty()
        for (i in entries.indices) for (j in i + 1 until entries.size) setException(entries[i], entries[j], ContactsContract.AggregationExceptions.TYPE_KEEP_SEPARATE)
    }

    /**
     * "Merge into one contact" (the owner's decision of 17 Nov 2026): the entry [target] keeps the
     * values [kept] (checked in the review) and loses those of its own not kept; it takes [name]
     * and the photo of the contact [photoFrom]; every other writable entry of the group is deleted,
     * and the read-only ones are joined with it. Asked for in a dialog first, without Undo.
     * Returns the merged contact, or null when the storage refused.
     */
    suspend fun mergePermanently(source: MergeSource, target: Long, kept: Set<Long>, name: FormValue?, photoFrom: Long?): Saved? = withContext(Dispatchers.IO) {
        try {
            val operations = ArrayList<ContentProviderOperation>()
            for (value in source.all.values) {
                if (value.kind == EditKind.NAME) continue
                val inTarget = value.origins.filter { it.rawContactId == target }
                if (value.key in kept) {
                    if (inTarget.isEmpty()) operations += insert(value.copy(origins = emptyList()), target, false)
                } else {
                    inTarget.forEach { operations += ContentProviderOperation.newDelete(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, it.dataId)).build() }
                }
            }
            if (name != null) {
                val targetContact = source.all.entries.firstOrNull { it.rawContactId == target }?.contactId
                val targetName = source.each.firstOrNull { it.contactId == targetContact }?.values?.firstOrNull { it.kind == EditKind.NAME }?.origins?.firstOrNull { it.rawContactId == target }
                operations += if (targetName != null) update(targetName.dataId, name) else insert(name.copy(origins = emptyList()), target, false)
            }
            val others = source.all.entries.filter { it.rawContactId != target }
            for (entry in others.filter { !it.readOnly }) {
                // Rows the review doesn't show (other apps' kinds, as a messenger's) go with the entry into the target, as a move copies them; group memberships aside, as in a move
                operations += unshownRows(entry.rawContactId, target)
                operations += ContentProviderOperation.newDelete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, entry.rawContactId)).build()
            }
            // The photo chosen, read before its entry goes
            val targetContact = source.all.entries.first { it.rawContactId == target }.contactId
            val photo = if (photoFrom != null && photoFrom != targetContact) {
                try {
                    ContactsContract.Contacts.openContactPhotoInputStream(resolver, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, photoFrom), true)?.use { it.readBytes() }
                } catch (_: Exception) {
                    null
                }
            } else null
            resolver.applyBatch(ContactsContract.AUTHORITY, operations)
            photo?.let { writePhoto(PhotoChange.Set(it), listOf(target)) }
            for (entry in others.filter { it.readOnly }) keepTogether(target, entry.rawContactId)
            val found = ContactsContract.RawContacts.getContactLookupUri(resolver, ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, target))?.let(::contact)
            found ?: Saved(targetContact, "")
        } catch (_: Exception) {
            null
        }
    }

    /** Inserts into [target] of the rows of [rawContactId] whose kind the editor and the review don't know (not a name, photo or group membership either). */
    private fun unshownRows(rawContactId: Long, target: Long): List<ContentProviderOperation> {
        val known = Columns.mime.values.toSet() + Photo.CONTENT_ITEM_TYPE + GroupMembership.CONTENT_ITEM_TYPE
        val columns = arrayOf(ContactsContract.Data.MIMETYPE) + (1..15).map { "data$it" }
        val operations = ArrayList<ContentProviderOperation>()
        resolver.query(ContactsContract.Data.CONTENT_URI, columns, "${ContactsContract.Data.RAW_CONTACT_ID} = ?", arrayOf(rawContactId.toString()), ContactsContract.Data._ID)?.use { cursor ->
            while (cursor.moveToNext()) {
                val mime = cursor.getString(0)
                if (mime in known) continue
                val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, target)
                    .withValue(ContactsContract.Data.MIMETYPE, mime)
                for (i in 1 until columns.size) {
                    when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> Unit
                        Cursor.FIELD_TYPE_BLOB -> builder.withValue(columns[i], cursor.getBlob(i))
                        Cursor.FIELD_TYPE_INTEGER -> builder.withValue(columns[i], cursor.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> builder.withValue(columns[i], cursor.getDouble(i))
                        else -> builder.withValue(columns[i], cursor.getString(i))
                    }
                }
                operations += builder.build()
            }
        }
        return operations
    }

    /** The entries [ids] the storage still has, with their dirty and deleted flags (the upload check). */
    suspend fun entryStates(ids: Collection<Long>): Map<Long, EntryState> = withContext(Dispatchers.IO) {
        val result = HashMap<Long, EntryState>()
        if (ids.isEmpty()) return@withContext result
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.DIRTY, ContactsContract.RawContacts.DELETED),
            "${ContactsContract.RawContacts._ID} IN (${ids.joinToString(",")})", null, null,
        )?.use { cursor -> while (cursor.moveToNext()) result[cursor.getLong(0)] = EntryState(cursor.getInt(1) != 0, cursor.getInt(2) != 0) }
        result
    }

    /** The ids of the data rows of the entries [ids]. */
    fun dataRows(ids: Collection<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val rows = HashSet<Long>()
        resolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID), "${ContactsContract.Data.RAW_CONTACT_ID} IN (${ids.joinToString(",")})", null, null)?.use { cursor ->
            while (cursor.moveToNext()) rows += cursor.getLong(0)
        }
        return rows
    }

    /** Of the entries [ids], those in accounts that sync (not on the phone only): a delete of them waits for their app. */
    suspend fun syncedEntries(ids: Collection<Long>): List<Long> = withContext(Dispatchers.IO) {
        val result = ArrayList<Long>()
        if (ids.isEmpty()) return@withContext result
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME),
            "${ContactsContract.RawContacts._ID} IN (${ids.joinToString(",")})", null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) if (!source.isLocal(AccountKey(cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty()))) result += cursor.getLong(0)
        }
        result
    }

    /** Of [dataIds], those no longer in the storage. */
    private fun missingRows(dataIds: List<Long>): Set<Long> {
        if (dataIds.isEmpty()) return emptySet()
        val present = HashSet<Long>()
        resolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID), "${ContactsContract.Data._ID} IN (${dataIds.joinToString(",")})", null, null)?.use { cursor ->
            while (cursor.moveToNext()) present += cursor.getLong(0)
        }
        return dataIds.toSet() - present
    }

    /** The contact the entry [rawContactId] belongs to now. */
    suspend fun contactOfEntry(rawContactId: Long): Saved? = withContext(Dispatchers.IO) {
        try {
            ContactsContract.RawContacts.getContactLookupUri(resolver, ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId))?.let(::contact)
        } catch (_: Exception) {
            null
        }
    }

    private fun contact(lookupUri: Uri): Saved? = resolver.query(lookupUri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) Saved(cursor.getLong(0), cursor.getString(1).orEmpty()) else null
    }

    /** The photo of every writable entry (SPEC §7.3): set through the entry's display photo, or removed. */
    private fun writePhoto(photo: PhotoChange, entries: List<Long>) {
        when (photo) {
            PhotoChange.None -> Unit
            PhotoChange.Remove -> for (raw in entries) {
                resolver.delete(ContactsContract.Data.CONTENT_URI, "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?", arrayOf(raw.toString(), Photo.CONTENT_ITEM_TYPE))
            }
            is PhotoChange.Set -> for (raw in entries) {
                val uri = Uri.withAppendedPath(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, raw), ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
                resolver.openAssetFileDescriptor(uri, "rw")?.use { descriptor -> descriptor.createOutputStream().use { it.write(photo.jpeg) } }
            }
        }
    }

    /** The columns of [value]; an [update] leaves alone what the form doesn't hold (a messaging address's type), so a synced value isn't changed behind the user's back. */
    private fun values(value: FormValue, update: Boolean = false): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for (column in Columns.partsOf(value.kind)) result[column] = value.part(column).trim().ifEmpty { null }
        when (value.kind) {
            // The storage works the display name and the formatted address out of the parts again
            EditKind.NAME -> result["data1"] = null
            EditKind.ADDRESS -> result["data1"] = null
            EditKind.IM -> {
                if (!update) result["data2"] = Columns.IM_TYPE_OTHER
                result["data5"] = value.protocol?.toString()
                result["data6"] = if (value.protocol == -1) value.label else null
            }
            else -> Unit
        }
        if (value.kind in Columns.typed) {
            result["data2"] = value.type
            result["data3"] = if (value.type == 0) value.label else null
        }
        if (value.kind == EditKind.PHONE || value.kind == EditKind.EMAIL) {
            result[ContactsContract.Data.IS_PRIMARY] = if (value.isDefault) 1 else 0
            result[ContactsContract.Data.IS_SUPER_PRIMARY] = if (value.isDefault) 1 else 0
        }
        return result
    }

    private fun insert(value: FormValue, rawContactId: Long?, newEntry: Boolean, entryAt: Int = 0): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValue(ContactsContract.Data.MIMETYPE, Columns.mime.getValue(value.kind))
        // The back reference points at the batch's operation [entryAt] (the first, for the editor), the new entry only when there is one; an edit always names its entry
        if (newEntry) builder.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, entryAt)
        else builder.withValue(ContactsContract.Data.RAW_CONTACT_ID, requireNotNull(rawContactId) { "A new value of an edited contact needs its entry" })
        for ((column, v) in values(value)) builder.withValue(column, v)
        return builder.build()
    }

    private fun update(dataId: Long, value: FormValue): ContentProviderOperation {
        val builder = ContentProviderOperation.newUpdate(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId))
        for ((column, v) in values(value, update = true)) builder.withValue(column, v)
        return builder.build()
    }
}
