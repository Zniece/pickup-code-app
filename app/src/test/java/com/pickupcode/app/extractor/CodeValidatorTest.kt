package com.pickupcode.app.extractor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

class CodeValidatorTest {

    @Test
    @DisplayName("valid three-segment (1-6-5020)")
    fun valid_threeSegment() {
        assertTrue(CodeValidator.isValidPickupCode("1-6-5020"))
        assertTrue(CodeValidator.isValidPickupCode("99-12-123456"))
    }

    @Test
    @DisplayName("valid letter-prefix (A-3-315)")
    fun valid_letterTwoSegment() {
        assertTrue(CodeValidator.isValidPickupCode("A-3-315"))
    }

    @Test
    @DisplayName("valid long number")
    fun valid_longNumber() {
        assertTrue(CodeValidator.isValidPickupCode("281849"))
    }

    @Test
    @DisplayName("valid food code (A23)")
    fun valid_foodCode() {
        assertTrue(CodeValidator.isValidPickupCode("A23"))
    }

    @Test
    @DisplayName("rejects blank")
    fun invalid_blank() {
        assertFalse(CodeValidator.isValidPickupCode(""))
    }

    @Test
    @DisplayName("rejects too-long number")
    fun invalid_tooLong() {
        assertFalse(CodeValidator.isValidPickupCode("123456789012345"))
    }

    @Test
    @DisplayName("classifyFormat returns stable IDs")
    fun format_stable() {
        assertEquals("THREE_SEGMENT_PARCEL", CodeValidator.getPatternId("1-6-5020"))
        assertEquals("LONG_NUMBER_PARCEL", CodeValidator.getPatternId("281849"))
        assertEquals("PREFIXED_CODE", CodeValidator.getPatternId("ABC"))
    }
}
