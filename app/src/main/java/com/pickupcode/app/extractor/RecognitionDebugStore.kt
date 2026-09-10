package com.pickupcode.app.extractor

import android.content.Context
import com.pickupcode.app.ocr.OCREngine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 识别调试快照（蓝本 BiuLand DebugReport，2026-08-13）：内存保存最近一次识别的完整过程数据。
 * DEBUG 构建下由 CodeExtractor/AddressExtractor 写入，设置页「识别调试」入口查看。
 * 用途：识别出错时打开面板截图，直接看到 OCR 行/候选/得分——免复现排错。
 *
 * 2026-09 新增 [exportFixture]：把最近一次识别导出成 corpus 回归语料（行文本格式，见下）。
 * 真机遇到误报 → 面板「导出语料」→ 人工核对 expect 段 → 丢进
 * `app/src/test/resources/corpus/` → 变成永久回归用例（CorpusRegressionTest 消费）。
 *
 * ## 语料格式（每行一条指令，# 为注释/元数据）
 * ```
 * # id: cainiao-3seg-ping
 * # source: screen
 * # screen: 2400
 * L <x|-> <y|-> <w|-> <h|-> <conf|-> <行文本...>
 * E code <pickup_parcel|pickup_food|coupon> <码值>
 * E forbid <不应被识别为码的串>
 * E address <期望地址片段>
 * E cabinet <期望柜号>
 * E from <期望命中步骤，如 S0-label>
 * ```
 * `L` 行的坐标可为 `-`（分享/短信路径无 boundingBox）；`E` 段顺序无关。
 */
object RecognitionDebugStore {

    /** 单次识别快照：OCR 行 + 码候选 + 最终结果 + 地址结果。 */
    data class Snapshot(
        val timeMs: Long,
        val source: String,
        val screenHeight: Int,
        val lines: List<LineInfo>,
        val candidates: List<CandidateInfo>,
        val finalResults: List<CandidateInfo>,
        val address: AddressInfo?,
        val allText: String
    )

    data class LineInfo(
        val index: Int,
        val text: String,
        val confidence: Float?,
        val box: String,          // 展示用："(x=..,y=..,w=..,h=..)" 或 "(no-box)"
        val left: Int? = null,    // 语料导出用（可解析的数值坐标）
        val top: Int? = null,
        val width: Int? = null,
        val height: Int? = null
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

    /**
     * 记录本次识别的 OCR 行与候选。**不继承上一轮的地址**——地址由 [captureAddress]
     * 在 AddressExtractor 跑完后单独写入（此前继承旧值会让调试面板显示上一轮地址）。
     */
    fun capture(
        lines: List<OCREngine.TextLine>,
        candidates: List<CandidateInfo>,
        allText: String,
        source: String,
        screenHeight: Int = 0,
        finalResults: List<CandidateInfo> = emptyList()
    ) {
        val lineInfos = lines.mapIndexed { idx, tl ->
            val bb = tl.boundingBox
            LineInfo(
                index = idx,
                text = tl.text,
                confidence = tl.confidence,
                box = if (bb != null) "(x=${bb.left},y=${bb.top},w=${bb.width()},h=${bb.height()})" else "(no-box)",
                left = bb?.left,
                top = bb?.top,
                width = bb?.width(),
                height = bb?.height()
            )
        }
        snapshot = Snapshot(
            timeMs = System.currentTimeMillis(),
            source = source,
            screenHeight = screenHeight,
            lines = lineInfos,
            candidates = candidates,
            finalResults = finalResults,
            address = null,
            allText = allText.take(2000)
        )
    }

    fun captureAddress(address: AddressInfo) {
        val cur = snapshot ?: return
        snapshot = cur.copy(address = address)
    }

    fun latest(): Snapshot? = snapshot

    fun clear() { snapshot = null }

    // ---------------------------------------------------------------
    // 语料导出
    // ---------------------------------------------------------------

    /** 由快照生成 corpus 语料文本；无快照返回 null。纯函数，可直接单测。 */
    fun exportFixture(): String? {
        val s = snapshot ?: return null
        val sb = StringBuilder()
        sb.append("# id: ").append(fixtureId(s)).append('\n')
        sb.append("# source: ").append(s.source).append('\n')
        sb.append("# screen: ").append(s.screenHeight).append('\n')
        sb.append("# generated: ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(s.timeMs)))
            .append('\n')
        sb.append("# NOTE: E 段是「当前行为」的自动快照，请人工核对后再提交为回归基线。\n")
        sb.append('\n')
        for (l in s.lines) {
            // 空文本行无信息量，且会让语料解析器报错（L 行需 7 段）——跳过
            if (l.text.isBlank()) continue
            sb.append("L ")
                .append(l.left ?: "-").append(' ')
                .append(l.top ?: "-").append(' ')
                .append(l.width ?: "-").append(' ')
                .append(l.height ?: "-").append(' ')
                .append(l.confidence?.let { String.format(Locale.US, "%.2f", it) } ?: "-")
                .append(' ')
                .append(l.text)
                .append('\n')
        }
        sb.append('\n')
        for (r in s.finalResults) {
            sb.append("E code ").append(r.type).append(' ').append(r.code).append('\n')
        }
        s.address?.let { a ->
            if (a.fullAddress.isNotBlank()) sb.append("E address ").append(a.fullAddress).append('\n')
            if (!a.cabinet.isNullOrBlank()) sb.append("E cabinet ").append(a.cabinet).append('\n')
            if (a.from.isNotBlank() && a.from != "none") sb.append("E from ").append(a.from).append('\n')
        }
        return sb.toString()
    }

    /** 导出语料文本到 cacheDir（供 FileProvider 分享）；无快照返回 null。 */
    fun exportFixtureFile(context: Context): File? {
        val text = exportFixture() ?: return null
        return try {
            val dir = File(context.cacheDir, "corpus")
            dir.mkdirs()
            File(dir, "${fixtureId(snapshot!!)}.txt").apply { writeText(text) }
        } catch (_: Exception) {
            null
        }
    }

    private fun fixtureId(s: Snapshot): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(s.timeMs))
        val src = s.source.substringBefore(' ').substringBefore(':').ifBlank { "scan" }
        return "$src-$stamp"
    }
}
