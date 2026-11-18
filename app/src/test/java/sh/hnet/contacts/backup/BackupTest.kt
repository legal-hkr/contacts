package sh.hnet.contacts.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val file = BackupFile(
        appVersion = "0.1.0",
        appVersionCode = 1,
        createdAt = "2026-11-17T10:00:00Z",
        settings = BackupSettings(sortBy = "last", nameOrder = "last_first", companyLine = true),
        accounts = listOf(BackupAccount("at.bitfire.davdroid", "jan@cloud.hnet.sh", true), BackupAccount("org.thoughtcrime.securesms", "Signal", false), BackupAccount("", "", true)),
    )

    @Test
    fun roundTrips() {
        val check = Backup.check(Backup.encode(file))
        assertEquals(BackupCheck.Valid(file), check)
    }

    @Test
    fun holdsNothingTakenFromContacts() {
        val text = Backup.encode(file)
        for (field in listOf("lookup", "contact\"", "duplicate", "exclusion", "pairs")) assertTrue(field, field !in text)
    }

    @Test
    fun rejectsOtherFilesAndNewerFormats() {
        assertTrue(Backup.check("not json") is BackupCheck.NotOurs)
        // A version that isn't a number is refused, not thrown at the caller
        assertTrue(Backup.check("""{"format":"sh.hnet.contacts.backup","formatVersion":{}}""") is BackupCheck.NotOurs)
        assertTrue(Backup.check("""{"format":"sh.hnet.calendar.backup","formatVersion":1}""") is BackupCheck.NotOurs)
        assertEquals(BackupCheck.Newer(2), Backup.check("""{"format":"sh.hnet.contacts.backup","formatVersion":2}"""))
    }

    @Test
    fun readsAnOlderFileWithoutTheSettings() {
        val check = Backup.check("""{"format":"sh.hnet.contacts.backup","formatVersion":1,"appVersion":"0.1.0","appVersionCode":1,"createdAt":"x","extra":1}""")
        assertEquals(BackupSettings(), (check as BackupCheck.Valid).backup.settings)
    }
}
