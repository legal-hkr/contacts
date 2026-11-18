package sh.hnet.contacts.model

import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.text.Collator
import java.util.Locale

/**
 * A number's country in the editor (the owner's decisions of 18 Nov 2026): chosen from a list in the phone field, put at the number's start as its calling code, and the number formatted as that country writes it – mobile and landline apart where they differ – while it is typed. A number without a country (112, an extension, a short code) stays exactly as typed; a stored number starting with "+" shows its country at once. Countries, codes and formats are libphonenumber's.
 */
object PhoneFormat {
    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    /** A country of the list: its region code ("PL"), calling code (48), name in the app's language and flag. */
    data class Country(val region: String, val code: Int, val name: String, val flag: String)

    /** Every country with a calling code, by name in [locale]'s order; worked out once per language (245 names and a sort, read from the library's metadata). */
    fun countries(locale: Locale): List<Country> = synchronized(listed) {
        listed.getOrPut(locale) {
            val collator = Collator.getInstance(locale)
            util.supportedRegions.map { country(it, locale) }.sortedWith(compareBy(collator) { it.name })
        }
    }

    private val listed = HashMap<Locale, List<Country>>()

    fun country(region: String, locale: Locale): Country =
        Country(region, util.getCountryCodeForRegion(region), Locale.Builder().setRegion(region).build().getDisplayCountry(locale).ifEmpty { region }, flag(region))

    /** The flag as the system draws it: the two regional indicator symbols of the region code. */
    fun flag(region: String): String = region.uppercase(Locale.ROOT).map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

    /**
     * The country of a number with a calling code ("+48 …"), or null without one. Where several countries share the code (+1, +7, +44), the number decides when it is whole; else [hint] – the country the user chose – when it has that code, else the code's main country.
     */
    fun regionOf(text: String, hint: String? = null): String? {
        val (code, _) = callingCode(text) ?: return null
        // Non-geographic codes (+800, +882) belong to no country: no flag
        val regions = util.getRegionCodesForCountryCode(code).filter { it != NON_GEOGRAPHIC }
        if (regions.isEmpty()) return null
        val whole = runCatching { util.parse(text, "ZZ") }.getOrNull()?.takeIf { util.isValidNumber(it) }?.let { util.getRegionCodeForNumber(it) }
        return whole?.takeIf { it in regions } ?: hint?.takeIf { it in regions } ?: util.getRegionCodeForCountryCode(code)
    }

    /** Whether the number starts with a calling code, a country's or not (+800): "No country code" can take it off. */
    fun hasCode(text: String): Boolean = callingCode(head(text)) != null

    /**
     * [text] with [region]'s calling code at its start, formatted: a code already there is replaced, and a national number loses its trunk prefix where the country has one (UK 07… → +44 7…, Germany 0151… → +49 151…), as does "(0)" after a code (+44 (0) 20…). Only the number itself changes: what follows it – an extension, pauses, a second number after "/", letters – stays as it was (the code review of 18 Nov 2026: it was joined into the number).
     */
    fun withCountry(text: String, region: String): String {
        val head = head(text)
        val number = head.replace(TRUNK_AFTER_CODE, "")
        val national = callingCode(number)?.second ?: util.getNddPrefixForRegion(region, true)?.takeIf { it.isNotEmpty() }?.let { prefix ->
            digits(number).let { if (it.startsWith(prefix)) it.removePrefix(prefix) else it }
        } ?: digits(number)
        return format("+${util.getCountryCodeForRegion(region)}$national", region) + tail(text, head)
    }

    /** [text] without its calling code: the national form where the number is whole ("+49 151 …" → "0151 …"), else the digits after the code; what follows the number stays as it was. */
    fun withoutCountry(text: String): String {
        val head = head(text)
        val (_, rest) = callingCode(head) ?: return text
        val number = runCatching { util.parse(head, "ZZ") }.getOrNull()?.takeIf { util.isValidNumber(it) }
        return (number?.let { util.format(it, PhoneNumberUtil.PhoneNumberFormat.NATIONAL) } ?: rest) + tail(text, head)
    }

    /** The number itself: [text] up to the first character that can't be part of one (a letter, a pause, "/", "#", "*"). */
    private fun head(text: String): String = text.takeWhile { it in ALLOWED }

    /** What follows the number, with one space before it where it began with a space or a letter (" ext. 12", " FLOWERS"), glued on otherwise (",,12"). */
    private fun tail(text: String, head: String): String {
        val rest = text.substring(head.length)
        if (rest.isEmpty()) return ""
        return if (head.endsWith(' ') || rest.first().isLetter()) " " + rest.trimStart() else rest
    }

    /**
     * The number as it is typed (SPEC §7.8 changed by the owner, 18 Nov 2026), with the cursor kept after the same digit: only a number with a calling code is formatted, and only one of digits, spaces and the usual separators – letters, pauses or an extension leave it as typed. [previous] is the text before the edit: a deleted space or dash takes the digit before it along, or it would come straight back.
     *
     * @return The text and the cursor
     */
    fun typed(previous: String, text: String, cursor: Int, hint: String? = null): Pair<String, Int> {
        if (!text.trimStart().startsWith("+") || text.any { it !in ALLOWED }) return text to cursor
        var edited = text
        var at = cursor
        // One separator deleted just before the cursor (Backspace on a space) – not a paste, a deleted selection or one of two spaces, which leave the digits as they were (the code review of 18 Nov 2026: a digit went then)
        val separatorDeleted = previous.length - text.length == 1 && cursor in previous.indices && previous.removeRange(cursor, cursor + 1) == text &&
            !previous[cursor].isDigit() && previous[cursor] != '+' && previous.getOrNull(cursor - 1)?.isDigit() == true
        if (separatorDeleted) {
            // The digit before the cursor goes with it, or the separator would come straight back
            val before = edited.substring(0, at).indexOfLast { it.isDigit() }
            if (before >= 0) {
                edited = edited.removeRange(before, before + 1)
                at = before
            }
        }
        val digitsBefore = edited.substring(0, at.coerceIn(0, edited.length)).count { it.isDigit() }
        val formatted = format("+" + digits(edited), regionOf(edited, hint) ?: "ZZ")
        // The cursor after the same number of digits, or right after "+" when none precede it
        var seen = 0
        var position = if (digitsBefore == 0) formatted.indexOf('+') + 1 else formatted.length
        if (digitsBefore > 0) {
            for ((index, char) in formatted.withIndex()) {
                if (char.isDigit() && ++seen == digitsBefore) {
                    position = index + 1
                    break
                }
            }
        }
        return formatted to position
    }

    /** Formats "+" and digits as the country writes them, as far as they go. */
    private fun format(international: String, region: String): String {
        val formatter = util.getAsYouTypeFormatter(region)
        var result = international
        for (char in international) result = formatter.inputDigit(char)
        return result
    }

    /** The calling code at the number's start ("+48…", "0048…") and the digits after it; null without one. Calling codes are prefix-free, so the first one that exists is the one. */
    private fun callingCode(text: String): Pair<Int, String>? {
        val trimmed = text.trimStart()
        val all = when {
            trimmed.startsWith("+") -> digits(trimmed)
            trimmed.startsWith("00") -> digits(trimmed).removePrefix("00")
            else -> return null
        }
        for (length in 1..minOf(3, all.length)) {
            val code = all.substring(0, length).toInt()
            if (util.getRegionCodesForCountryCode(code).isNotEmpty()) return code to all.substring(length)
        }
        return null
    }

    private fun digits(text: String): String = text.filter { it.isDigit() }

    private const val NON_GEOGRAPHIC = "001"

    /** What a formatted number may hold besides digits; "/" is not one of them: it parts two numbers or a number and its extension. */
    private const val ALLOWED = "0123456789+ -(). "

    /** "(0)" written after a calling code for the trunk prefix, which isn't dialed with the code. */
    private val TRUNK_AFTER_CODE = Regex("""(?<=\d)\s*\(0\)""")
}
