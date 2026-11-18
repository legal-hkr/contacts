package sh.hnet.contacts.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UploadCheckTest {
    private val clean = EntryState(dirty = false, deleted = false)
    private val dirty = EntryState(dirty = true, deleted = false)
    private val deleted = EntryState(dirty = true, deleted = true)

    @Test
    fun anEditWaitsWhileTheEntryIsDirty() {
        assertEquals(UploadOutcome.PENDING, UploadCheck.edit(mapOf(1L to dirty), listOf(1L), setOf(10L), setOf(10L)) { true })
    }

    @Test
    fun anEditSentKeepsItsRowsAndIsNotComparedAgain() {
        var asked = false
        assertEquals(UploadOutcome.CONFIRMED, UploadCheck.edit(mapOf(1L to clean), listOf(1L), setOf(10L, 11L), setOf(10L, 11L)) { asked = true; false })
        assertFalse(asked)
    }

    @Test
    fun replacedRowsAreRejectedUnlessTheyHoldWhatWasSaved() {
        // DAVx⁵ with a read-only book: the server's version written back, the new number gone
        assertEquals(UploadOutcome.REJECTED, UploadCheck.edit(mapOf(1L to clean), listOf(1L), setOf(10L, 11L), setOf(20L)) { false })
        // The server echoed the change: new rows, the same content
        assertEquals(UploadOutcome.CONFIRMED, UploadCheck.edit(mapOf(1L to clean), listOf(1L), setOf(10L, 11L), setOf(20L, 21L)) { true })
    }

    @Test
    fun aNewContactTheAppRemovedIsRejected() {
        assertEquals(UploadOutcome.REJECTED, UploadCheck.edit(emptyMap(), listOf(1L), setOf(10L), emptySet()) { true })
        assertEquals(UploadOutcome.REJECTED, UploadCheck.edit(mapOf(1L to deleted), listOf(1L), setOf(10L), setOf(10L)) { true })
    }

    @Test
    fun aDeleteIsSentWhenTheEntriesAreGoneAndRejectedWhenOneCameBack() {
        assertEquals(UploadOutcome.PENDING, UploadCheck.deletion(mapOf(1L to deleted, 2L to deleted), listOf(1L, 2L)))
        assertEquals(UploadOutcome.CONFIRMED, UploadCheck.deletion(emptyMap(), listOf(1L, 2L)))
        assertEquals(UploadOutcome.PENDING, UploadCheck.deletion(mapOf(2L to deleted), listOf(1L, 2L)))
        assertEquals(UploadOutcome.REJECTED, UploadCheck.deletion(mapOf(2L to clean), listOf(1L, 2L)))
    }
}
