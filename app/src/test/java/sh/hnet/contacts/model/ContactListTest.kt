package sh.hnet.contacts.model

import java.text.Normalizer
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactListTest {
    private val pl = Locale.forLanguageTag("pl")
    private val en = Locale.ENGLISH

    private fun row(id: Long, name: String) = ContactRow(id, "key$id", name, name, null, false)

    @Test
    fun polishLettersFileUnderThemselvesInPolish() {
        assertEquals("Ł", ContactList.letter("Łukasz", pl))
        assertEquals("Ś", ContactList.letter("świątek", pl))
        assertEquals("Ż", ContactList.letter("Żaneta", pl))
        assertEquals("E", ContactList.letter("Émile", pl))
    }

    @Test
    fun polishLettersFileUnderTheBaseLetterInEnglish() {
        assertEquals("L", ContactList.letter("Łukasz", en))
        assertEquals("S", ContactList.letter("Świątek", en))
        assertEquals("O", ContactList.letter("Øystein", en))
    }

    @Test
    fun digitsAndSignsFileUnderOther() {
        assertEquals("#", ContactList.letter("+48 601 234 567", pl))
        assertEquals("#", ContactList.letter("112", pl))
        assertEquals("#", ContactList.letter("", pl))
    }

    @Test
    fun polishOrderPutsLStrokeAfterLAndOtherLast() {
        val entries = ContactList.build(listOf(row(1, "Łukasz"), row(2, "112"), row(3, "Lena"), row(4, "Mateusz"), row(5, "anna")), pl)
        val letters = entries.filterIsInstance<ListEntry.Header>().map { it.letter }
        assertEquals(listOf("A", "L", "Ł", "M", "#"), letters)
        val names = entries.filterIsInstance<ListEntry.Contact>().map { it.contact.name }
        assertEquals(listOf("anna", "Lena", "Łukasz", "Mateusz", "112"), names)
    }

    @Test
    fun englishOrderKeepsLStrokeWithL() {
        val entries = ContactList.build(listOf(row(1, "Łukasz"), row(3, "Lena"), row(4, "Lyle")), en)
        assertEquals(listOf("L"), entries.filterIsInstance<ListEntry.Header>().map { it.letter })
        // Unicode collation gives Ł its own place after L: in English it follows the L names, still under L
        assertEquals(listOf("Lena", "Lyle", "Łukasz"), entries.filterIsInstance<ListEntry.Contact>().map { it.contact.name })
    }

    @Test
    fun decomposedNamesFileUnderTheirOwnLetter() {
        // "Śliwa" as macOS writes it: S and a combining acute accent
        val decomposed = Normalizer.normalize("Śliwa", Normalizer.Form.NFD)
        assertEquals("Ś", ContactList.letter(decomposed, pl))
        val entries = ContactList.build(listOf(row(1, "Sikora"), row(2, decomposed), row(3, "Świątek")), pl)
        assertEquals(listOf("S", "Ś"), entries.filterIsInstance<ListEntry.Header>().map { it.letter })
    }

    @Test
    fun aLetterTheCollationBringsBackGetsNoSecondHeader() {
        // Swedish collation puts Å after Z, while the letter files under A: two "A" headers would share one list key
        val entries = ContactList.build(listOf(row(1, "Anna"), row(2, "Zoe"), row(3, "Åsa")), Locale.forLanguageTag("sv"))
        val keys = entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(listOf("A", "Z"), entries.filterIsInstance<ListEntry.Header>().map { it.letter })
    }

    @Test
    fun favoritesAreTheStarredOnesInTheListsOrder() {
        val rows = listOf(row(1, "Zofia").copy(starred = true), row(2, "Adam"), row(3, "Łucja").copy(starred = true), row(4, "Lena").copy(starred = true))
        assertEquals(listOf("Lena", "Łucja", "Zofia"), ContactList.favorites(rows, pl).map { it.name })
    }

    @Test
    fun initialsKeepTheirMarks() {
        assertEquals("Ś", Avatars.initial("świątek", pl))
        assertEquals("#", Avatars.initial("+48 601", pl))
        assertEquals("É", Avatars.initial("émile", en))
    }

    @Test
    fun toneStaysWithTheLookupKey() {
        val tone = Avatars.tone("0r12-ABC")
        assertEquals(tone, Avatars.tone("0r12-ABC"))
        assert(tone in 0 until Avatars.TONES)
    }

    @Test
    fun accountKeyRoundTrips() {
        val key = AccountKey("at.bitfire.davdroid", "jan@cloud.hnet.sh")
        assertEquals(key, AccountKey.of(key.key))
        assertEquals(AccountKey("", ""), AccountKey.of(AccountKey("", "").key))
    }

    @Test
    fun initialsAreGivenThenFamilyNameAlways() {
        val pl = java.util.Locale.forLanguageTag("pl")
        assertEquals("JK", Avatars.initials("Jan", "Kowalski", pl))
        assertEquals("ŁŻ", Avatars.initials("łucja", "żak", pl))
        assertEquals("J", Avatars.initials("Jan", null, pl))
        assertEquals("K", Avatars.initials(" ", "Kowalski", pl))
        assertEquals(null, Avatars.initials(null, null, pl))
        assertEquals("A", Avatars.initials("Ala", "123", pl))
    }
}
