package sh.hnet.contacts.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatesTest {
    private fun c(id: Long, name: String, numbers: Set<String> = emptySet(), emails: Set<String> = emptySet(), others: Set<String> = emptySet()) =
        DupContact(id, Duplicates.foldName(name), numbers, emails, others)

    @Test
    fun aSharedNumberOrEmailMakesAGroup() {
        val groups = Duplicates.find(listOf(c(1, "Anna", setOf("48601234567")), c(2, "A. Kowalska", setOf("48601234567")), c(3, "Jan", emails = setOf("jan@x.pl")), c(4, "Janek", emails = setOf("jan@x.pl"))), emptySet())
        assertEquals(listOf(DupGroup(listOf(1, 2), DupReason.NUMBER), DupGroup(listOf(3, 4), DupReason.EMAIL)), groups)
    }

    @Test
    fun aNameAloneIsNeverEnough() {
        assertTrue(Duplicates.find(listOf(c(1, "Anna Kowalska"), c(2, "anna kowalska")), emptySet()).isEmpty())
    }

    @Test
    fun theSameNameWithAnAddressInCommonIsSuggested() {
        val groups = Duplicates.find(listOf(c(1, "Łucja Nowak", others = setOf("ul. Polna 1")), c(2, "Lucja Nowak", others = setOf("ul. Polna 1"))), emptySet())
        assertEquals(listOf(DupGroup(listOf(1, 2), DupReason.NAME_EMAIL)), groups)
    }

    @Test
    fun groupsAreTransitiveAndLargeOnesDropped() {
        val chain = Duplicates.find(listOf(c(1, "A", setOf("48600000001")), c(2, "B", setOf("48600000001"), setOf("b@x.pl")), c(3, "C", emails = setOf("b@x.pl"))), emptySet())
        assertEquals(listOf(1L, 2L, 3L), chain.single().ids)
        val office = (1L..6L).map { c(it, "Osoba $it", setOf("48221234567")) }
        assertTrue(Duplicates.find(office, emptySet()).isEmpty())
    }

    @Test
    fun keptSeparatePairsAreNotSuggested() {
        val contacts = listOf(c(1, "A", setOf("48601234567")), c(2, "B", setOf("48601234567")))
        assertTrue(Duplicates.find(contacts, setOf(2L to 1L)).isEmpty())
    }

    @Test
    fun shortNumbersSayNothing() {
        assertTrue(Duplicates.find(listOf(c(1, "Pizza", setOf("112")), c(2, "Pogotowie", setOf("112"))), emptySet()).isEmpty())
    }
}
