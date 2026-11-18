package sh.hnet.contacts.model

import java.util.Base64

/** One value of a contact for a vCard: the kind's parts as the storage keeps them, with its type, custom label and default flag. */
data class CardValue(val parts: Map<String, String>, val type: Int = 0, val label: String? = null, val isDefault: Boolean = false, val protocol: Int? = null) {
    fun part(column: String): String = parts[column].orEmpty().trim()
}

/**
 * A contact as it goes into a vCard (SPEC §11.3): every value of its entries, equal ones once
 * (§9.5), and the photo as JPEG. [values] holds the kinds of [EditKind] except the name, which is
 * [name] (the storage's columns `data4` prefix, `data2` given, `data5` middle, `data3` family,
 * `data6` suffix, `data7`/`data9` phonetic given and family) with [displayName].
 */
data class CardContact(
    val displayName: String,
    val name: Map<String, String>,
    val values: Map<EditKind, List<CardValue>>,
    val photo: ByteArray? = null,
)

/**
 * vCard 4.0 (RFC 6350), written by the app itself (owner's decisions of 17 Nov 2026: our own
 * writer, version 4.0). Pure Kotlin, unit-tested; the storage's type codes are mapped here, so the
 * data layer only hands the rows over. What 4.0 has no property for is written the way Apple's and
 * Android's apps do (a group with `X-ABLabel` for custom labels, `X-PHONETIC-*`, `X-ABDATE`), so
 * the app's own import reads everything back.
 */
object VCardWriter {
    private const val CRLF = "\r\n"

    /** Lines are folded at 75 octets (RFC 6350 §3.2), never inside a UTF-8 sequence. */
    private const val FOLD = 75

    fun write(contacts: List<CardContact>): String = buildString { contacts.forEach { append(card(it)) } }

    fun card(contact: CardContact): String {
        val lines = ArrayList<String>()
        var group = 0
        fun labeled(property: String, label: String?) {
            if (label.isNullOrBlank()) {
                lines += property
            } else {
                group++
                lines += "item$group.$property"
                lines += "item$group.X-ABLabel:${text(label)}"
            }
        }
        lines += "BEGIN:VCARD"
        lines += "VERSION:4.0"
        lines += "PRODID:-//HNet//Contacts//EN"
        val n = contact.name
        fun np(column: String) = component(n[column].orEmpty().trim())
        lines += "FN:${text(contact.displayName)}"
        if (n.values.any { it.isNotBlank() }) lines += "N:${np("data3")};${np("data2")};${np("data5")};${np("data4")};${np("data6")}"
        n["data7"]?.takeIf { it.isNotBlank() }?.let { lines += "X-PHONETIC-FIRST-NAME:${text(it.trim())}" }
        n["data9"]?.takeIf { it.isNotBlank() }?.let { lines += "X-PHONETIC-LAST-NAME:${text(it.trim())}" }
        for (value in contact.values[EditKind.NICKNAME].orEmpty()) lines += "NICKNAME:${text(value.part("data1"))}"
        for (value in contact.values[EditKind.COMPANY].orEmpty()) {
            if (value.part("data1").isNotEmpty()) lines += "ORG:${component(value.part("data1"))}"
            if (value.part("data4").isNotEmpty()) lines += "TITLE:${text(value.part("data4"))}"
        }
        for (value in contact.values[EditKind.PHONE].orEmpty()) {
            labeled("TEL${params(phoneType(value.type), value.isDefault)}:${text(value.part("data1"))}", value.label.takeIf { value.type == 0 })
        }
        for (value in contact.values[EditKind.EMAIL].orEmpty()) {
            labeled("EMAIL${params(emailType(value.type), value.isDefault)}:${text(value.part("data1"))}", value.label.takeIf { value.type == 0 })
        }
        for (value in contact.values[EditKind.ADDRESS].orEmpty()) {
            val adr = listOf("", "", value.part("data4"), value.part("data7"), value.part("data8"), value.part("data9"), value.part("data10")).joinToString(";") { component(it) }
            labeled("ADR${params(simpleType(value.type), false)}:$adr", value.label.takeIf { value.type == 0 })
        }
        for (value in contact.values[EditKind.WEBSITE].orEmpty()) {
            // Blog, profile and FTP have no vCard type: their names as labels, so the import knows them again
            val label = when (value.type) {
                0 -> value.label
                2 -> "Blog"
                3 -> "Profile"
                6 -> "FTP"
                else -> null
            }
            labeled("URL${params(websiteType(value.type), false)}:${uri(value.part("data1"))}", label)
        }
        for (value in contact.values[EditKind.DATE].orEmpty()) {
            val date = date(value.part("data1")) ?: continue
            when (value.type) {
                3 -> lines += "BDAY:$date"
                1 -> lines += "ANNIVERSARY:$date"
                // Other dates have no property in 4.0: Apple's, with the label (or "Other")
                else -> labeled("X-ABDATE:$date", value.label ?: "Other")
            }
        }
        for (value in contact.values[EditKind.RELATION].orEmpty()) {
            val (type, label) = relationType(value)
            labeled("RELATED${if (type != null) ";TYPE=$type" else ""};VALUE=text:${text(value.part("data1"))}", label)
        }
        for (value in contact.values[EditKind.IM].orEmpty()) {
            val handle = value.part("data1")
            // XMPP has its URI scheme; the others go as Apple writes them: the service in a parameter
            if (value.protocol == 7) lines += "IMPP:xmpp:${uri(handle)}"
            else lines += "IMPP;X-SERVICE-TYPE=${param(value.label?.takeIf { value.protocol == -1 } ?: service(value.protocol))}:x-apple:${uri(handle)}"
        }
        for (value in contact.values[EditKind.SIP].orEmpty()) {
            val address = value.part("data1")
            lines += "IMPP${params(simpleType(value.type), false)}:${if (address.startsWith("sip:")) address else "sip:$address"}"
        }
        for (value in contact.values[EditKind.NOTE].orEmpty()) lines += "NOTE:${text(value.part("data1"))}"
        contact.photo?.let { lines += "PHOTO:data:image/jpeg;base64,${Base64.getEncoder().encodeToString(it)}" }
        lines += "END:VCARD"
        return lines.joinToString("") { fold(it) + CRLF }
    }

    /** The storage's old messaging protocols by the names Apple's apps use (`VCardReader.protocolOf` reads them back). */
    private fun service(protocol: Int?): String = when (protocol) {
        0 -> "AIM"
        1 -> "MSN"
        2 -> "Yahoo"
        3 -> "Skype"
        4 -> "QQ"
        5 -> "GoogleTalk"
        6 -> "ICQ"
        8 -> "NetMeeting"
        else -> "Other"
    }

    /** TYPE and PREF parameters: `;TYPE=cell;PREF=1`. */
    private fun params(type: String?, isDefault: Boolean): String =
        (if (type != null) ";TYPE=$type" else "") + (if (isDefault) ";PREF=1" else "")

    /** The storage's phone types as vCard's (RFC 6350 §6.4.1); null for a custom one, which goes by its label. */
    private fun phoneType(type: Int): String? = when (type) {
        2 -> "cell"
        1 -> "home"
        3 -> "work"
        4 -> "\"work,fax\""
        5 -> "\"home,fax\""
        6 -> "pager"
        // Types without an RFC 6350 value of their own: the names `VCardReader.phoneType` reads back
        17 -> "\"cell,work\""
        18 -> "\"pager,work\""
        13 -> "fax"
        9 -> "car"
        11 -> "isdn"
        12 -> "main"
        7 -> "voice"
        0 -> null
        else -> "voice"
    }

    /** Home, work, mobile (Apple's `x-mobile`, read back by `VCardReader.emailType`), other with no type. */
    private fun emailType(type: Int): String? = if (type == 4) "x-mobile" else simpleType(type)

    /** Home (1), work (2), other (3, no type) for e-mail, address and SIP. */
    private fun simpleType(type: Int): String? = when (type) {
        1 -> "home"
        2 -> "work"
        else -> null
    }

    private fun websiteType(type: Int): String? = when (type) {
        5 -> "work"
        1, 4 -> "home"
        else -> null
    }

    /**
     * RFC 6350's relation types for the storage's (§6.6.6); those without an equal one go with the
     * nearest type and the storage's name as the label, so nothing is lost on the way back in.
     */
    private fun relationType(value: CardValue): Pair<String?, String?> = when (value.type) {
        14 -> "spouse" to null
        10 -> "sweetheart" to "Partner"
        3 -> "child" to null
        9 -> "parent" to null
        8 -> "parent" to "Mother"
        5 -> "parent" to "Father"
        2 -> "sibling" to "Brother"
        13 -> "sibling" to "Sister"
        6 -> "friend" to null
        12 -> "kin" to null
        1 -> "agent" to "Assistant"
        7 -> "co-worker" to "Manager"
        4 -> "co-resident" to "Domestic partner"
        11 -> "contact" to "Referred by"
        else -> null to (value.label ?: "Other")
    }

    /** `yyyy-MM-dd` as `yyyyMMdd`, `--MM-dd` as `--MMdd` (RFC 6350 §4.3.1); null for anything else. */
    fun date(stored: String): String? = when (val date = ContactDate.parse(stored)) {
        is ContactDate.Full -> "%04d%02d%02d".format(date.date.year, date.date.monthValue, date.date.dayOfMonth)
        is ContactDate.NoYear -> "--%02d%02d".format(date.day.monthValue, date.day.dayOfMonth)
        null -> null
    }

    /** A text value (RFC 6350 §3.4): backslash, comma and line breaks (a lone CR too) escaped. */
    fun text(value: String): String = value.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\r\n", "\\n").replace("\r", "\\n").replace("\n", "\\n")

    /** A component of a structured value (N, ADR, ORG): the semicolon separates components, so it is escaped too. */
    private fun component(value: String): String = text(value)

    /** A URI as given; only line breaks would break the line, and a URI has none. */
    private fun uri(value: String): String = value.replace("\n", "").replace("\r", "")

    /** A parameter value: quoted when it holds a character the grammar keeps for itself. */
    private fun param(value: String): String {
        // A quote can't be escaped in a parameter value (RFC 6350 §5): it goes, or the reader would take it as the start of a quoted value
        val plain = value.replace("\"", "")
        return if (plain.any { it == ':' || it == ';' || it == ',' }) "\"$plain\"" else plain
    }

    /** Folds a line at [FOLD] octets with CRLF and a space, never splitting a character's UTF-8 bytes. */
    fun fold(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= FOLD) return line
        val out = StringBuilder()
        var used = 0
        var limit = FOLD
        for (char in line.codePoints().toArray()) {
            val size = String(Character.toChars(char)).toByteArray(Charsets.UTF_8).size
            if (used + size > limit) {
                out.append(CRLF).append(' ')
                used = 1
                limit = FOLD
            }
            out.appendCodePoint(char)
            used += size
        }
        return out.toString()
    }
}
