package sh.hnet.contacts.model

import java.text.BreakIterator
import java.text.CollationKey
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * An account the contacts storage knows, as the storage names it: [type] and [name] are empty for
 * entries without an account. [local] is set by the source for those and for the platform's local
 * account (SPEC §8.3); everything else is synced by another app.
 */
data class AccountKey(val type: String, val name: String) {
    /** The account as one string, for the app's own settings: account visibility (SPEC §8.1). */
    val key: String get() = "$type\n$name"

    companion object {
        fun of(key: String): AccountKey? = key.split('\n', limit = 2).takeIf { it.size == 2 }?.let { AccountKey(it[0], it[1]) }
    }
}

/**
 * Settings › List (SPEC §13.2).
 *
 * @param sortByLast Sort by the last name (the storage's alternative name) instead of the first
 * @param lastFirst Show names as "Last, First" (the storage's alternative display name)
 * @param companyLine The company (and title) on a second line of a row
 */
/** @param avatarColors Letter avatars in many colors (Calendar's palette); false keeps the theme's three (the owner, 18 Nov 2026) */
data class ListSettings(val sortByLast: Boolean = false, val lastFirst: Boolean = false, val companyLine: Boolean = false, val avatarColors: Boolean = true)

/**
 * One contact as the list shows it (SPEC §4.5).
 *
 * @param name The name shown, in the order of [ListSettings.lastFirst]
 * @param sortName The name the list is sorted and filed by, in the order of [ListSettings.sortByLast]
 * @param photo The storage's thumbnail address, or null for the letter avatar
 * @param company "company, title", or whichever of the two exists; null when the setting is off or there is none
 * @param initials The letter avatar's letters (the first of the given name and of the family name); null to take the name's first letter
 * @param shortName What the favorites row shows under the avatar (SPEC §4.3): the nickname, else the given name; null for other contacts or when neither exists
 */
data class ContactRow(
    val id: Long,
    val lookupKey: String,
    val name: String,
    val sortName: String,
    val photo: String?,
    val starred: Boolean,
    val company: String? = null,
    val shortName: String? = null,
    val initials: String? = null,
)

/** What the list draws: a letter's header, then its contacts. */
sealed interface ListEntry {
    val key: Any

    data class Header(val letter: String) : ListEntry {
        override val key: Any get() = "header:$letter"
    }

    data class Contact(val contact: ContactRow) : ListEntry {
        override val key: Any get() = contact.id
    }
}

object ContactList {
    /** What names that start with a digit or a sign file under, last in the list (SPEC §4.6). */
    const val OTHER = "#"

    /** Letters a language keeps apart from their base letter in its alphabet; elsewhere they file under the base letter. */
    private val OWN_LETTERS = mapOf("pl" to "ĄĆĘŁŃÓŚŹŻ")

    /**
     * The letter [name] files under in [locale]: its first letter, upper case, with diacritics
     * dropped unless the language has the letter as its own (Polish Ł, Ś, …); [OTHER] when the name
     * doesn't start with a letter.
     */
    fun letter(name: String, locale: Locale): String {
        val first = name.trimStart().firstOrNull() ?: return OTHER
        if (!first.isLetter()) return OTHER
        val upper = first.toString().uppercase(locale)
        if (upper in OWN_LETTERS[locale.language].orEmpty()) return upper
        // Ł and Ø have no decomposition: they are letters of their own to Unicode, not L and O with a mark
        val base = when (upper) {
            "Ł" -> "L"
            "Ø" -> "O"
            "Đ" -> "D"
            else -> Normalizer.normalize(upper, Normalizer.Form.NFD).filter { it.isLetter() }.take(1).ifEmpty { upper }
        }
        return base
    }

    /**
     * The list in [locale]'s order with a header before each letter's contacts; names without a
     * letter come last, under [OTHER]. Order within a letter follows the language's collation.
     */
    fun build(contacts: List<ContactRow>, locale: Locale): List<ListEntry> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        val keyed = contacts.map { Triple(it, letter(it.sortName, locale), collator.getCollationKey(it.sortName.trim())) }
        val sorted = keyed.sortedWith(compareBy<Triple<ContactRow, String, CollationKey>> { it.second == OTHER }.thenBy { it.third }.thenBy { it.first.id })
        val entries = ArrayList<ListEntry>(sorted.size + 40)
        var current: String? = null
        for ((contact, letter, _) in sorted) {
            if (letter != current) {
                entries += ListEntry.Header(letter)
                current = letter
            }
            entries += ListEntry.Contact(contact)
        }
        return entries
    }
}

/** The favorites row (SPEC §4.3): the starred contacts in the list's order. */
fun ContactList.favorites(contacts: List<ContactRow>, locale: Locale): List<ContactRow> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    return contacts.filter { it.starred }.sortedWith(compareBy<ContactRow, String>(collator) { it.sortName.trim() }.thenBy { it.id })
}

object Avatars {
    /** The three theme container pairs a letter avatar takes its fill from (SPEC §4.5). */
    const val TONES = 3

    /** The first character as a reader sees it (a letter with its marks), upper case; [ContactList.OTHER] for a digit or a sign. */
    fun initial(name: String, locale: Locale): String {
        val text = name.trim()
        if (text.isEmpty() || !text[0].isLetter()) return ContactList.OTHER
        val characters = BreakIterator.getCharacterInstance(locale).apply { setText(text) }
        val end = characters.next().takeIf { it != BreakIterator.DONE } ?: text.length
        return text.substring(0, end).uppercase(locale)
    }

    /** The colors of the palette in many colors (Calendar's, without Graphite). */
    const val PALETTE = 11

    /** Which of the [TONES] a contact's avatar takes: by its lookup key, so it stays after a sync and between launches. */
    fun tone(lookupKey: String): Int = Math.floorMod(lookupKey.hashCode(), TONES)

    /** Which of the [PALETTE]'s colors a contact's avatar takes, by its lookup key as [tone]. */
    fun color(lookupKey: String): Int = Math.floorMod(lookupKey.hashCode(), PALETTE)

    /**
     * The letter avatar's letters (the owner, 18 Nov 2026): the first of the given name and the first
     * of the family name, always in that order; one when only one of them begins with a letter; null
     * when neither does (a nickname or a company alone: the name's first letter then).
     */
    fun initials(given: String?, family: String?, locale: Locale): String? {
        val letters = listOfNotNull(given, family).map { initial(it, locale) }.filter { it != ContactList.OTHER }
        return letters.joinToString("").ifEmpty { null }
    }
}

/**
 * Account types of messaging apps whose entries mirror the user's real contacts, mostly with a
 * number only; they start hidden at the first run (SPEC §12.4).
 */
object MessagingAccounts {
    val TYPES = setOf(
        "org.thoughtcrime.securesms",
        "com.whatsapp",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "im.molly.app",
        "chat.simplex.app",
    )
}
