package com.pickupcode.app.extractor

import com.pickupcode.app.ocr.OCREngine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CodeExtractorTest {

    private fun line(text: String) = OCREngine.TextLine(text = text, boundingBox = null, confidence = null)

    // ── normalizeText：全角→半角 + 符号归一 ──
    @Test
    @DisplayName("全角数字/减号转半角")
    fun normalize_fullwidth() {
        assertEquals("123-1", CodeExtractor.normalizeText("１２３－１"))
        assertEquals("1-6-5020", CodeExtractor.normalizeText("１－６－５０２０"))
    }

    @Test
    @DisplayName("全角括号/逗号归一 + 空白压缩")
    fun normalize_punct() {
        assertEquals("(1-6-5020)", CodeExtractor.normalizeText("（１－６－５０２０）"))
        assertEquals("a b c", CodeExtractor.normalizeText("a\tb\nc"))
    }

    @Test
    @DisplayName("波浪号/长破折号归一为连字符")
    fun normalize_dash() {
        assertEquals("1-2", CodeExtractor.normalizeText("1～2"))
        assertEquals("1-2", CodeExtractor.normalizeText("1—2"))
    }

    // ── isFinancialNoise：金融词拦截 ──
    @Test
    @DisplayName("金融词且无快递词 → 判定噪音")
    fun finance_noise() {
        assertTrue(CodeExtractor.isFinancialNoise("您的余额为 100 元"))
        assertTrue(CodeExtractor.isFinancialNoise("支付宝到账 50 元"))
        assertTrue(CodeExtractor.isFinancialNoise("信用卡还款提醒"))
    }

    @Test
    @DisplayName("金融词 + 快递词 → 放行")
    fun finance_with_express() {
        assertFalse(CodeExtractor.isFinancialNoise("您的快递已到，取件码 1-6-5020"))
        assertFalse(CodeExtractor.isFinancialNoise("包裹驿站取件通知，微信支付已扣"))
    }

    @Test
    @DisplayName("空文本 → 非噪音")
    fun finance_blank() {
        assertFalse(CodeExtractor.isFinancialNoise(""))
    }

    // ── extract：核心提取 ──
    @Test
    @DisplayName("提取前缀取件码")
    fun extract_parcel() {
        val r = CodeExtractor.extract(listOf(line("【菜鸟驿站】您的取件码 1-6-5020 已到")))
        assertTrue(r.isNotEmpty(), "应提取出取件码")
        assertEquals("1-6-5020", r.first().code)
        assertEquals(CodeExtractor.CodeType.pickup_parcel, r.first().type)
    }

    @Test
    @DisplayName("提取取餐码")
    fun extract_food() {
        val r = CodeExtractor.extract(listOf(line("您的取餐码 A-3-315 请取餐")))
        assertTrue(r.isNotEmpty(), "应提取出取餐码")
        assertEquals("A-3-315", r.first().code)
        assertEquals(CodeExtractor.CodeType.pickup_food, r.first().type)
    }

    @Test
    @DisplayName("无码文本返回空列表")
    fun extract_none() {
        assertTrue(CodeExtractor.extract(emptyList()).isEmpty())
        assertTrue(CodeExtractor.extract(listOf(line("这是一段没有码的文字"))).isEmpty())
    }

    // ── 边界回归：Android(ICU) 的 \b 把中文当词字符，码值紧贴中文时 \b 失效漏抓 ──
    // 桌面 JVM 的 \b 是 ASCII 语义，此 bug 在单测环境复现不出（设备必现），
    // 这些用例锁定「码值紧贴中文仍须提取」的行为要求，防止边界写法被改回 \b。
    @Test
    @DisplayName("6位纯数字码紧贴中文（欢猫智柜 749019复制）应被提取")
    fun extract_six_digit_adjacent_chinese() {
        val r = CodeExtractor.extract(listOf(
            line("韵达快递435316307329341 取件码"),
            line("育新路与李庄街西李庄社区卫生所对面3号柜欢猫智柜"),
            line("749019复制 您的快件己暂存至周口市育新路3号柜")
        ))
        assertTrue(r.any { it.code == "749019" && it.type == CodeExtractor.CodeType.pickup_parcel },
            "应提取出 749019(pickup_parcel)，实际: ${r.map { "${it.code}(${it.type})" }}")
    }

    @Test
    @DisplayName("字母段式码紧贴中文（D-06003取件）应被提取")
    fun extract_letter_dash_adjacent_chinese() {
        val r = CodeExtractor.extract(listOf(line("【菜鸟驿站】您的快件在快递柜，凭D-06003取件")))
        assertTrue(r.any { it.code == "D-06003" }, "应提取出 D-06003，实际: ${r.map { it.code }}")
    }

    @Test
    @DisplayName("三段式码紧贴中文（1-6-5020到）应被提取")
    fun extract_three_seg_adjacent_chinese() {
        val r = CodeExtractor.extract(listOf(line("凭1-6-5020到1号柜取件")))
        assertTrue(r.any { it.code == "1-6-5020" }, "应提取出 1-6-5020，实际: ${r.map { it.code }}")
    }

    @Test
    @DisplayName("长运单号（15位纯数字）不被当作取件码")
    fun extract_rejects_long_tracking_number() {
        val r = CodeExtractor.extract(listOf(line("韵达快递435316307329341 您的快件已到")))
        assertTrue(r.none { it.code == "435316307329341" }, "运单号不应被识别为取件码")
    }
}
