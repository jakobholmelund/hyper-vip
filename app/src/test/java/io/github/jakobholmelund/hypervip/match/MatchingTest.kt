package io.github.jakobholmelund.hypervip.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingTest {
    @Test
    fun normalizeKeepsDigitsAndLeadingPlus() {
        assertEquals("+4512345678", PhoneNumbers.normalize("+45 12 34 56 78"))
        assertEquals("+4512345678", PhoneNumbers.normalize("0045 12-34-56-78"))
        assertEquals("6505551234", PhoneNumbers.normalize("(650) 555-1234"))
        assertEquals("", PhoneNumbers.normalize(null))
    }

    @Test
    fun matchesSameNumberInDifferentFormats() {
        assertTrue(PhoneNumbers.matches("+45 12 34 56 78", "12345678"))
        assertTrue(PhoneNumbers.matches("0045 12345678", "+4512345678"))
        assertTrue(PhoneNumbers.matches("+44 7700 900123", "07700 900123"))
        assertTrue(PhoneNumbers.matches("+1 650 555 1234", "650-555-1234"))
        assertTrue(PhoneNumbers.matches("+39 06 1234567", "06 1234567"))
        assertTrue(PhoneNumbers.matches("1234", "1234"))
    }

    @Test
    fun rejectsDifferentNumbers() {
        assertFalse(PhoneNumbers.matches("+4512345678", "+4612345678"))
        assertFalse(PhoneNumbers.matches("12345678", "12345679"))
        assertFalse(PhoneNumbers.matches("1234", "51234"))
        assertFalse(PhoneNumbers.matches("", "12345678"))
        assertFalse(PhoneNumbers.matches(null, null))
    }

    @Test
    fun recognisesNumbersVersusNames() {
        assertTrue(PhoneNumbers.looksLikeNumber("+45 12 34 56 78"))
        assertTrue(PhoneNumbers.looksLikeNumber("‪+45 12 34 56 78‬"))
        assertFalse(PhoneNumbers.looksLikeNumber("Mom"))
        assertFalse(PhoneNumbers.looksLikeNumber("Flat 3B"))
    }

    @Test
    fun namesMatchIgnoringCaseSpacingAndDirectionMarks() {
        assertTrue(Names.matches("Mom", "mom"))
        assertTrue(Names.matches("  Anna   Jensen ", "Anna Jensen"))
        assertTrue(Names.matches("‎Anna Jensen‎", "Anna Jensen"))
        assertFalse(Names.matches("Anna", "Anna Jensen"))
        assertFalse(Names.matches("", ""))
    }
}
