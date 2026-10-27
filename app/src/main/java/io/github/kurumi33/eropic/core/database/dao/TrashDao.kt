package io.github.kurumi33.eropic.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.kurumi33.eropic.core.database.entity.TrashItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrashDao {

    @Insert
    suspend fun insert(item: TrashItemEntity): Long

    @Query("SELECT * FROM trash_item ORDER BY trashedAtMs DESC")
    fun observeAll(): Flow<List<TrashItemEntity>>

    @Query("SELECT * FROM trash_item ORDER BY trashedAtMs DESC")
    suspend fun all(): List<TrashItemEntity>

    @Query("SELECT * FROM trash_item WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<TrashItemEntity>

    @Query("DELETE FROM trash_item WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM trash_item")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM trash_item")
    fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM trash_item")
    suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM trash_item")
    fun totalBytesFlow(): Flow<Long>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM trash_item")
    suspend fun totalBytes(): Long

    /** 到期应清理（默认保留 30 天）。 */
    @Query("SELECT * FROM trash_item WHERE expiresAtMs <= :nowMs")
    suspend fun expiredBefore(nowMs: Long): List<TrashItemEntity>

    /** 即将到期（M4 到期提醒）：在 [nowMs, beforeMs] 之间到期。 */
    @Query("SELECT * FROM trash_item WHERE expiresAtMs > :nowMs AND expiresAtMs <= :beforeMs ORDER BY expiresAtMs ASC")
    suspend fun expiringBetween(nowMs: Long, beforeMs: Long): List<TrashItemEntity>
}