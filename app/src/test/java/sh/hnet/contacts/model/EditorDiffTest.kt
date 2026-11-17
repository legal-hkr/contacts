package sh.hnet.contacts.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorDiffTest {
    private fun phone(key: Long, number: String, vararg origins: Origin, readOnly: Boolean = false, default: Boolean = false) =
        FormValue(key, EditKind.PHONE, mapOf("data1" to number), type = 2, isDefault = default, origins = origins.toList(), readOnly = readOnly)

    @Test
    fun anUntouchedFormWritesNothing() {
        val original = listOf(phone(1, "601 234 567", Origin(10, 100)))
        assertTrue(EditorDiff.writes(original, original, 10).isEmpty())
    }

    @Test
    fun trailingSpacesAreNoChange() {
        val original = listOf(phone(1, "601 234 567", Origin(10, 100)))
        assertTrue(EditorDiff.writes(original, listOf(phone(1, "601 234 567 ", Origin(10, 100))), 10).isEmpty())
    }

    @Test
    fun aChangedValueOfTwoEntriesIsWrittenToBoth() {
        val original = listOf(phone(1, "601 234 567", Origin(10, 100), Origin(11, 110)))
        val edited = listOf(phone(1, "601 234 568", Origin(10, 100), Origin(11, 110)))
        val writes = EditorDiff.writes(original, edited, 10)
        assertEquals(listOf(100L, 110L), writes.map { (it as Write.Update).dataId })
    }

    @Test
    fun aRemovedValueIsDeletedEverywhereAndABlankOneCountsAsRemoved() {
        val original = listOf(phone(1, "601", Origin(10, 100), Origin(11, 110)), phone(2, "602", Origin(10, 101)))
        val edited = listOf(phone(2, " ", Origin(10, 101)))
        val writes = EditorDiff.writes(original, edited, 10)
        assertEquals(setOf(100L, 110L, 101L), writes.map { (it as Write.Delete).dataId }.toSet())
    }

    @Test
    fun aNewValueGoesToTheTarget() {
        val writes = EditorDiff.writes(emptyList(), listOf(phone(5, "603")), 11)
        assertEquals(listOf(Write.Insert(11, phone(5, "603"))), writes)
    }

    @Test
    fun readOnlyValuesAreNeverWritten() {
        val original = listOf(phone(1, "601", Origin(20, 200), readOnly = true))
        assertTrue(EditorDiff.writes(original, listOf(phone(1, "609", Origin(20, 200), readOnly = true)), 10).isEmpty())
        assertTrue(EditorDiff.writes(original, emptyList(), 10).isEmpty())
    }

    @Test
    fun makingANumberTheDefaultIsAChange() {
        val original = listOf(phone(1, "601", Origin(10, 100)))
        val writes = EditorDiff.writes(original, listOf(phone(1, "601", Origin(10, 100), default = true)), 10)
        assertEquals(1, writes.size)
        assertTrue((writes[0] as Write.Update).value.isDefault)
    }

    @Test
    fun aContactDeletedMeanwhileIsCreatedAgainWhole() {
        val edited = listOf(phone(1, "601", Origin(10, 100)), FormValue(2, EditKind.NOTE, mapOf("data1" to "")))
        val writes = EditorDiff.recreate(edited)
        assertEquals(1, writes.size)
        assertEquals(null, (writes[0] as Write.Insert).rawContactId)
    }

    @Test
    fun anEmptyContactCantBeSaved() {
        assertFalse(EditorDiff.hasContent(listOf(FormValue(1, EditKind.NAME, mapOf("data2" to " "))), photo = false))
        assertTrue(EditorDiff.hasContent(emptyList(), photo = true))
    }
}
