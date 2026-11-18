package sh.hnet.contacts.model

import java.nio.charset.Charset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VCardReaderTest {
    private fun v(vararg parts: Pair<String, String>, type: Int = 0, label: String? = null, default: Boolean = false, protocol: Int? = null) = CardValue(parts.toMap(), type, label, default, protocol)

    private fun read(text: String, charset: Charset = Charsets.UTF_8) = VCardReader.read(text.toByteArray(charset))

    @Test
    fun readsBackWhatTheWriterWrites() {
        val gosia = CardContact(
            displayName = "Małgorzata Wiśniewska-Kowalczyk",
            name = mapOf("data2" to "Małgorzata", "data3" to "Wiśniewska-Kowalczyk", "data7" to "Malgożata"),
            values = mapOf(
                EditKind.PHONE to listOf(v("data1" to "601 234 567", type = 2, default = true), v("data1" to "+48 22 123 45 67", label = "Klub"), v("data1" to "22 600 00 00", type = 4)),
                EditKind.EMAIL to listOf(v("data1" to "m@example.org", type = 2)),
                EditKind.ADDRESS to listOf(v("data4" to "ul. Marszałkowska 1/23", "data7" to "Warszawa", "data9" to "00-001", "data10" to "Polska", type = 1)),
                EditKind.WEBSITE to listOf(v("data1" to "https://blog.example.org", type = 2), v("data1" to "https://example.org", type = 5)),
                EditKind.DATE to listOf(v("data1" to "1985-06-04", type = 3), v("data1" to "--03-12", label = "Imieniny"), v("data1" to "2010-09-18", type = 1)),
                EditKind.RELATION to listOf(v("data1" to "Anna Kowalska", type = 13), v("data1" to "Jan", type = 14), v("data1" to "Ola", label = "Chrześnica")),
                EditKind.IM to listOf(v("data1" to "gosia@jabber.example.org", protocol = 7), v("data1" to "@gosia:matrix.org", protocol = -1, label = "Matrix"), v("data1" to "gosia.k", protocol = 3)),
                EditKind.SIP to listOf(v("data1" to "gosia@sip.example.org", type = 2)),
                EditKind.NICKNAME to listOf(v("data1" to "Gosia")),
                EditKind.COMPANY to listOf(v("data1" to "Urząd Miasta", "data4" to "Naczelniczka wydziału")),
                EditKind.NOTE to listOf(v("data1" to "Pierwsza linia, druga; część\nDruga linia")),
            ),
            photo = ByteArray(300) { it.toByte() },
        )
        val file = VCardReader.read(VCardWriter.write(listOf(gosia)).toByteArray())
        assertEquals(emptyList<String>(), file.skipped)
        val back = file.contacts.single()
        assertEquals(gosia.displayName, back.displayName)
        assertEquals(gosia.name, back.name)
        for ((kind, values) in gosia.values) assertEquals(kind.name, values.map { it.copy(protocol = it.protocol.takeIf { kind == EditKind.IM }) }, back.values[kind])
        assertArrayEquals(gosia.photo, back.photo)
    }

    @Test
    fun readsAnOldPhonesQuotedPrintableCard() {
        val text = "BEGIN:VCARD\r\nVERSION:2.1\r\n" +
            "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=C5=81=C4=85cka;Zofia;;;\r\n" +
            "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Zofia =C5=81=C4=85=\r\ncka\r\n" +
            "TEL;CELL;PREF:+48601000000\r\n" +
            "TEL;WORK;FAX:221234567\r\n" +
            "EMAIL;INTERNET:zofia@example.org\r\n" +
            "X-SIEMENS-RINGTONE:dzwonek.mid\r\n" +
            "END:VCARD\r\n"
        val file = read(text)
        val zofia = file.contacts.single()
        assertEquals("Zofia Łącka", zofia.displayName)
        assertEquals(mapOf("data3" to "Łącka", "data2" to "Zofia"), zofia.name)
        assertEquals(listOf(v("data1" to "+48601000000", type = 2, default = true), v("data1" to "221234567", type = 4)), zofia.values[EditKind.PHONE])
        assertEquals(listOf(v("data1" to "zofia@example.org", type = 3)), zofia.values[EditKind.EMAIL])
        assertEquals(listOf("X-SIEMENS-RINGTONE"), file.skipped)
        assertEquals(1, file.skippedContacts)
    }

    @Test
    fun takesWindows1250WhenTheFileIsNotUtf8AndHonoursADeclaredCharset() {
        val plain = read("BEGIN:VCARD\nVERSION:3.0\nFN:Paweł Żółć\nEND:VCARD\n", Charset.forName("windows-1250"))
        assertEquals("Paweł Żółć", plain.contacts.single().displayName)
        // ISO-8859-2 named on the value: ś is 0xB6 there (0x9C in Windows-1250)
        val declared = read("BEGIN:VCARD\nVERSION:2.1\nFN;CHARSET=ISO-8859-2;ENCODING=QUOTED-PRINTABLE:=B6wi=EAty\nEND:VCARD\n")
        assertEquals("święty", declared.contacts.single().displayName)
    }

    @Test
    fun readsApplesLabelsDatesAndServices() {
        val text = "BEGIN:VCARD\nVERSION:3.0\nFN:Tim\n" +
            "item1.TEL:+1 555 0100\nitem1.X-ABLabel:_\$!<Mobile>!\$_\n" +
            "item2.X-ABDATE;X-APPLE-OMIT-YEAR=1604:1604-05-02\nitem2.X-ABLabel:_\$!<Anniversary>!\$_\n" +
            "item3.X-ABRELATEDNAMES:Laurene\nitem3.X-ABLabel:_\$!<Spouse>!\$_\n" +
            "IMPP;X-SERVICE-TYPE=Skype:x-apple:tim.c\n" +
            "IMPP;X-SERVICE-TYPE=Signal:x-apple:+15550100\n" +
            "BDAY:1960-11-01T00:00:00Z\n" +
            "END:VCARD\n"
        val tim = read(text).contacts.single()
        assertEquals(listOf(v("data1" to "+1 555 0100", type = 2)), tim.values[EditKind.PHONE])
        assertEquals(listOf(v("data1" to "--05-02", type = 1), v("data1" to "1960-11-01", type = 3)), tim.values[EditKind.DATE])
        assertEquals(listOf(v("data1" to "Laurene", type = 14)), tim.values[EditKind.RELATION])
        assertEquals(listOf(v("data1" to "tim.c", protocol = 3), v("data1" to "+15550100", protocol = -1, label = "Signal")), tim.values[EditKind.IM])
    }

    @Test
    fun readsAndroidsCustomRowsAndOldMessagingProperties() {
        val text = "BEGIN:VCARD\nVERSION:2.1\nN:Nowak;Adam;;;\n" +
            "X-ANDROID-CUSTOM:vnd.android.cursor.item/nickname;Adaś;1;;;;;;;;;;;;;\n" +
            "X-ANDROID-CUSTOM:vnd.android.cursor.item/relation;Ewa;14;;;;;;;;;;;;;\n" +
            "X-ANDROID-CUSTOM:vnd.android.cursor.item/contact_event;2001-02-03;0;Imieniny;;;;;;;;;;;;\n" +
            "X-JABBER:adam@jabber.example.org\n" +
            "X-SIP:sip:adam@sip.example.org\n" +
            "END:VCARD\n"
        val adam = read(text).contacts.single()
        assertEquals("Adam Nowak", adam.displayName)
        assertEquals(listOf(v("data1" to "Adaś")), adam.values[EditKind.NICKNAME])
        assertEquals(listOf(v("data1" to "Ewa", type = 14)), adam.values[EditKind.RELATION])
        assertEquals(listOf(v("data1" to "2001-02-03", label = "Imieniny")), adam.values[EditKind.DATE])
        assertEquals(listOf(v("data1" to "adam@jabber.example.org", protocol = 7)), adam.values[EditKind.IM])
        assertEquals(listOf(v("data1" to "adam@sip.example.org", type = 3)), adam.values[EditKind.SIP])
    }

    @Test
    fun readsSeveralCardsAndSkipsANestedOne() {
        val text = "BEGIN:VCARD\nVERSION:2.1\nFN:Jeden\nAGENT:\nBEGIN:VCARD\nVERSION:2.1\nFN:Agent\nEND:VCARD\nEND:VCARD\n" +
            "BEGIN:VCARD\nVERSION:4.0\nFN:Dwa\nEND:VCARD\n" +
            "BEGIN:VCARD\nVERSION:4.0\nEND:VCARD\n"
        val file = read(text)
        assertEquals(listOf("Jeden", "Dwa"), file.contacts.map { it.displayName })
        assertEquals(listOf("AGENT"), file.skipped)
    }

    @Test
    fun aFileWithoutCardsIsNotVCard() {
        val thrown = runCatching { read("Hello\nworld\n") }.exceptionOrNull()
        assertTrue(thrown is NotVCardException)
        assertTrue(read("BEGIN:VCARD\nVERSION:4.0\nEND:VCARD\n").contacts.isEmpty())
    }

    @Test
    fun readsA21PhotoInBase64() {
        val text = "BEGIN:VCARD\nVERSION:2.1\nFN:Foto\nPHOTO;ENCODING=BASE64;TYPE=JPEG:AQID\n BAUG\n\nEND:VCARD\n"
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), read(text).contacts.single().photo)
        assertNull(read("BEGIN:VCARD\nVERSION:4.0\nFN:X\nPHOTO:https://example.org/a.jpg\nEND:VCARD\n").contacts.single().photo)
    }
}
