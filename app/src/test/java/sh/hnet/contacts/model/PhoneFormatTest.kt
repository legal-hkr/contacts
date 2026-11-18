package sh.hnet.contacts.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneFormatTest {
    @Test
    fun polishMobilesAndLandlinesHaveTheirOwnFormats() {
        assertEquals("+48 600 123 456", PhoneFormat.withCountry("600123456", "PL"))
        assertEquals("+48 22 123 45 67", PhoneFormat.withCountry("221234567", "PL"))
    }

    @Test
    fun aCodeAlreadyThereIsReplacedAndATrunkPrefixDropped() {
        assertEquals("+48 600 123 456", PhoneFormat.withCountry("+49 600123456", "PL"))
        assertEquals("+48 600 123 456", PhoneFormat.withCountry("0049600123456", "PL"))
        assertEquals("+44 7700 900123", PhoneFormat.withCountry("07700 900123", "GB"))
        assertEquals("+49 1512 3456789", PhoneFormat.withCountry("015123456789", "DE"))
    }

    @Test
    fun theCountryComesFromTheCode() {
        assertEquals("PL", PhoneFormat.regionOf("+48 600 123 456"))
        assertNull(PhoneFormat.regionOf("600 123 456"))
        assertNull(PhoneFormat.regionOf("112"))
        // A shared code: the whole number decides, else the user's choice, else the code's main country
        assertEquals("CA", PhoneFormat.regionOf("+1 416 979 1234"))
        assertEquals("CA", PhoneFormat.regionOf("+1 4", hint = "CA"))
        assertEquals("US", PhoneFormat.regionOf("+1 4"))
        assertNull(PhoneFormat.regionOf("+800 1234 5678"))
    }

    @Test
    fun withoutCountryTheNationalFormStays() {
        assertEquals("600 123 456", PhoneFormat.withoutCountry("+48 600 123 456"))
        assertEquals("01512 3456789", PhoneFormat.withoutCountry("+49 1512 3456789"))
        assertEquals("112", PhoneFormat.withoutCountry("112"))
    }

    @Test
    fun typingFormatsAndKeepsTheCursorAfterItsDigit() {
        // A digit typed at the end
        assertEquals("+48 600 123 4" to 13, PhoneFormat.typed("+48 600 123", "+48 600 1234", 12))
        // A deleted space takes the digit before it
        assertEquals("+48 600 124" to 10, PhoneFormat.typed("+48 600 123 4", "+48 600 1234", 11))
        // Without a code, or with letters: as typed
        assertEquals("112" to 3, PhoneFormat.typed("11", "112", 3))
        assertEquals("+48 600 w 1" to 11, PhoneFormat.typed("+48 600 w", "+48 600 w 1", 11))
    }

    @Test
    fun theListHasEveryCountryInTheAppsLanguage() {
        val countries = PhoneFormat.countries(Locale.forLanguageTag("pl"))
        assertTrue(countries.any { it.region == "PL" && it.code == 48 && it.name == "Polska" && it.flag == "🇵🇱" })
    }

    @Test
    fun onlyTheNumberItselfGetsTheCode() {
        // An extension, pauses, a second number after "/" and letters stay as they were
        assertEquals("+48 600 123 456 ext. 12", PhoneFormat.withCountry("600 123 456 ext. 12", "PL"))
        assertEquals("+48 600 123 456,,12", PhoneFormat.withCountry("600123456,,12", "PL"))
        assertEquals("+48 22 123 45 67 / 68", PhoneFormat.withCountry("22 123 45 67 / 68", "PL"))
        // "(0)" after a code isn't dialed with it
        assertEquals("+44 20 7946 0958", PhoneFormat.withCountry("+44 (0) 20 7946 0958", "GB"))
        assertEquals("600 123 456,,12", PhoneFormat.withoutCountry("+48 600123456,,12"))
    }

    @Test
    fun aNumberWithAPartAfterASlashIsLeftAsTyped() {
        assertEquals("+48 22 123 45 67 / 689" to 21, PhoneFormat.typed("+48 22 123 45 67 / 68", "+48 22 123 45 67 / 689", 21))
    }

    @Test
    fun onlyABackspacedSeparatorTakesItsDigit() {
        // The whole number pasted again without spaces keeps every digit
        assertEquals("+48 600 123 456", PhoneFormat.typed("+48 600 123 456", "+48600123456", 12).first)
        // One of two spaces deleted: nothing else goes
        assertEquals("+48 600", PhoneFormat.typed("+48  600", "+48 600", 4).first)
    }

    @Test
    fun aCodeOfNoCountryCanBeTakenOff() {
        assertTrue(PhoneFormat.hasCode("+800 1234 5678"))
        assertFalse(PhoneFormat.hasCode("112"))
    }

    @Test
    fun anEmptyFieldGetsTheCodeAlone() {
        assertEquals("+48", PhoneFormat.withCountry("", "PL").trim())
        assertEquals("+1", PhoneFormat.withCountry("", "CA").trim())
    }
}
