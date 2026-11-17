package sh.hnet.contacts.model

import java.time.LocalDate
import java.time.MonthDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactDetailsTest {
    private fun phone(number: String, raw: Long, type: Int = 2, default: Boolean = false) = Detail(DetailKind.PHONE, number, type, null, default, raw, raw * 100 + number.length)

    /** As the app compares numbers in Poland: digits in international form. */
    private val polish: (String) -> String = { n -> Details.digits(n).let { if (n.trim().startsWith("+")) it else "48$it" } }

    @Test
    fun theSameNumberInTwoEntriesShowsOnceFromTheFirstEntry() {
        val values = listOf(phone("601 234 567", raw = 2, type = 1), phone("+48 601 234 567", raw = 1, type = 2), phone("602 000 000", raw = 2))
        val shown = Details.distinct(values, sourceOrder = listOf(1, 2), numberKey = polish)
        assertEquals(listOf("+48 601 234 567", "602 000 000"), shown.map { it.value })
        assertEquals(2, shown[0].type)
    }

    @Test
    fun aDefaultCopyMakesTheShownValueTheDefault() {
        val values = listOf(phone("601234567", raw = 1), phone("601 234 567", raw = 2, default = true))
        val shown = Details.distinct(values, listOf(1, 2), polish)
        assertEquals(1, shown.size)
        assertEquals(true, shown[0].isDefault)
        assertEquals(1L, shown[0].rawContactId)
    }

    @Test
    fun emailsAreEqualWhateverTheCase() {
        val values = listOf(Detail(DetailKind.EMAIL, "Jan@Cloud.hnet.sh", 1, null, false, 1, 1), Detail(DetailKind.EMAIL, "jan@cloud.hnet.sh", 2, null, false, 2, 2))
        assertEquals(1, Details.distinct(values, listOf(1, 2), polish).size)
    }

    @Test
    fun datesOfDifferentTypesStay() {
        val values = listOf(Detail(DetailKind.DATE, "1990-03-12", 3, null, false, 1, 1), Detail(DetailKind.DATE, "1990-03-12", 1, null, false, 1, 2))
        assertEquals(2, Details.distinct(values, listOf(1), polish).size)
    }

    @Test
    fun datesParseWithAndWithoutYear() {
        assertEquals(ContactDate.Full(LocalDate.of(2016, 6, 4)), ContactDate.parse("2016-06-04"))
        assertEquals(ContactDate.NoYear(MonthDay.of(3, 12)), ContactDate.parse("--03-12"))
        assertEquals(ContactDate.Full(LocalDate.of(2016, 6, 4)), ContactDate.parse("20160604"))
        assertEquals(ContactDate.Full(LocalDate.of(2016, 6, 4)), ContactDate.parse("2016-06-04T00:00:00.000Z"))
        assertNull(ContactDate.parse("jutro"))
        assertNull(ContactDate.parse("2016-13-40"))
    }
}
