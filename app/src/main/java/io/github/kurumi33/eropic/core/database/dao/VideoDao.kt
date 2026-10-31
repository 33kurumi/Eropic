package io.github.kurumi33.eropic.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.kurumi33.eropic.core.database.entity.VideoEntity
import kotlinx.coroutines.flow.Flow

/** 视频索引表的读写（v0.8）。只读写本地索引，不碰真实文件。 */
@Dao
interface VideoDao {

    @Upsert
    suspend fun upsertAll(items: List<VideoEntity>)

    @Query("SELECT id FROM video_item")
    suspend fun allIds(): List<Long>

    @Query("UPDATE video_item SET isPresent = 0")
    suspend fun markAllAbsent()

    @Query("UPDATE video_item SET isPresent = 1 WHERE id IN (:ids)")
    suspend fun markPresent(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM video_item WHERE isPresent = 1")
    suspend fun countPresent(): Int

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM video_item WHERE isPresent = 1")
    suspend fun totalBytes(): Long

    @Query("SELECT COUNT(*) FROM video_item WHERE isPresent = 1")
    fun countFlow(): Flow<Int>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM video_item WHERE isPresent = 1")
    fun totalBytesFlow(): Flow<Long>

    @Query(
        "SELECT * FROM video_item WHERE isPresent = 1 " +
            "ORDER BY dateTakenMs DESC, id DESC LIMIT :limit",
    )
    suspend fun list(limit: Int): List<VideoEntity>

    @Query("DELETE FROM video_item")
    suspend fun clear()
}
