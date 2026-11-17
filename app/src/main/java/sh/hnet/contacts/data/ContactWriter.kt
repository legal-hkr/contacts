package sh.hnet.contacts.data

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.net.Uri
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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.contacts.model.AccountKey
import sh.hnet.contacts.model.EditKind
import sh.hnet.contacts.model.EditorDiff
import sh.hnet.contacts.model.FormValue
import sh.hnet.contacts.model.Origin
import sh.hnet.contacts.model.Write

/** An entry of the contact being edited, with the version it had when the editor read it (SPEC §7.7). */
data class EditEntry(val rawContactId: Long, val account: AccountKey, val local: Boolean, val readOnly: Boolean, val version: Int)

/** A contact as the editor reads it: its entries and every value with the rows it came from. */
data class EditSnapshot(val contactId: Long, val lookupKey: String, val entries: List<EditEntry>, val values: List<FormValue>, val photo: String?) {
    /** New values go here (SPEC §7.6): the first writable entry, in the order the page lists them. */
    val target: EditEntry? get() = entries.firstOrNull { !it.readOnly }
}

/** What the editor does with the photo on Save. */
sealed interface PhotoChange {
    data object None : PhotoChange
    data object Remove : PhotoChange

    /** A cropped square JPEG (SPEC §7.3). */
    class Set(val jpeg: ByteArray) : PhotoChange
}

/** A saved contact, to open its page; [photoFailed] when the contact was saved but its photo couldn't be written. */
data class Saved(val contactId: Long, val lookupKey: String, val photoFailed: Boolean = false)

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
    private val resolver = context.contentResolver
    private val keys = AtomicLong(1)

    /** A key for a new value of the form. */
    fun newKey(): Long = keys.getAndIncrement()

    /** The contact a lookup key points to now, ready for the form; null when it is gone. */
    suspend fun load(lookupKey: String, id: Long): EditSnapshot? = withContext(Dispatchers.IO) {
        val uri = ContactsContract.Contacts.lookupContact(resolver, ContactsContract.Contacts.getLookupUri(id, lookupKey)) ?: return@withContext null
        val contactId = ContentUris.parseId(uri)
        var key = lookupKey
        var photo: String? = null
        var nameSource = 0L
        resolver.query(uri, arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.PHOTO_URI, ContactsContract.Contacts.NAME_RAW_CONTACT_ID, ContactsContract.Contacts.PHOTO_FILE_ID, ContactsContract.Contacts.PHOTO_ID), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            key = cursor.getString(0) ?: lookupKey
            photo = source.photoAddress(cursor.getString(1), cursor.getString(3), cursor.getString(4))
            nameSource = cursor.getLong(2)
        } ?: return@withContext null
        val adapters = source.contactsAdapters()
        val entries = ArrayList<EditEntry>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.VERSION),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0", arrayOf(contactId.toString()), ContactsContract.RawContacts._ID,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val account = AccountKey(cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty())
                val local = source.isLocal(account)
                entries += EditEntry(cursor.getLong(0), account, local, !local && adapters[account.type] != true, cursor.getInt(3))
            }
        }
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
            "${ContactsContract.Data.CONTACT_ID} = ?", arrayOf(contactId.toString()), ContactsContract.Data._ID,
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
        EditSnapshot(contactId, key, entries, values, photo)
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
            (found ?: snapshot?.let { Saved(it.contactId, it.lookupKey) } ?: Saved(0, "")).copy(photoFailed = photoFailed)
        } catch (_: Exception) {
            // OperationApplicationException, RemoteException, SecurityException: the batch didn't happen, nothing was saved
            null
        }
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

    private fun values(value: FormValue): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for (column in Columns.partsOf(value.kind)) result[column] = value.part(column).trim().ifEmpty { null }
        when (value.kind) {
            // The storage works the display name and the formatted address out of the parts again
            EditKind.NAME -> result["data1"] = null
            EditKind.ADDRESS -> result["data1"] = null
            EditKind.IM -> {
                result["data2"] = Columns.IM_TYPE_OTHER
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

    private fun insert(value: FormValue, rawContactId: Long?, newEntry: Boolean): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValue(ContactsContract.Data.MIMETYPE, Columns.mime.getValue(value.kind))
        // The back reference points at the batch's first operation, which is the new entry only when there is one; an edit always names its entry
        if (newEntry) builder.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
        else builder.withValue(ContactsContract.Data.RAW_CONTACT_ID, requireNotNull(rawContactId) { "A new value of an edited contact needs its entry" })
        for ((column, v) in values(value)) builder.withValue(column, v)
        return builder.build()
    }

    private fun update(dataId: Long, value: FormValue): ContentProviderOperation {
        val builder = ContentProviderOperation.newUpdate(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId))
        for ((column, v) in values(value)) builder.withValue(column, v)
        return builder.build()
    }
}
