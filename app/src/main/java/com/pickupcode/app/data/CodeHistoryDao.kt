package com.pickupcode.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CodeHistoryDao {
    /** 活跃记录：按 code+type 去重取最新 */
    @Query("SELECT * FROM code_history WHERE isActive = 1 AND id IN (SELECT MAX(id) FROM code_history WHERE isActive = 1 GROUP BY code, type) ORDER BY timestamp DESC")
    fun getActiveFlow(): Flow<List<CodeHistory>>

    /** 回收站记录 */
    @Query("SELECT * FROM code_history WHERE isActive = 0 ORDER BY doneAt DESC")
    fun getTrashFlow(): Flow<List<CodeHistory>>

    @Query("SELECT * FROM code_history WHERE id = :id")
    fun getById(id: Long): Flow<CodeHistory?>

    @Query("SELECT * FROM code_history WHERE id = :id")
    suspend fun getByIdSuspend(id: Long): CodeHistory?

    @Query("SELECT * FROM code_history WHERE isActive = 1 ORDER BY timestamp DESC LIMIT 5")
    fun getRecentActive(): Flow<List<CodeHistory>>

    /** 按 code+type 查最新的活跃一条（保存前去重用；需 isActive=1，避免回收站数据误判"已存在"） */
    @Query("SELECT * FROM code_history WHERE code = :code AND type = :type AND isActive = 1 ORDER BY timestamp DESC LIMIT 1")
    suspend fun findByCodeAndType(code: String, type: String): CodeHistory?

    /** 查同 code 不同类型的记录（重复值检测） */
    @Query("SELECT * FROM code_history WHERE code = :code AND type != :type AND isActive = 1 ORDER BY timestamp DESC")
    suspend fun findSameCodeDifferentType(code: String, type: String): List<CodeHistory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: CodeHistory): Long

    @Update
    suspend fun update(history: CodeHistory)

    /** 标记已取（移入回收站）：isActive=0 + doneAt=now。注：名含 Done 但语义是“归档/移入回收站”，非物理删除。 */
    @Query("UPDATE code_history SET isActive = 0, doneAt = :doneAt WHERE id = :id")
    suspend fun markDone(id: Long, doneAt: Long = System.currentTimeMillis())

    /** M1: 定向只更新 geo 校验字段，避免异步回调用旧快照覆盖用户对 code/source/address 的编辑。 */
    @Query("UPDATE code_history SET geoVerified = :verified, geoConfidence = :confidence, geoFormattedAddress = :formatted WHERE id = :id")
    suspend fun updateGeo(id: Long, verified: Boolean, confidence: Float, formatted: String)

    /** 批量归档：同 code+type 的所有活跃记录标记为已取（一次取件对应多份同码记录全部归档）。 */
    @Query("UPDATE code_history SET isActive = 0, doneAt = :doneAt WHERE code = :code AND type = :type AND isActive = 1")
    suspend fun markDoneByCodeAndType(code: String, type: String, doneAt: Long = System.currentTimeMillis())

    /** 从回收站恢复 */
    @Query("UPDATE code_history SET isActive = 1, doneAt = 0 WHERE id = :id")
    suspend fun restore(id: Long)

    /** 清除过期回收站记录（超过 retentionMs） */
    @Query("DELETE FROM code_history WHERE isActive = 0 AND doneAt > 0 AND doneAt < :before")
    suspend fun deleteExpiredTrash(before: Long)

    /** 手动删除回收站记录 */
    @Query("DELETE FROM code_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 批量删除多条记录（一次性事务，避免逐条删） */
    @Query("DELETE FROM code_history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Medium-2: 清理过期旧记录——仅回收站（isActive=0），绝不删活跃记录；活跃记录需用户标记已取后才可被清理。 */
    @Query("DELETE FROM code_history WHERE isActive = 0 AND timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    /** 查重复码值分组（同 code+type 出现 ≥2 次），每组返回最新一条 */
    @Query("SELECT * FROM code_history WHERE isActive = 1 AND code || ':' || type IN (SELECT code || ':' || type FROM code_history WHERE isActive = 1 GROUP BY code, type HAVING COUNT(*) >= 2) ORDER BY code, timestamp DESC")
    suspend fun getDuplicateEntries(): List<CodeHistory>

    /** 查同 code+type 的所有重复记录 */
    @Query("SELECT * FROM code_history WHERE code = :code AND type = :type AND isActive = 1 ORDER BY timestamp DESC")
    suspend fun getDuplicatesByCodeAndType(code: String, type: String): List<CodeHistory>

    /** 统计活跃的重复组数量 */
    @Query("SELECT COUNT(*) FROM (SELECT 1 FROM code_history WHERE isActive = 1 GROUP BY code, type HAVING COUNT(*) >= 2)")
    suspend fun countDuplicateGroups(): Int

    /** H6 去重的保存结果：id = 记录 id，existed = 是否命中已存在的活跃记录（用于通知去重提示）。 */
    class SaveResult(val id: Long, val existed: Boolean)

    /**
     * H6: 事务内原子化「查询已有 + 插入/更新」，避免多入口(分享/无障碍/手动)并发对同一 code+type
     * 各自 find→insert 产生重复行。已存在则按新信息更新并返回现有 id；不存在则插入返回新 id。
     */
    @Transaction
    suspend fun saveOrUpdate(history: CodeHistory): SaveResult {
        val existing = findByCodeAndType(history.code, history.type)
        return if (existing != null) {
            update(existing.copy(
                source = if (history.source.isNotBlank()) history.source else existing.source,
                pickupAddress = if (history.pickupAddress.isNotBlank()) history.pickupAddress else existing.pickupAddress,
                cabinetNumber = if (history.cabinetNumber.isNotBlank()) history.cabinetNumber else existing.cabinetNumber,
                screenshotPath = if (history.screenshotPath.isNotBlank()) history.screenshotPath else existing.screenshotPath,
                rawTextSnippet = if (history.rawTextSnippet.isNotBlank()) history.rawTextSnippet else existing.rawTextSnippet,
                shareSourcePkg = if (history.shareSourcePkg.isNotBlank()) history.shareSourcePkg else existing.shareSourcePkg,
                shareSourceName = if (history.shareSourceName.isNotBlank()) history.shareSourceName else existing.shareSourceName,
                isActive = true,
                doneAt = 0,
                timestamp = history.timestamp
            ))
            SaveResult(existing.id, true)
        } else {
            SaveResult(insert(history), false)
        }
    }

    /**
     * 原始去重语义（v1.0.4）：查重但照常新增，让同一 code 多次保存真实产生多行，
     * 由「重复值整理」入口手动保留/删除。existed=是否已存在同 code+type（用于提示重复），
     * 但每次都会 insert 新行（不再像 saveOrUpdate 那样合并成一行）。
     *
     * 增强（借鉴 sources2 deduplicatePackages）：查重的同码记录若缺地址/柜号/来源，
     * 用本次新识别到的信息补全缺失项——同一取件码多张截图/多次识别，地址和柜号可能
     * 只在其中一张上识别完整，补全后详情页无需再空着。
     */
    @Transaction
    suspend fun insertCheckDuplicate(history: CodeHistory): SaveResult {
        val existing = findByCodeAndType(history.code, history.type)
        if (existing != null) {
            // 补全缺失信息（仅当新值非空且旧值为空时覆盖）
            val needUpdate =
                (history.pickupAddress.isNotBlank() && existing.pickupAddress.isBlank()) ||
                (history.cabinetNumber.isNotBlank() && existing.cabinetNumber.isBlank()) ||
                (history.source.isNotBlank() && existing.source.isBlank())
            if (needUpdate) {
                update(existing.copy(
                    pickupAddress = if (history.pickupAddress.isNotBlank()) history.pickupAddress else existing.pickupAddress,
                    cabinetNumber = if (history.cabinetNumber.isNotBlank()) history.cabinetNumber else existing.cabinetNumber,
                    source = if (history.source.isNotBlank()) history.source else existing.source
                ))
            }
        }
        val id = insert(history)
        return SaveResult(id, existing != null)
    }
}

@Database(entities = [CodeHistory::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun codeHistoryDao(): CodeHistoryDao

    companion object {
        /** 3 → 4：新增分享来源两个字段。用 ALTER 保留既有历史数据（避免升级清空取件记录）。 */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE code_history ADD COLUMN shareSourcePkg TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE code_history ADD COLUMN shareSourceName TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 4 → 5：新增独立柜号列。ALTER 保留既有数据，默认空串。 */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE code_history ADD COLUMN cabinetNumber TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pickup_code_db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    // 兜底迁移：无 exportSchema 时首轮迁移难以严格校验 schema，仍保留 destructive 作为最后的保险，
                    // 避免未知后续版本导致无法升级卡死；已通过 addMigrations 保住 3→4 的数据。
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
