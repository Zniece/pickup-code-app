package com.pickupcode.app.extractor

import com.pickupcode.app.learner.SavedAddressStore
import com.pickupcode.app.ocr.OCREngine

/**
 * 用户**预存地址**的匹配（管线最优先的一层）。
 *
 * 模型（用户 2026-09-15 的想法）：每条预存记录 = **完整名称** + **一个至多个关键词**。
 * OCR 文本里命中任一**关键词** → 该记录就采用它的**完整名称**（写进记录、在主页显示）。
 *
 * 刻意做成**纯函数 + 纯数据注入**（不碰 Context、不碰磁盘）：项目既有测试全在桌面 JVM 跑，
 * 一旦这里依赖 Android 就没法进语料回归。
 *
 * 规则（按优先级）：
 *  1. 匹配 key = `keywords`；**关键词为空时用 `fullName` 兜底**（否则只填名称的用户永远命中不了）；
 *  2. key 按长度降序——最长优先，避免「丰巢」抢在「丰巢快递柜(XX路店)」前面；
 *  3. key 长度 ≥ [MIN_KEY_LENGTH]（防单字误命中，与金融闸门被单字「柜」击穿是同类教训）；
 *  4. 按行号升序找第一个命中的行（与多码窗口"取最靠上"的既有策略一致，保守）；
 *  5. 命中后返回**完整名称**，而不是 OCR 原文。
 */
object SavedAddressMatcher {

    /** 参与匹配的最小 key 长度：单字在中文里太常见，必然误命中。 */
    const val MIN_KEY_LENGTH = 2

    data class Match(val fullName: String, val key: String, val lineIndex: Int)

    fun match(
        lines: List<OCREngine.TextLine>,
        saved: List<SavedAddressStore.MatcherView>
    ): Match? {
        if (lines.isEmpty() || saved.isEmpty()) return null

        // key → 完整名称；关键词优先，没有关键词时用完整名称兜底
        val keys: List<Pair<String, String>> = saved
            .flatMap { s ->
                val effective = s.keywords.ifEmpty { listOf(s.fullName) }
                effective.map { it.trim() to s.fullName }
            }
            .filter { (k, fullName) -> k.length >= MIN_KEY_LENGTH && fullName.isNotBlank() }
            .distinctBy { it.first }
            .sortedByDescending { it.first.length }

        if (keys.isEmpty()) return null

        lines.forEachIndexed { idx, line ->
            val text = line.text
            val hit = keys.firstOrNull { (k, _) -> text.contains(k, ignoreCase = true) }
            if (hit != null) return Match(hit.second, hit.first, idx)
        }
        return null
    }
}
