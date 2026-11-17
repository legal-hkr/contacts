package sh.hnet.contacts.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VCardWriterTest {
    private fun v(vararg parts: Pair<String, String>, type: Int = 0, label: String? = null, default: Boolean = false, protocol: Int? = null) = CardValue(parts.toMap(), type, label, default, protocol)

    private val gosia = CardContact(
        displayName = "Małgorzata Wiśniewska-Kowalczyk",
        name = mapOf("data2" to "Małgorzata", "data3" to "Wiśniewska-Kowalczyk", "data7" to "Malgożata"),
        values = mapOf(
            EditKind.NICKNAME to listOf(v("data1" to "Gosia")),
            EditKind.COMPANY to listOf(v("data1" to "Urząd Miasta", "data4" to "Naczelniczka wydziału")),
            EditKind.PHONE to listOf(v("data1" to "601 234 567", type = 2, default = true), v("data1" to "+48 22 123 45 67", type = 0, label = "Klub")),
            EditKind.EMAIL to listOf(v("data1" to "m@example.org", type = 2)),
            EditKind.ADDRESS to listOf(v("data4" to "ul. Marszałkowska 1/23", "data7" to "Warszawa", "data9" to "00-001", "data10" to "Polska", type = 1)),
            EditKind.DATE to listOf(v("data1" to "1985-06-04", type = 3), v("data1" to "--03-12", type = 0, label = "Imieniny")),
            EditKind.RELATION to listOf(v("data1" to "Anna Kowalska", type = 13)),
            EditKind.IM to listOf(v("data1" to "gosia@jabber.example.org", protocol = 7), v("data1" to "@gosia:matrix.org", protocol = -1, label = "Matrix")),
            EditKind.SIP to listOf(v("data1" to "gosia@sip.example.org", type = 2)),
            EditKind.NOTE to listOf(v("data1" to "Pierwsza linia, druga część\nDruga linia")),
        ),
        photo = byteArrayOf(1, 2, 3),
    )

    private fun lines(text: String) = text.replace("\r\n ", "").split("\r\n").filter { it.isNotEmpty() }

    @Test
    fun writesVersion4WithEveryKind() {
        val lines = lines(VCardWriter.card(gosia))
        assertEquals(listOf("BEGIN:VCARD", "VERSION:4.0"), lines.take(2))
        assertEquals("END:VCARD", lines.last())
        for (expected in listOf(
            "FN:Małgorzata Wiśniewska-Kowalczyk",
            "N:Wiśniewska-Kowalczyk;Małgorzata;;;",
            "X-PHONETIC-FIRST-NAME:Malgożata",
            "NICKNAME:Gosia",
            "ORG:Urząd Miasta",
            "TITLE:Naczelniczka wydziału",
            "TEL;TYPE=cell;PREF=1:601 234 567",
            "item1.TEL:+48 22 123 45 67",
            "item1.X-ABLabel:Klub",
            "EMAIL;TYPE=work:m@example.org",
            "ADR;TYPE=home:;;ul. Marszałkowska 1/23;Warszawa;;00-001;Polska",
            "BDAY:19850604",
            "item2.X-ABDATE:--0312",
            "item2.X-ABLabel:Imieniny",
            "item3.RELATED;TYPE=sibling;VALUE=text:Anna Kowalska",
            "item3.X-ABLabel:Sister",
            "IMPP:xmpp:gosia@jabber.example.org",
            "IMPP;X-SERVICE-TYPE=Matrix:x-apple:@gosia:matrix.org",
            "IMPP;TYPE=work:sip:gosia@sip.example.org",
            "NOTE:Pierwsza linia\\, druga część\\nDruga linia",
            "PHOTO:data:image/jpeg;base64,AQID",
        )) assertTrue(expected, expected in lines)
    }

    @Test
    fun linesFoldAt75OctetsWithoutSplittingACharacter() {
        val long = CardContact("Ż".repeat(60), emptyMap(), emptyMap())
        val raw = VCardWriter.card(long).split("\r\n")
        for (line in raw) assertTrue(line, line.toByteArray(Charsets.UTF_8).size <= 75)
        assertEquals("FN:" + "Ż".repeat(60), lines(VCardWriter.card(long)).first { it.startsWith("FN:") })
    }

    @Test
    fun datesWithAndWithoutYear() {
        assertEquals("19850604", VCardWriter.date("1985-06-04"))
        assertEquals("--0312", VCardWriter.date("--03-12"))
        assertEquals(null, VCardWriter.date("jutro"))
    }

    @Test
    fun textEscapesItsSeparators() {
        assertEquals("a\\,b\\;c\\\\d\\ne", VCardWriter.text("a,b;c\\d\ne"))
    }
}
