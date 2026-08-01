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

    @Query("SELECT * FROM code_history WHERE isActive = 1 ORDER BY timestamp DESC LIMIT 5")
    fun getRecentActive(): Flow<List<CodeHistory>>

    /** 按 code+type 查最新一条（保存前去重用） */
    @Query("SELECT * FROM code_history WHERE code = :code AND type = :type ORDER BY timestamp DESC LIMIT 1")
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

    @Query("DELETE FROM code_history WHERE timestamp < :before")
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
}

@Database(entities = [CodeHistory::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun codeHistoryDao(): CodeHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pickup_code_db"
                )
                    .fallbackToDestructiveMigration() // 刻意选择：个人工具App历史版本多、无exportSchema，手写迁移风险更高；升级造成的历史数据清空由回收站/去重机制部分缓解。若未来需要保留数据，须先 exportSchema 并补 addMigrations。
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
