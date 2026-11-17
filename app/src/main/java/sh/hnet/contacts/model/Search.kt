package sh.hnet.contacts.model

import java.text.Normalizer
import java.util.Locale

/** What a search can match in a contact (SPEC §5.2), in the order a result's second line prefers them. */
enum class MatchKind { NAME, NICKNAME, PHONETIC, PHONE, EMAIL, COMPANY, NOTE }

/**
 * One searchable value of a contact.
 *
 * @param type For a phone number: the storage's type code and [label], which the result's line names
 */
data class SearchField(val kind: MatchKind, val value: String, val type: Int = 0, val label: String? = null)

/**
 * A contact as search sees it: its list row, every searchable value, and whether it is in a shown
 * account (hidden ones are searched only on "Search all", SPEC §5.4).
 */
data class SearchContact(val row: ContactRow, val shown: Boolean, val fields: List<SearchField>)

/**
 * A found contact (SPEC §5.3). [field] is null when the match is in the name shown, which is then
 * highlighted at [range]; otherwise [field] is the value that matched, as [text] (a note cut to the
 * words around the match), with the match at [range].
 */
data class SearchResult(val contact: ContactRow, val inNames: Boolean, val field: SearchField?, val text: String, val range: IntRange?)

object Search {
    /** Characters a number query may have besides digits (SPEC §5.2). */
    private const val NUMBER_SIGNS = " +-()."

    /** Words of a note shown before and after the match. */
    private const val NOTE_WORDS = 3

    /** A character without its marks, lower case: "Ł" is "l", "ś" is "s". One character in, one out, so places stay. */
    private fun fold(c: Char): Char {
        val lower = c.lowercaseChar()
        if (lower == 'ł') return 'l'
        return Normalizer.normalize(lower.toString(), Normalizer.Form.NFD).firstOrNull() ?: lower
    }

    private fun fold(text: String): String = buildString(text.length) { text.forEach { append(fold(it)) } }

    /** A query with a diacritic is compared exactly, case aside; one without matches with or without them (SPEC §5.2). */
    private fun hasMarks(query: String): Boolean = fold(query) != query.lowercase(Locale.ROOT)

    fun isNumberQuery(query: String): Boolean = query.count { it.isDigit() } >= 3 && query.all { it.isDigit() || it in NUMBER_SIGNS }

    /** Where [query] starts in [text] at the start of a word ([anywhere] for e-mails); null when it doesn't. */
    fun findText(text: String, query: String, anywhere: Boolean = false): IntRange? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val exact = hasMarks(q)
        val haystack = if (exact) text.lowercase(Locale.ROOT) else fold(text)
        val needle = if (exact) q.lowercase(Locale.ROOT) else fold(q)
        // lowercase() may change a text's length (İ); the places then can't be trusted to highlight
        if (haystack.length != text.length) return null
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return null
            if (anywhere || at == 0 || !haystack[at - 1].isLetterOrDigit()) return at until at + needle.length
            from = at + 1
        }
    }

    /**
     * Whether a number query matches [number] (SPEC §5.2): the query's digits anywhere in the
     * number's, also with the region's country code [countryCode] taken off the start of either.
     * Returns the matched digits of [number] as a range over its characters, for the highlight.
     */
    fun findNumber(number: String, query: String, countryCode: String?): IntRange? {
        val queryDigits = query.filter { it.isDigit() }
        val numberDigits = number.filter { it.isDigit() }
        val queries = listOfNotNull(queryDigits, countryCode?.let { cc -> queryDigits.removePrefix(cc).takeIf { it != queryDigits && it.length >= 3 } })
        for (q in queries) {
            val at = numberDigits.indexOf(q)
            if (at >= 0) return digitRange(number, at, q.length)
        }
        return null
    }

    /** The characters of [text] holding its digits from [first] for [count] digits. */
    private fun digitRange(text: String, first: Int, count: Int): IntRange? {
        var digit = 0
        var start = -1
        for ((i, c) in text.withIndex()) {
            if (!c.isDigit()) continue
            if (digit == first) start = i
            if (digit == first + count - 1) return start until i + 1
            digit++
        }
        return null
    }

    /**
     * The contacts matching [query] (SPEC §5.2, §5.3), in the order given (the list's): first those
     * matched in a name, then the others. [all] searches hidden accounts too.
     */
    fun find(contacts: List<SearchContact>, query: String, countryCode: String?, all: Boolean = false): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val number = isNumberQuery(q)
        val names = ArrayList<SearchResult>()
        val others = ArrayList<SearchResult>()
        for (contact in contacts) {
            if (!contact.shown && !all) continue
            val result = match(contact, q, number, countryCode) ?: continue
            if (result.inNames) names += result else others += result
        }
        return names + others
    }

    private fun match(contact: SearchContact, query: String, number: Boolean, countryCode: String?): SearchResult? {
        if (number) {
            for (field in contact.fields) {
                if (field.kind != MatchKind.PHONE) continue
                findNumber(field.value, query, countryCode)?.let { return SearchResult(contact.row, false, field, field.value, it) }
            }
            return null
        }
        // The name shown first: then there is no second line
        findText(contact.row.name, query)?.let { return SearchResult(contact.row, true, null, contact.row.name, it) }
        val ordered = contact.fields.sortedBy { it.kind.ordinal }
        for (field in ordered) {
            if (field.kind == MatchKind.PHONE) continue
            val range = findText(field.value, query, anywhere = field.kind == MatchKind.EMAIL) ?: continue
            val inNames = field.kind == MatchKind.NAME || field.kind == MatchKind.NICKNAME || field.kind == MatchKind.PHONETIC
            // A part of the name not shown as such (the middle name, the other order): the name is the line, without a highlight
            if (field.kind == MatchKind.NAME) return SearchResult(contact.row, true, null, contact.row.name, null)
            if (field.kind == MatchKind.NOTE) {
                val (snippet, at) = snippet(field.value, range)
                return SearchResult(contact.row, inNames, field, snippet, at)
            }
            return SearchResult(contact.row, inNames, field, field.value, range)
        }
        return null
    }

    /** A note on one line: the words around the match, with an ellipsis where cut (SPEC §5.3). */
    fun snippet(note: String, range: IntRange): Pair<String, IntRange> {
        val flat = note.replace('\n', ' ')
        var start = range.first
        repeat(NOTE_WORDS) {
            val previous = flat.lastIndexOf(' ', start - 2)
            start = if (previous < 0) 0 else previous + 1
        }
        var end = range.last + 1
        repeat(NOTE_WORDS) {
            val next = flat.indexOf(' ', end + 1)
            end = if (next < 0) flat.length else next
        }
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < flat.length) "…" else ""
        val shift = prefix.length - start
        return (prefix + flat.substring(start, end) + suffix) to (range.first + shift)..(range.last + shift)
    }
}
