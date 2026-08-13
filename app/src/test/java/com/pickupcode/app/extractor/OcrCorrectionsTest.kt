package com.pickupcode.app.extractor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OcrCorrectionsTest {

    @Test
    fun `apply 替换已知 OCR 误读`() {
        assertEquals("兔喜生活", OcrCorrections.apply("免喜生活"))
    }

    @Test
    fun `apply 无命中原样返回`() {
        assertEquals("菜鸟驿站 1-2-3456", OcrCorrections.apply("菜鸟驿站 1-2-3456"))
    }

    @Test
    fun `apply 空串安全`() {
        assertEquals("", OcrCorrections.apply(""))
    }
}
