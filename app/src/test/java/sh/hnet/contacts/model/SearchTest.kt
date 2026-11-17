package sh.hnet.contacts.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private fun contact(id: Long, name: String, vararg fields: SearchField, shown: Boolean = true) =
        SearchContact(ContactRow(id, "k$id", name, name, null, false), shown, fields.toList())

    private val lukasz = contact(1, "Łukasz Świątek", SearchField(MatchKind.PHONE, "+48 601 234 567", 2))
    private val lukas = contact(2, "Lukas Podolski", SearchField(MatchKind.NICKNAME, "Poldi"))
    private val anna = contact(
        3, "Anna Kowalska",
        SearchField(MatchKind.EMAIL, "a.kowalska@example.org"),
        SearchField(MatchKind.COMPANY, "Urząd Miasta"),
        SearchField(MatchKind.NOTE, "Poznana na konferencji w Gdańsku, lubi kawę bez cukru i długie spacery"),
    )
    private val hidden = contact(4, "Signal Kontakt", SearchField(MatchKind.PHONE, "604 000 000", 2), shown = false)
    private val all = listOf(lukasz, lukas, anna, hidden)

    @Test
    fun numbersMatchWithAndWithoutSpacesAndTheCountryCode() {
        for (query in listOf("60123", "601 23", "+4860123", "48601234")) {
            assertEquals(query, listOf(1L), Search.find(all, query, "48").map { it.contact.id })
        }
    }

    @Test
    fun theHighlightCoversTheMatchedDigitsOfTheShownNumber() {
        val range = Search.findNumber("+48 601 234 567", "601 23", "48")
        assertEquals("601 23", "+48 601 234 567".substring(range!!))
    }

    @Test
    fun aQueryWithoutMarksMatchesLettersWithThem() {
        assertEquals(listOf(1L, 2L), Search.find(all, "lukas", "48").map { it.contact.id })
        assertEquals(listOf(1L), Search.find(all, "swiatek", "48").map { it.contact.id })
    }

    @Test
    fun aQueryWithMarksIsExact() {
        assertEquals(listOf(1L), Search.find(all, "Ł", "48").map { it.contact.id })
    }

    @Test
    fun textMatchesAtTheStartOfAWordOnlyButEmailsAnywhere() {
        assertTrue(Search.find(all, "ukasz", "48").isEmpty())
        val email = Search.find(all, "example", "48").single()
        assertEquals(MatchKind.EMAIL, email.field?.kind)
    }

    @Test
    fun nameMatchesComeBeforeOtherFields() {
        val results = Search.find(listOf(anna, lukas), "po", "48")
        // Anna's note ("Poznana") is another field, Lukas's name is a name
        assertEquals(listOf(2L, 3L), results.map { it.contact.id })
        assertTrue(results[0].inNames)
        assertEquals(false, results[1].inNames)
    }

    @Test
    fun aNicknameCountsAsANameWithItsOwnLine() {
        val result = Search.find(all, "poldi", "48").single()
        assertTrue(result.inNames)
        assertEquals(MatchKind.NICKNAME, result.field?.kind)
    }

    @Test
    fun aNoteShowsTheWordsAroundTheMatch() {
        val result = Search.find(all, "kawę", "48").single()
        assertEquals("…w Gdańsku, lubi kawę bez cukru i…", result.text)
        assertEquals("kawę", result.text.substring(result.range!!))
    }

    @Test
    fun hiddenAccountsAreSearchedOnlyOnSearchAll() {
        assertTrue(Search.find(all, "signal", "48").isEmpty())
        assertNotNull(Search.find(all, "signal", "48", all = true).singleOrNull())
    }

    @Test
    fun shortDigitQueriesAreText() {
        assertNull(Search.findText("Pokój 12", "12x"))
        assertTrue(!Search.isNumberQuery("12"))
        assertTrue(Search.isNumberQuery("(22) 123"))
    }
}
