package io.github.kurumi33.eropic.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.kurumi33.eropic.core.database.entity.OperationLogEntity

@Dao
interface OperationLogDao {

    @Insert
    suspend fun insert(log: OperationLogEntity): Long

    @Query("SELECT * FROM operation_log ORDER BY createdAtMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<OperationLogEntity>

    @Query("SELECT COUNT(*) FROM operation_log")
    suspend fun count(): Int

    @Query("DELETE FROM operation_log WHERE createdAtMs < :beforeMs")
    suspend fun clearBefore(beforeMs: Long)
}
