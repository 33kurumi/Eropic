package io.github.kurumi33.eropic.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.kurumi33.eropic.core.database.entity.OperationLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OperationLogDao {

    @Insert
    suspend fun insert(log: OperationLogEntity): Long

    @Query("SELECT * FROM operation_log ORDER BY createdAtMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<OperationLogEntity>

    /** 操作历史页用：带乘性变化时要跟着刷新，所以给 Flow 版本。 */
    @Query("SELECT * FROM operation_log ORDER BY createdAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<OperationLogEntity>>

    @Query("SELECT * FROM operation_log WHERE id = :id")
    suspend fun byId(id: Long): OperationLogEntity?

    @Query("UPDATE operation_log SET undone = 1 WHERE id = :id")
    suspend fun markUndone(id: Long)

    @Query("SELECT COUNT(*) FROM operation_log")
    suspend fun count(): Int

    @Query("DELETE FROM operation_log WHERE createdAtMs < :beforeMs")
    suspend fun clearBefore(beforeMs: Long)
}