package com.pickupcode.app.extractor

import com.pickupcode.app.ocr.OCREngine

/**
 * 识别调试快照（蓝本 BiuLand DebugReport，2026-08-13）：内存保存最近一次识别的完整过程数据。
 * DEBUG 构建下由 CodeExtractor/AddressExtractor 写入，设置页「识别调试」入口查看。
 * 用途：识别出错时打开面板截图，直接看到 OCR 行/候选/得分——免复现排错。
 */
object RecognitionDebugStore {

    /** 单次识别快照：OCR 行 + 码候选 + 地址结果。 */
    data class Snapshot(
        val timeMs: Long,
        val source: String,
        val lines: List<LineInfo>,
        val candidates: List<CandidateInfo>,
        val address: AddressInfo?,
        val allText: String
    )

    data class LineInfo(
        val index: Int,
        val text: String,
        val confidence: Float?,
        val box: String  // "(x=..,y=..,w=..,h=..)" 或 "(no-box)"
    )

    data class CandidateInfo(
        val code: String,
        val score: Float,
        val type: String,
        val source: String,
        val lineIndex: Int,
        val context: String
    )

    data class AddressInfo(
        val fullAddress: String,
        val station: String,
        val cabinet: String?,
        val from: String
    )

    @Volatile
    private var snapshot: Snapshot? = null

    fun capture(lines: List<OCREngine.TextLine>, candidates: List<CandidateInfo>, allText: String, source: String) {
        val lineInfos = lines.mapIndexed { idx, tl ->
            val bb = tl.boundingBox
            LineInfo(
                index = idx,
                text = tl.text,
                confidence = tl.confidence,
                box = if (bb != null) "(x=${bb.left},y=${bb.top},w=${bb.width()},h=${bb.height()})" else "(no-box)"
            )
        }
        snapshot = Snapshot(
            timeMs = System.currentTimeMillis(),
            source = source,
            lines = lineInfos,
            candidates = candidates,
            address = snapshot?.address,  // 地址由 AddressExtractor 单独 captureAddress 更新
            allText = allText.take(2000)
        )
    }

    fun captureAddress(address: AddressInfo) {
        val cur = snapshot ?: return
        snapshot = cur.copy(address = address)
    }

    fun latest(): Snapshot? = snapshot

    fun clear() { snapshot = null }
}
