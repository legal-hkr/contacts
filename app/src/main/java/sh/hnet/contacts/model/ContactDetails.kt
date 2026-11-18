package sh.hnet.contacts.model

import java.time.DateTimeException
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeParseException

/** The groups of the contact page, in its order (SPEC §6.4). */
enum class DetailKind { PHONE, EMAIL, ADDRESS, WEBSITE, DATE, RELATION, IM, SIP, NOTE }

/**
 * One value of a contact as the storage keeps it, in the entry ([rawContactId]) it came from.
 *
 * @param type The storage's type code of the kind (0 for a custom [label])
 * @param label The user's own label for a custom type
 * @param protocol For messaging addresses: the storage's protocol code, or -1 with [label] holding the custom protocol
 */
data class Detail(
    val kind: DetailKind,
    val value: String,
    val type: Int,
    val label: String?,
    val isDefault: Boolean,
    val rawContactId: Long,
    val dataId: Long,
    val protocol: Int? = null,
)

/**
 * An entry (raw contact) of a contact with its account (SPEC §6.5).
 *
 * @param readOnly The entry can't be changed: its account's app doesn't send changes to a server
 */
data class Source(val rawContactId: Long, val account: AccountKey, val local: Boolean, val readOnly: Boolean, val app: String?)

/** Everything the contact page shows (SPEC §6.2–§6.6). */
data class ContactDetails(
    val id: Long,
    val lookupKey: String,
    val name: String,
    val nickname: String?,
    val phonetic: String?,
    val company: String?,
    val photo: String?,
    val starred: Boolean,
    val details: Map<DetailKind, List<Detail>>,
    val sources: List<Source>,
    /** The letter avatar's letters, as [ContactRow.initials]. */
    val initials: String? = null,
) {
    /** Only read-only entries: no editing, no deleting (SPEC §6.6). */
    val readOnly: Boolean get() = sources.isNotEmpty() && sources.all { it.readOnly }

    fun of(kind: DetailKind): List<Detail> = details[kind].orEmpty()
}

object Details {
    /**
     * Values present in several entries of a joined contact show once (SPEC §9.5): the copy of the
     * first entry in [sourceOrder] stays, with its type, and it is the default if any copy is.
     * [numberKey] turns a phone number into what makes two numbers equal (digits in international
     * form where the region allows).
     */
    fun distinct(values: List<Detail>, sourceOrder: List<Long>, numberKey: (String) -> String): List<Detail> {
        val order = sourceOrder.withIndex().associate { it.value to it.index }
        val sorted = values.sortedBy { order[it.rawContactId] ?: Int.MAX_VALUE }
        val kept = LinkedHashMap<String, Detail>()
        for (value in sorted) {
            val key = key(value, numberKey)
            val first = kept[key]
            kept[key] = if (first == null) value else if (value.isDefault && !first.isDefault) first.copy(isDefault = true) else first
        }
        return kept.values.toList()
    }

    private fun key(value: Detail, numberKey: (String) -> String): String = when (value.kind) {
        DetailKind.PHONE -> numberKey(value.value)
        DetailKind.EMAIL -> value.value.trim().lowercase()
        DetailKind.RELATION, DetailKind.DATE -> "${value.type}|${value.label.orEmpty()}|${value.value.trim()}"
        else -> value.value.trim()
    }

    /** Only the digits, the way numbers are compared when nothing better is known. */
    fun digits(number: String): String = number.filter { it.isDigit() }
}

/** A date as the storage keeps it: `yyyy-MM-dd`, or `--MM-dd` without a year (SPEC §6.4.1). */
sealed interface ContactDate {
    data class Full(val date: LocalDate) : ContactDate
    data class NoYear(val day: MonthDay) : ContactDate

    companion object {
        /** Null for anything else a sync may have left (the page then shows the stored text). */
        fun parse(text: String): ContactDate? {
            val value = text.trim()
            return try {
                when {
                    value.startsWith("--") -> NoYear(MonthDay.parse(value))
                    value.length >= 10 -> Full(LocalDate.parse(value.take(10)))
                    value.length == 8 && value.all { it.isDigit() } -> Full(LocalDate.of(value.take(4).toInt(), value.substring(4, 6).toInt(), value.substring(6).toInt()))
                    else -> null
                }
            } catch (_: DateTimeParseException) {
                null
            } catch (_: DateTimeException) {
                null
            }
        }
    }
}
