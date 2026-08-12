package com.pickupcode.app.extractor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * Regression tests for AddressExtractor.
 * dedupeRepeated tested via reflection; extractAddress tested through public API.
 */
class AddressExtractorTest {

    // ---------------------------------------------------------------
    // dedupeRepeated — was broken for multi-char CJK (HIGH bug, fixed v1.0.9)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("collpases 3+ repeated multi-char CJK unit")
    fun dedupeRepeated_collapsesMultiCharCjk() {
        assertEquals("育新路北段", invokeDedupeRepeated("育新路育新路育新路北段"))
        assertEquals("申通快递", invokeDedupeRepeated("申通快递申通快递申通快递"))
    }

    @Test
    @DisplayName("4-char unit repeated 3x is collapsed")
    fun dedupeRepeated_4charUnit() {
        assertEquals("申通快递取", invokeDedupeRepeated("申通快递申通快递申通快递取"))
    }

    @Test
    @DisplayName("2-char unit repeated 3x is collapsed")
    fun dedupeRepeated_2charUnit() {
        assertEquals("快递", invokeDedupeRepeated("快递快递快递"))
    }

    @Test
    @DisplayName("single-char repeat NOT collapsed (algorithm starts at len=2)")
    fun dedupeRepeated_singleChar() {
        assertEquals("路路路", invokeDedupeRepeated("路路路"))
    }

    @Test
    @DisplayName("double repeat preserved (not 3+)")
    fun dedupeRepeated_keepsDoubleRepeat() {
        assertEquals("育新路路", invokeDedupeRepeated("育新路路"))
    }

    @Test
    @DisplayName("empty string returns empty")
    fun dedupeRepeated_empty() {
        assertEquals("", invokeDedupeRepeated(""))
    }

    @Test
    @DisplayName("no repetition returns original")
    fun dedupeRepeated_noRepetition() {
        assertEquals("育新路北段爱玛电动车旁边",
            invokeDedupeRepeated("育新路北段爱玛电动车旁边"))
    }

    private fun invokeDedupeRepeated(s: String): String {
        val method = AddressExtractor::class.java.getDeclaredMethod("dedupeRepeated", String::class.java)
        method.isAccessible = true
        return method.invoke(AddressExtractor, s) as String
    }

    // ---------------------------------------------------------------
    // extractAddress — integration tests
    // ---------------------------------------------------------------

    @Test
    @DisplayName("finds address from 到…取件 pattern (S6)")
    fun extractAddress_afterToPattern() {
        val text = "凭281849到育新路北段爱玛电动车旁边2号柜取您的快递"
        val addr = extract(text)
        assertTrue(addr.contains("育新路"), "S6 should find address, got: $addr")
    }

    @Test
    @DisplayName("finds address from 已放至 pattern (S5)")
    fun extractAddress_placedPhrase() {
        val text = "已放至育新路北段菜鸟驿站，凭码取件"
        val addr = extract(text)
        assertTrue(addr.contains("育新路"), "S5 should find address, got: $addr")
    }

    @Test
    @DisplayName("finds address from explicit label (S0)")
    fun extractAddress_explicitLabel() {
        val text = "取件地址: 育新路北段爱玛电动车旁边"
        val addr = extract(text)
        assertTrue(addr.contains("育新路"), "S0 should find address, got: $addr")
    }

    @Test
    @DisplayName("fallback for line with road indicator (S10)")
    fun extractAddress_s10fallback() {
        val text = "育新路北段爱玛电动车旁边"
        val addr = extract(text)
        assertTrue(addr.contains("育新路"), "S10 fallback should find, got: $addr")
    }

    @Test
    @DisplayName("returns empty for text with no address indicators")
    fun extractAddress_noAddressIndicators() {
        val text = "请取件"
        val addr = extract(text)
        assertTrue(addr.isEmpty() || !addr.any { it in '\u4e00'..'\u9fff' },
            "Should return empty or non-CJK for non-address, got: $addr")
    }

    private fun extract(text: String): String {
        val lines = text.lines()
            .mapIndexed { i, line ->
                com.pickupcode.app.ocr.OCREngine.TextLine(
                    text = line.trim(),
                    boundingBox = android.graphics.Rect(0, i * 30, 500, (i + 1) * 30),
                    confidence = 1.0f
                )
            }
            .filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return ""
        val allText = lines.joinToString(" ") { it.text }
        return AddressExtractor.extractAddress(lines, allText)
    }
}
