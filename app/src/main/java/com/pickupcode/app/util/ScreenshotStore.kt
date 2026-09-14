package com.pickupcode.app.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 识别截图的落盘治理：**孤儿清扫 + 硬保留期(TTL) + 目录总量上限**。
 *
 * 背景（代码检查报告 3-3 / 3-14）：
 *  - 无障碍截图以明文 JPEG 写在 `cacheDir/screenshots`，仅靠 `.nomedia`，**没有过期清理**；
 *  - 「删记录不清文件」造成大量孤儿文件（DedupScreen / TrashScreen / deleteOlderThan）；
 *  - 用户长期不清理时目录可无限增长。
 *
 * 设计：
 *  - 策略计算是**纯函数** [planDeletions]（不碰 Android API、不碰磁盘，可 JVM 单测）；
 *  - [sweep] 只做「列目录 → 调策略 → 执行删除」，并**返回被删路径**，
 *    由调用方（`CodeRepository.cleanScreenshots`）同步清空 DB 里已失效的引用，
 *    避免详情页指向不存在的文件。
 *
 * ⚠️ 有意**不做**加密：这些截图是识别过程的临时产物，其生命周期由 TTL + 记录删除收口；
 * 对临时文件加解密只会增加详情页读取成本，不解决"留太久"这个真问题。若将来要覆盖
 * 身份码等敏感界面，应走"根本不落盘"（见 identity-code-deeplink.md 的隐私红线），而不是加密。
 */
object ScreenshotStore {

    private const val TAG = "ScreenshotStore"

    /** 截图目录名（与 PickupCodeAccessibilityService.saveScreenshot 保持一致）。 */
    const val DIR_NAME = "screenshots"

    /**
     * 孤儿宽限期：刚写完但记录还没落库（或正在写）的文件不能立刻删。
     * 10 分钟足够覆盖"截图 → OCR → 入库"整条链路。
     */
    const val ORPHAN_GRACE_MS = 10 * 60 * 1000L

    /** 硬保留期：无论是否仍被记录引用，超过 30 天一律回收。 */
    const val TTL_MS = 30L * 24 * 60 * 60 * 1000

    /** 目录总量上限 50 MB；超出时按「最旧优先」回收。 */
    const val MAX_DIR_BYTES = 50L * 1024 * 1024

    /** 一个待治理的截图文件（纯数据，便于单测构造）。 */
    data class Entry(val path: String, val sizeBytes: Long, val lastModified: Long)

    /**
     * 计算应删除的文件路径（纯函数）。
     *
     * 规则与顺序：
     *  ① **孤儿**：不再被任何记录引用，且已过 [graceMs] 宽限期 → 删；
     *  ② **硬 TTL**：存在时间超过 [ttlMs] → 删（即使仍被引用，调用方随后清空该引用）；
     *  ③ **总量上限**：①② 删完后若仍超过 [maxTotalBytes]，按 lastModified 从旧到新继续删到阈值以内
     *     （可能命中仍被引用的文件——它们是"最旧的"，同样由调用方清引用）。
     */
    fun planDeletions(
        entries: List<Entry>,
        referenced: Set<String>,
        now: Long,
        ttlMs: Long = TTL_MS,
        graceMs: Long = ORPHAN_GRACE_MS,
        maxTotalBytes: Long = MAX_DIR_BYTES
    ): List<String> {
        val victims = LinkedHashSet<String>()

        // ① 孤儿（无引用 + 过宽限期）
        for (e in entries) {
            if (e.path !in referenced && now - e.lastModified > graceMs) victims += e.path
        }

        // ② 硬 TTL（不论是否被引用）
        for (e in entries) {
            if (now - e.lastModified > ttlMs) victims += e.path
        }

        // ③ 总量上限：剩下的按最旧优先删
        val survivors = entries.filter { it.path !in victims }
        var total = survivors.sumOf { it.sizeBytes }
        if (total > maxTotalBytes) {
            for (e in survivors.sortedBy { it.lastModified }) {
                if (total <= maxTotalBytes) break
                victims += e.path
                total -= e.sizeBytes
            }
        }

        return victims.toList()
    }

    /**
     * 执行一次治理：[referenced] 是 DB 里仍引用的全部截图路径。
     * @return 实际删除成功的文件路径列表（调用方据此清空 DB 引用）。
     */
    fun sweep(
        context: Context,
        referenced: Set<String>,
        now: Long = System.currentTimeMillis()
    ): List<String> {
        val dir = File(context.cacheDir, DIR_NAME)
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name != ".nomedia" } ?: return emptyList()
        if (files.isEmpty()) return emptyList()

        val entries = files.map { f ->
            // lastModified 异常（0）时按"刚创建"处理，避免被当成超期文件误删
            val mtime = f.lastModified().takeIf { it > 0 } ?: now
            Entry(f.absolutePath, f.length(), mtime)
        }

        val victims = planDeletions(entries, referenced, now)
        val deleted = ArrayList<String>(victims.size)
        for (path in victims) {
            try {
                if (File(path).delete()) deleted += path
            } catch (e: Exception) {
                Log.w(TAG, "截图删除失败: $path", e)
            }
        }
        if (deleted.isNotEmpty()) Log.i(TAG, "截图治理：删除 ${deleted.size} 个文件")
        return deleted
    }
}
