package sh.hnet.contacts.model

/**
 * The kinds of the editor (SPEC §7.4), each one MIME type of the storage, in the canonical order
 * of the page's groups. [repeatable] kinds take any number of values, the others one.
 */
enum class EditKind(val repeatable: Boolean) {
    NAME(false), PHONE(true), EMAIL(true), ADDRESS(true), COMPANY(false), WEBSITE(true), DATE(true), RELATION(true), NICKNAME(false), IM(true), SIP(true), NOTE(false)
}

/** Where a value is kept: one data row of one entry. */
data class Origin(val rawContactId: Long, val dataId: Long)

/**
 * One value of the form. [parts] holds the storage's columns of the value (`data1`… as the kind
 * uses them); [type] and [label] the type code and the custom label, [protocol] the messaging
 * protocol. A value read from several entries (equal values show once, SPEC §9.5) has several
 * [origins], and a change goes to each of them; a new value has none.
 *
 * @param key Stable within one opening of the editor, so the form can tell its values apart
 * @param readOnly From an entry that can't be changed: shown, locked (SPEC §7.6)
 */
data class FormValue(
    val key: Long,
    val kind: EditKind,
    val parts: Map<String, String> = emptyMap(),
    val type: Int = 0,
    val label: String? = null,
    val protocol: Int? = null,
    val isDefault: Boolean = false,
    val origins: List<Origin> = emptyList(),
    val readOnly: Boolean = false,
) {
    fun part(column: String): String = parts[column].orEmpty()

    /** Nothing typed in any part. */
    val blank: Boolean get() = parts.values.all { it.isBlank() }

    /** What the storage keeps, compared to tell a change: the trimmed parts, the type, the label, the protocol, the default. */
    fun sameContent(other: FormValue): Boolean =
        parts.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() } == other.parts.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() } &&
            type == other.type && label.orEmpty() == other.label.orEmpty() && protocol == other.protocol && isDefault == other.isDefault
}

/** One write to the storage, as the editor works it out (SPEC §7.7); the data layer turns these into provider operations. */
sealed interface Write {
    /** A new data row in an entry ([rawContactId]), or in the new entry when it is null. */
    data class Insert(val rawContactId: Long?, val value: FormValue) : Write

    data class Update(val dataId: Long, val value: FormValue) : Write

    data class Delete(val dataId: Long) : Write
}

object EditorDiff {
    /**
     * The writes that turn [original] into [edited] (SPEC §7.6, §7.7): a value not touched is not
     * written at all, a changed one is written to every row it came from, a removed one is deleted
     * from every row, a new one goes to [target] (the first writable entry; null for a new contact).
     * Read-only values are never written. Blank values count as removed.
     */
    fun writes(original: List<FormValue>, edited: List<FormValue>, target: Long?): List<Write> {
        val before = original.associateBy { it.key }
        val kept = edited.filter { !it.blank }
        val result = ArrayList<Write>()
        for (value in kept) {
            if (value.readOnly) continue
            val old = before[value.key]
            when {
                old == null || old.origins.isEmpty() -> result += Write.Insert(target, value.copy(origins = emptyList()))
                !old.sameContent(value) -> old.origins.forEach { result += Write.Update(it.dataId, value) }
            }
        }
        val keptKeys = kept.map { it.key }.toSet()
        for (old in original) {
            if (old.readOnly || old.key in keptKeys) continue
            old.origins.forEach { result += Write.Delete(it.dataId) }
        }
        return result
    }

    /**
     * Everything of [edited] as new rows, for a contact deleted while it was edited (SPEC §7.7):
     * saving creates it again.
     */
    fun recreate(edited: List<FormValue>): List<Write> = edited.filter { !it.blank }.map { Write.Insert(null, it.copy(origins = emptyList(), readOnly = false)) }

    /** The form holds something to save: a value of any kind, or a photo (SPEC §7.1). */
    fun hasContent(edited: List<FormValue>, photo: Boolean): Boolean = photo || edited.any { !it.blank }
}
