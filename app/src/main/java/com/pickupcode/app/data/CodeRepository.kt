package com.pickupcode.app.data

import kotlinx.coroutines.flow.Flow

/**
 * 统一数据仓库——三条识别路径的唯一数据入口。
 * 替代各路径直接调 DAO 的模式，去重/合并逻辑集中管理。
 */
class CodeRepository(private val dao: CodeHistoryDao) {

    suspend fun save(history: CodeHistory): CodeHistoryDao.SaveResult =
        dao.saveOrUpdate(history)

    suspend fun findByCodeAndType(code: String, type: String): CodeHistory? =
        dao.findByCodeAndType(code, type)

    suspend fun markDoneByCodeAndType(code: String, type: String) =
        dao.markDoneByCodeAndType(code, type)

    suspend fun restore(id: Long) = dao.restore(id)

    fun observeActive(): Flow<List<CodeHistory>> = dao.getActiveFlow()

    fun observeTrash(): Flow<List<CodeHistory>> = dao.getTrashFlow()

    suspend fun countDuplicateGroups(): Int = dao.countDuplicateGroups()

    suspend fun updatePickupAddress(id: Long, address: String) =
        dao.updatePickupAddress(id, address)

    suspend fun updateGeo(id: Long, verified: Boolean, confidence: Float, formatted: String) =
        dao.updateGeo(id, verified, confidence, formatted)

    suspend fun updateCode(id: Long, code: String) = dao.updateCode(id, code)

    suspend fun updateSource(id: Long, source: String) = dao.updateSource(id, source)

    suspend fun updateCabinet(id: Long, cabinet: String) = dao.updateCabinet(id, cabinet)

    suspend fun cleanExpired(before: Long, onScreenshot: (String) -> Unit) {
        dao.getExpiredScreenshots(before).forEach { onScreenshot(it) }
        dao.deleteExpiredTrash(before)
    }

    /**
     * 截图治理：孤儿清扫 + 硬保留期 + 目录总量上限（见 [ScreenshotStore]）。
     * 被删掉但仍被记录引用的路径会同步清空 DB 引用，避免详情页指向不存在的文件。
     * @return 实际删除的文件数
     */
    suspend fun cleanScreenshots(
        context: android.content.Context,
        now: Long = System.currentTimeMillis()
    ): Int {
        val referenced = dao.getAllScreenshotPaths().toSet()
        val deleted = com.pickupcode.app.util.ScreenshotStore.sweep(context, referenced, now)
        if (deleted.isNotEmpty()) {
            val stillReferenced = deleted.filter { it in referenced }
            if (stillReferenced.isNotEmpty()) dao.clearScreenshotPaths(stillReferenced)
        }
        return deleted.size
    }

    suspend fun findSameCodeDifferentType(code: String, type: String): List<CodeHistory> =
        dao.findSameCodeDifferentType(code, type)

    suspend fun countActiveByCodeAndType(code: String, type: String): Int =
        dao.countActiveByCodeAndType(code, type)

    /** 删除记录并回收其截图文件（避免 cacheDir 孤儿，代码检查 3-14）。 */
    suspend fun deleteByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        val paths = dao.getScreenshotPathsByIds(ids)
        dao.deleteByIds(ids)
        paths.forEach { deleteFileQuietly(it) }
    }

    suspend fun deleteById(id: Long) {
        val paths = dao.getScreenshotPathsByIds(listOf(id))
        dao.deleteById(id)
        paths.forEach { deleteFileQuietly(it) }
    }

    /** 删文件不抛异常：图片缺失只影响详情页预览，不该让删除记录失败。 */
    private fun deleteFileQuietly(path: String) {
        if (path.isBlank()) return
        try {
            java.io.File(path).delete()
        } catch (_: Exception) {
        }
    }

    fun getById(id: Long): Flow<CodeHistory?> = dao.getById(id)

    suspend fun getByIdSuspend(id: Long): CodeHistory? = dao.getByIdSuspend(id)

    suspend fun getDuplicateEntries(): List<CodeHistory> = dao.getDuplicateEntries()

    suspend fun markDone(id: Long, doneAt: Long = System.currentTimeMillis()) =
        dao.markDone(id, doneAt)


}
