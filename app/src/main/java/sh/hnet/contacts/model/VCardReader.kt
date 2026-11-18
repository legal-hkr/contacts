package sh.hnet.contacts.model

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * A vCard file as read for the import (SPEC §10): its contacts, and the properties left out because
 * the app has nothing to keep them in ([skipped], the most frequent first) with the number of
 * contacts that had any of them.
 */
data class VCardFile(val contacts: List<CardContact>, val skipped: List<String>, val skippedContacts: Int)

/** The bytes aren't a vCard file: no card in them at all. */
class NotVCardException : Exception()

/**
 * vCard 2.1, 3.0 and 4.0 (RFC 2426, RFC 6350, and the 2.1 text of the Internet Mail Consortium),
 * read by the app itself (owner's decision of 17 Nov 2026: our own reader). Pure Kotlin,
 * unit-tested. It reads back everything [VCardWriter] writes, and what other apps write for the
 * same values: Apple's groups with `X-ABLabel`, `X-ABDATE`, `X-ABRELATEDNAMES` and
 * `X-SERVICE-TYPE`, Android's `X-ANDROID-CUSTOM` rows, old phones' `X-JABBER`, `X-SIP` and the
 * like, quoted-printable values in any declared charset, and embedded photos.
 *
 * The values come out in the storage's terms (type codes, `data` columns), as [CardContact]; the
 * photo as it was in the file, to be re-encoded by the caller.
 */
object VCardReader {
    private val LATIN1 = Charsets.ISO_8859_1
    private val CP1250: Charset = Charset.forName("windows-1250")

    /** Properties that say nothing the user would miss: left out without being named. */
    private val QUIET = setOf(
        "BEGIN", "END", "VERSION", "PRODID", "REV", "UID", "LABEL", "SORT-STRING", "KIND", "SOURCE", "NAME", "PROFILE",
        "CLIENTPIDMAP", "X-ABUID", "X-ABSHOWAS", "X-ABLABEL", "X-ABADR", "X-PHONETIC-FIRST-NAME", "X-PHONETIC-MIDDLE-NAME", "X-PHONETIC-LAST-NAME",
    )

    /** Old properties for one messaging protocol each, with the storage's protocol code. */
    private val LEGACY_IM = mapOf(
        "X-AIM" to 0, "X-MSN" to 1, "X-YAHOO" to 2, "X-SKYPE" to 3, "X-SKYPE-USERNAME" to 3, "X-QQ" to 4,
        "X-GOOGLE-TALK" to 5, "X-GTALK" to 5, "X-ICQ" to 6, "X-JABBER" to 7,
    )

    /**
     * Reads [bytes]. The text is taken as UTF-8, or Windows-1250 when it isn't valid UTF-8 (old
     * Polish phones, Outlook; owner's decision of 18 Nov 2026); a value's own `CHARSET` wins.
     *
     * @throws NotVCardException when there is no card in the bytes
     */
    fun read(bytes: ByteArray): VCardFile {
        val (text, fallback) = decode(bytes)
        val cards = ArrayList<List<Property>>()
        var current: MutableList<Property>? = null
        var depth = 0
        var seen = false
        for (line in unfold(text)) {
            if (line.isBlank()) continue
            val property = parse(line) ?: continue
            when {
                property.name == "BEGIN" && property.raw.trim().equals("VCARD", ignoreCase = true) -> {
                    seen = true
                    depth++
                    if (depth == 1) current = ArrayList()
                }
                property.name == "END" && property.raw.trim().equals("VCARD", ignoreCase = true) -> {
                    if (depth == 1) current?.let { cards += it }
                    if (depth > 0) depth--
                    if (depth == 0) current = null
                }
                // A card inside a card (2.1's AGENT) is left out with its property
                depth == 1 -> current?.add(property)
            }
        }
        // A file cut short: what was read of the last card still counts
        if (depth > 0) current?.let { cards += it }
        if (!seen) throw NotVCardException()
        val counts = LinkedHashMap<String, Int>()
        var skippedContacts = 0
        val contacts = cards.mapNotNull { properties ->
            val skipped = LinkedHashSet<String>()
            val contact = card(properties, fallback, skipped)
            if (skipped.isNotEmpty()) {
                skippedContacts++
                skipped.forEach { counts[it] = (counts[it] ?: 0) + 1 }
            }
            contact
        }
        return VCardFile(contacts, counts.entries.sortedByDescending { it.value }.map { it.key }, skippedContacts)
    }

    /** The file as one string of its bytes (Latin-1 keeps each byte as one character) and the charset for values that don't name theirs. */
    private fun decode(bytes: ByteArray): Pair<String, Charset> {
        fun startsWith(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
        return when {
            startsWith(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, LATIN1) to Charsets.UTF_8
            // UTF-16 files are turned into UTF-8 bytes first, so the rest reads them as any other
            startsWith(0xFF, 0xFE) -> String(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE).toByteArray(Charsets.UTF_8), LATIN1) to Charsets.UTF_8
            startsWith(0xFE, 0xFF) -> String(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE).toByteArray(Charsets.UTF_8), LATIN1) to Charsets.UTF_8
            else -> String(bytes, LATIN1) to (if (validUtf8(bytes)) Charsets.UTF_8 else CP1250)
        }
    }

    private fun validUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    /**
     * Logical lines: a line starting with a space or a tab continues the one before (RFC 6350
     * §3.2), and a quoted-printable value ending in "=" goes on in the next line (vCard 2.1).
     */
    private fun unfold(text: String): List<String> {
        val physical = text.split("\r\n", "\n", "\r")
        val lines = ArrayList<String>()
        var i = 0
        while (i < physical.size) {
            val line = StringBuilder(physical[i++])
            while (i < physical.size && physical[i].isNotEmpty() && (physical[i][0] == ' ' || physical[i][0] == '\t')) line.append(physical[i++], 1, physical[i - 1].length)
            if (quotedPrintable(line)) {
                while (line.endsWith("=") && i < physical.size) {
                    line.setLength(line.length - 1)
                    line.append(physical[i++].trimStart())
                }
            }
            lines += line.toString()
        }
        return lines
    }

    private fun quotedPrintable(line: CharSequence): Boolean {
        val head = line.substring(0, colon(line).takeIf { it >= 0 } ?: return false)
        return head.contains("QUOTED-PRINTABLE", ignoreCase = true)
    }

    /** The first colon outside a quoted parameter value: where the value starts. */
    private fun colon(line: CharSequence): Int {
        var quoted = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ':' -> if (!quoted) return i
            }
        }
        return -1
    }

    /** One property: `group.NAME;PARAM=a,b;BARE:value`, the value still in the file's bytes. */
    private class Property(val group: String?, val name: String, val params: Map<String, List<String>>, val raw: String) {
        /** TYPE values in lower case, 2.1's bare ones included. */
        val types: Set<String> get() = params["TYPE"].orEmpty().map { it.lowercase() }.toSet()
        val encoding: String? get() = params["ENCODING"]?.firstOrNull()?.uppercase()
        val pref: Boolean get() = "pref" in types || params.containsKey("PREF")
        fun param(name: String): String? = params[name]?.firstOrNull()
    }

    private fun parse(line: String): Property? {
        val at = colon(line)
        if (at <= 0) return null
        val head = split(line.substring(0, at), ';')
        val qualified = head.first().trim()
        val dot = qualified.lastIndexOf('.')
        val group = if (dot > 0) qualified.substring(0, dot).uppercase() else null
        val name = qualified.substring(dot + 1).uppercase()
        val params = LinkedHashMap<String, MutableList<String>>()
        for (token in head.drop(1)) {
            val equals = token.indexOf('=')
            if (equals < 0) {
                // vCard 2.1: bare parameters are types, encodings, or a VALUE
                val bare = token.trim().uppercase()
                val key = when (bare) {
                    "QUOTED-PRINTABLE", "BASE64", "B", "8BIT", "7BIT" -> "ENCODING"
                    else -> "TYPE"
                }
                params.getOrPut(key) { ArrayList() } += bare
            } else {
                val key = token.substring(0, equals).trim().uppercase()
                val values = split(token.substring(equals + 1), ',').map { it.trim().removeSurrounding("\"") }
                // TYPE="work,fax" (RFC 6350 quotes a list too): its items one by one
                params.getOrPut(key) { ArrayList() } += if (key == "TYPE") values.flatMap { it.split(',') }.map { it.trim() } else values
            }
        }
        return Property(group, name, params, line.substring(at + 1))
    }

    /** Splits at [separator] outside quotes. */
    private fun split(text: String, separator: Char): List<String> {
        val parts = ArrayList<String>()
        val part = StringBuilder()
        var quoted = false
        for (c in text) {
            when {
                c == '"' -> { quoted = !quoted; part.append(c) }
                c == separator && !quoted -> { parts += part.toString(); part.setLength(0) }
                else -> part.append(c)
            }
        }
        parts += part.toString()
        return parts
    }

    /** A property's value as text: quoted-printable undone, then decoded in its charset. */
    private fun valueOf(property: Property, fallback: Charset): String {
        var bytes = property.raw.toByteArray(LATIN1)
        if (property.encoding == "QUOTED-PRINTABLE") bytes = unquote(bytes)
        val charset = property.param("CHARSET")?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: fallback
        return String(bytes, charset)
    }

    /** A parameter value (a label, a service name) in the file's charset. */
    private fun paramText(value: String?, fallback: Charset): String? = value?.let { String(it.toByteArray(LATIN1), fallback) }

    /** Quoted-printable undone: `=XX` is a byte, a lone "=" at the end a soft break. */
    private fun unquote(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == '='.code.toByte()) {
                val hex = if (i + 2 < bytes.size) String(bytes, i + 1, 2, LATIN1).toIntOrNull(16) else null
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
                if (i == bytes.size - 1) break
            }
            out.write(b.toInt())
            i++
        }
        return out.toByteArray()
    }

    /** Text with its escapes undone: `\n`, `\,`, `\;`, `\\`. */
    fun unescape(text: String): String {
        if ('\\' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                val next = text[i + 1]
                out.append(if (next == 'n' || next == 'N') '\n' else next)
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** A structured value's components (N, ADR, ORG, X-ANDROID-CUSTOM), split at unescaped semicolons and unescaped. */
    fun components(text: String): List<String> {
        val parts = ArrayList<String>()
        val part = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> { part.append(c).append(text[i + 1]); i += 2; continue }
                c == ';' -> { parts += unescape(part.toString()); part.setLength(0) }
                else -> part.append(c)
            }
            i++
        }
        parts += unescape(part.toString())
        return parts.map { it.trim() }
    }

    private fun card(properties: List<Property>, fallback: Charset, skipped: MutableSet<String>): CardContact? {
        // Apple's custom labels: one X-ABLabel per group, for the property of the same group
        val labels = properties.filter { it.name == "X-ABLABEL" && it.group != null }.associate { it.group!! to label(unescape(valueOf(it, fallback))) }
        fun labelOf(property: Property): String? = property.group?.let { labels[it] }?.takeIf { it.isNotBlank() }

        var formatted = ""
        val name = LinkedHashMap<String, String>()
        val values = LinkedHashMap<EditKind, MutableList<CardValue>>()
        var photo: ByteArray? = null
        val nicknames = ArrayList<String>()
        val notes = ArrayList<String>()
        var organization: String? = null
        var title: String? = null
        var role: String? = null
        fun add(kind: EditKind, value: CardValue) {
            if (value.parts.values.any { it.isNotBlank() }) values.getOrPut(kind) { ArrayList() } += value
        }
        fun im(handle: String, protocol: Int, custom: String? = null) = add(EditKind.IM, CardValue(mapOf("data1" to handle.trim()), protocol = protocol, label = custom))
        fun sip(address: String, property: Property) = add(EditKind.SIP, CardValue(mapOf("data1" to address.trim().removePrefix("sip:").removePrefix("SIP:")), type = simpleType(property.types)))

        for (property in properties) {
            val text = valueOf(property, fallback)
            when (property.name) {
                "FN" -> formatted = unescape(text).trim()
                "N" -> {
                    val c = components(text)
                    listOf("data3", "data2", "data5", "data4", "data6").forEachIndexed { i, column -> c.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { name[column] = it } }
                }
                "X-PHONETIC-FIRST-NAME" -> unescape(text).trim().takeIf { it.isNotEmpty() }?.let { name["data7"] = it }
                "X-PHONETIC-MIDDLE-NAME" -> unescape(text).trim().takeIf { it.isNotEmpty() }?.let { name["data8"] = it }
                "X-PHONETIC-LAST-NAME" -> unescape(text).trim().takeIf { it.isNotEmpty() }?.let { name["data9"] = it }
                "NICKNAME" -> unescape(text).trim().takeIf { it.isNotEmpty() }?.let { nicknames += it }
                "ORG" -> if (organization == null) organization = components(text).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }
                "TITLE" -> if (title == null) title = unescape(text).trim().ifEmpty { null }
                "ROLE" -> if (role == null) role = unescape(text).trim().ifEmpty { null }
                "TEL" -> {
                    val number = unescape(text).trim().let { if (it.startsWith("tel:", ignoreCase = true)) it.substring(4) else it }
                    val (type, label) = labelOf(property)?.let { phoneLabel(it) } ?: (phoneType(property.types) to null)
                    add(EditKind.PHONE, CardValue(mapOf("data1" to number), type, label, property.pref))
                }
                "EMAIL" -> {
                    val address = unescape(text).trim().let { if (it.startsWith("mailto:", ignoreCase = true)) it.substring(7) else it }
                    val (type, label) = labelOf(property)?.let { emailLabel(it) } ?: (emailType(property.types) to null)
                    add(EditKind.EMAIL, CardValue(mapOf("data1" to address), type, label, property.pref))
                }
                "ADR" -> {
                    val c = components(text)
                    val street = listOfNotNull(c.getOrNull(0), c.getOrNull(1), c.getOrNull(2)).filter { it.isNotEmpty() }.joinToString("\n")
                    val parts = mapOf("data4" to street, "data7" to c.getOrNull(3).orEmpty(), "data8" to c.getOrNull(4).orEmpty(), "data9" to c.getOrNull(5).orEmpty(), "data10" to c.getOrNull(6).orEmpty())
                        .filterValues { it.isNotEmpty() }
                    val (type, label) = labelOf(property)?.let { simpleLabel(it) } ?: (simpleType(property.types) to null)
                    add(EditKind.ADDRESS, CardValue(parts, type, label))
                }
                "URL" -> {
                    val (type, label) = labelOf(property)?.let { websiteLabel(it) } ?: (websiteType(property.types) to null)
                    add(EditKind.WEBSITE, CardValue(mapOf("data1" to unescape(text).trim()), type, label))
                }
                "BDAY" -> add(EditKind.DATE, CardValue(mapOf("data1" to date(text, property)), 3))
                "ANNIVERSARY", "X-ANNIVERSARY" -> add(EditKind.DATE, CardValue(mapOf("data1" to date(text, property)), 1))
                "X-ABDATE" -> {
                    val (type, label) = dateLabel(labelOf(property) ?: "Other")
                    add(EditKind.DATE, CardValue(mapOf("data1" to date(text, property)), type, label))
                }
                "RELATED", "X-ABRELATEDNAMES" -> {
                    val value = unescape(text).trim()
                    // A reference to another card says nothing the storage can keep
                    if (value.startsWith("urn:", ignoreCase = true)) { skipped += property.name; continue }
                    val (type, label) = labelOf(property)?.let { relationLabel(it) } ?: relationType(property.types)
                    add(EditKind.RELATION, CardValue(mapOf("data1" to value), type, label))
                }
                "IMPP" -> {
                    val value = unescape(text).trim()
                    val scheme = value.substringBefore(':', "").lowercase()
                    val handle = if (scheme.isEmpty()) value else value.substringAfter(':')
                    when (scheme) {
                        "sip" -> sip(handle, property)
                        "x-apple", "" -> {
                            val (protocol, custom) = protocolOf(paramText(property.param("X-SERVICE-TYPE"), fallback) ?: labelOf(property) ?: "Other")
                            im(handle, protocol, custom)
                        }
                        else -> {
                            val (protocol, custom) = schemeProtocol(scheme)
                            im(handle, protocol, custom)
                        }
                    }
                }
                in LEGACY_IM -> im(unescape(text), LEGACY_IM.getValue(property.name))
                "X-SIP" -> sip(unescape(text), property)
                "X-ANDROID-CUSTOM" -> {
                    val c = components(text)
                    when (c.firstOrNull()) {
                        "vnd.android.cursor.item/nickname" -> c.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { nicknames += it }
                        "vnd.android.cursor.item/relation" -> add(EditKind.RELATION, CardValue(mapOf("data1" to c.getOrNull(1).orEmpty()), c.getOrNull(2)?.toIntOrNull() ?: 0, c.getOrNull(3)?.ifEmpty { null }))
                        "vnd.android.cursor.item/contact_event" -> add(EditKind.DATE, CardValue(mapOf("data1" to date(c.getOrNull(1).orEmpty(), null)), c.getOrNull(2)?.toIntOrNull() ?: 0, c.getOrNull(3)?.ifEmpty { null }))
                        else -> skipped += property.name
                    }
                }
                "NOTE" -> unescape(text).trim().takeIf { it.isNotEmpty() }?.let { notes += it }
                "PHOTO" -> photo = photo ?: photoOf(property) ?: run { skipped += property.name; null }
                else -> if (property.name !in QUIET) skipped += property.name
            }
        }
        if (nicknames.isNotEmpty()) add(EditKind.NICKNAME, CardValue(mapOf("data1" to nicknames.distinct().joinToString(", "))))
        if (organization != null || title != null || role != null) add(EditKind.COMPANY, CardValue(listOfNotNull(organization?.let { "data1" to it }, (title ?: role)?.let { "data4" to it }).toMap()))
        if (notes.isNotEmpty()) add(EditKind.NOTE, CardValue(mapOf("data1" to notes.joinToString("\n\n"))))
        // Only one default per kind, as the storage keeps it
        for (kind in listOf(EditKind.PHONE, EditKind.EMAIL)) {
            val list = values[kind] ?: continue
            val first = list.indexOfFirst { it.isDefault }
            if (first >= 0) values[kind] = list.mapIndexed { i, v -> if (i != first && v.isDefault) v.copy(isDefault = false) else v }.toMutableList()
        }
        val displayName = formatted.ifEmpty {
            listOf("data4", "data2", "data5", "data3", "data6").mapNotNull { name[it] }.joinToString(" ").ifEmpty { organization.orEmpty() }
        }
        if (displayName.isEmpty() && name.isEmpty() && values.isEmpty() && photo == null) return null
        return CardContact(displayName, name, values, photo)
    }

    /** Apple writes its own labels as `_$!<Mobile>!$_`: the name inside. */
    private fun label(text: String): String = text.trim().removePrefix("_\$!<").removeSuffix(">!\$_").trim()

    private fun photoOf(property: Property): ByteArray? = try {
        val raw = property.raw.trim()
        when {
            raw.startsWith("data:", ignoreCase = true) -> if (raw.substringBefore(',').contains(";base64", ignoreCase = true)) Base64.getMimeDecoder().decode(raw.substringAfter(',')) else null
            property.encoding == "B" || property.encoding == "BASE64" -> Base64.getMimeDecoder().decode(raw)
            else -> null
        }?.takeIf { it.isNotEmpty() }
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * A date as the storage keeps it (`yyyy-MM-dd`, `--MM-dd`, SPEC §6.4.1): from `yyyyMMdd`,
     * `yyyy-MM-dd`, `--MMdd`, `--MM-dd`, with a time cut off; Apple's year 1604 (or the one its
     * `X-APPLE-OMIT-YEAR` names) means no year. Anything else is kept as written.
     */
    private fun date(text: String, property: Property?): String {
        val value = unescape(text).trim().substringBefore('T')
        Regex("^(\\d{4})-?(\\d{2})-?(\\d{2})$").find(value)?.let { match ->
            val (year, month, day) = match.destructured
            val omitted = property?.param("X-APPLE-OMIT-YEAR")
            return if (year == "1604" || year == omitted) "--$month-$day" else "$year-$month-$day"
        }
        Regex("^--(\\d{2})-?(\\d{2})$").find(value)?.let { match ->
            val (month, day) = match.destructured
            return "--$month-$day"
        }
        return value
    }

    // The storage's type codes (ContactsContract.CommonDataKinds), named here so the model stays pure Kotlin

    private fun phoneType(types: Set<String>): Int {
        val fax = "fax" in types
        val work = "work" in types
        val mobile = "cell" in types || "mobile" in types || "iphone" in types
        return when {
            fax && work -> 4
            fax && "home" in types -> 5
            fax -> 13
            "pager" in types && work -> 18
            "pager" in types -> 6
            mobile && work -> 17
            mobile || "text" in types -> 2
            "car" in types -> 9
            "isdn" in types -> 11
            "main" in types -> 12
            work -> 3
            "home" in types -> 1
            else -> 7
        }
    }

    private fun phoneLabel(label: String): Pair<Int, String?> = when (label.lowercase()) {
        "mobile", "cell", "iphone" -> 2 to null
        "home" -> 1 to null
        "work" -> 3 to null
        "workfax", "work fax" -> 4 to null
        "homefax", "home fax" -> 5 to null
        "pager" -> 6 to null
        "other" -> 7 to null
        "main" -> 12 to null
        else -> 0 to label
    }

    private fun emailType(types: Set<String>): Int = when {
        "home" in types -> 1
        "work" in types -> 2
        "x-mobile" in types || "mobile" in types -> 4
        else -> 3
    }

    private fun emailLabel(label: String): Pair<Int, String?> = when (label.lowercase()) {
        "home" -> 1 to null
        "work" -> 2 to null
        "other" -> 3 to null
        "mobile" -> 4 to null
        else -> 0 to label
    }

    /** Home (1), work (2), other (3): addresses and SIP. */
    private fun simpleType(types: Set<String>): Int = when {
        "home" in types -> 1
        "work" in types -> 2
        else -> 3
    }

    private fun simpleLabel(label: String): Pair<Int, String?> = when (label.lowercase()) {
        "home" -> 1 to null
        "work" -> 2 to null
        "other" -> 3 to null
        else -> 0 to label
    }

    private fun websiteType(types: Set<String>): Int = when {
        "work" in types -> 5
        "home" in types -> 1
        else -> 7
    }

    private fun websiteLabel(label: String): Pair<Int, String?> = when (label.lowercase()) {
        "homepage", "home" -> 1 to null
        "blog" -> 2 to null
        "profile" -> 3 to null
        "work" -> 5 to null
        "ftp" -> 6 to null
        "other" -> 7 to null
        else -> 0 to label
    }

    private fun dateLabel(label: String): Pair<Int, String?> = when (label.lowercase()) {
        "anniversary" -> 1 to null
        "other" -> 2 to null
        "birthday" -> 3 to null
        else -> 0 to label
    }

    /** RFC 6350's relation types; those without a storage type keep their name as the label. */
    private fun relationType(types: Set<String>): Pair<Int, String?> = when {
        "spouse" in types -> 14 to null
        "sweetheart" in types -> 10 to null
        "child" in types -> 3 to null
        "parent" in types -> 9 to null
        "friend" in types -> 6 to null
        "kin" in types -> 12 to null
        "agent" in types -> 1 to null
        "sibling" in types -> 0 to "Sibling"
        "co-worker" in types -> 0 to "Co-worker"
        "colleague" in types -> 0 to "Colleague"
        else -> 0 to (types.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "Other")
    }

    private fun relationLabel(label: String): Pair<Int, String?> = when (label.lowercase()) {
        "assistant" -> 1 to null
        "brother" -> 2 to null
        "child" -> 3 to null
        "domestic partner" -> 4 to null
        "father" -> 5 to null
        "friend" -> 6 to null
        "manager" -> 7 to null
        "mother" -> 8 to null
        "parent" -> 9 to null
        "partner" -> 10 to null
        "referred by" -> 11 to null
        "relative" -> 12 to null
        "sister" -> 13 to null
        "spouse" -> 14 to null
        else -> 0 to label
    }

    /** A messaging service by name (Apple's `X-SERVICE-TYPE`): the storage's protocol, or -1 with the name. */
    fun protocolOf(service: String): Pair<Int, String?> = when (service.trim().lowercase()) {
        "aim" -> 0 to null
        "msn" -> 1 to null
        "yahoo" -> 2 to null
        "skype" -> 3 to null
        "qq" -> 4 to null
        "googletalk", "google talk", "gtalk" -> 5 to null
        "icq" -> 6 to null
        "jabber", "xmpp" -> 7 to null
        "netmeeting" -> 8 to null
        "matrix" -> -1 to "Matrix"
        "signal" -> -1 to "Signal"
        "telegram" -> -1 to "Telegram"
        else -> -1 to service.trim().ifEmpty { "Other" }
    }

    private fun schemeProtocol(scheme: String): Pair<Int, String?> = when (scheme) {
        "aim" -> 0 to null
        "msnim", "msn" -> 1 to null
        "ymsgr" -> 2 to null
        "skype" -> 3 to null
        "qq" -> 4 to null
        "gtalk" -> 5 to null
        "icq" -> 6 to null
        "xmpp" -> 7 to null
        "matrix" -> -1 to "Matrix"
        "sgnl", "signal" -> -1 to "Signal"
        "tg", "telegram" -> -1 to "Telegram"
        else -> -1 to scheme
    }
}
