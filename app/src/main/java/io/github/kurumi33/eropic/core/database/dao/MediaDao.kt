package io.github.kurumi33.eropic.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.model.FolderRow
import io.github.kurumi33.eropic.core.model.MediaIdState
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MediaEntity>)

    @Query("SELECT id, dateModifiedMs FROM media_item")
    suspend fun idStates(): List<MediaIdState>

    @Query("UPDATE media_item SET isPresent = 0")
    suspend fun markAllAbsent()

    @Query("UPDATE media_item SET isPresent = 1 WHERE id IN (:ids)")
    suspend fun markPresent(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM media_item WHERE isPresent = 1")
    fun countFlow(): Flow<Long>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM media_item WHERE isPresent = 1")
    fun totalSizeFlow(): Flow<Long>

    @Query("SELECT * FROM media_item WHERE isPresent = 1 ORDER BY dateTakenMs DESC")
    fun pagingSource(): PagingSource<Int, MediaEntity>

    /** 运行时排序/筛选：SQL 由 [io.github.kurumi33.eropic.core.media.MediaQuery] 拼接。 */
    @RawQuery(observedEntities = [MediaEntity::class])
    fun pagingSourceRaw(query: SupportSQLiteQuery): PagingSource<Int, MediaEntity>

    @RawQuery(observedEntities = [MediaEntity::class])
    suspend fun idsRaw(query: SupportSQLiteQuery): List<Long>

    @Query("SELECT * FROM media_item WHERE id = :id")
    suspend fun byId(id: Long): MediaEntity?

    @Query("UPDATE media_item SET localFavorite = :favorite WHERE id = :id")
    suspend fun setLocalFavorite(id: Long, favorite: Boolean)

    @Query("SELECT relativePath, COUNT(*) AS mediaCount, SUM(sizeBytes) AS totalBytes FROM media_item WHERE isPresent = 1 GROUP BY relativePath")
    suspend fun folderRows(): List<FolderRow>

    @Query("SELECT * FROM media_item WHERE isPresent = 1 ORDER BY dateTakenMs DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_item WHERE isPresent = 1 AND signatureVersion = 0 ORDER BY dateTakenMs DESC LIMIT :limit")
    suspend fun itemsNeedingSignature(limit: Int): List<MediaEntity>

    @Query("SELECT * FROM media_item WHERE isPresent = 1 AND dhash IS NOT NULL")
    suspend fun hashedItems(): List<MediaEntity>

    @Query("SELECT * FROM media_item WHERE isPresent = 1 AND sha256 IS NOT NULL")
    suspend fun itemsWithSha(): List<MediaEntity>

    @Query("SELECT id, path, sizeBytes, mimeType, dateModifiedMs FROM media_item WHERE isPresent = 1")
    suspend fun exactCandidates(): List<io.github.kurumi33.eropic.core.model.ExactCandidate>

    @Query("SELECT * FROM media_item WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<MediaEntity>

    @Query("UPDATE media_item SET dhash = :dhash, phash = :phash, blurScore = :blurScore, signatureVersion = 1 WHERE id = :id")
    suspend fun updateSignature(id: Long, dhash: Long, phash: Long, blurScore: Double)

    @Query("UPDATE media_item SET sha256 = :sha WHERE id = :id")
    suspend fun updateSha(id: Long, sha: String)

    @Query("DELETE FROM media_item")
    suspend fun clear()

    /** 解码失败的条目记为 -1，避免 itemsNeedingSignature 反复取到同一批导致死循环。 */
    @Query("UPDATE media_item SET signatureVersion = -1 WHERE id IN (:ids)")
    suspend fun markSignatureUnsupported(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM media_item WHERE isPresent = 1 AND signatureVersion = 0")
    suspend fun countNeedingSignature(): Int

    @Query("SELECT COUNT(*) FROM media_item")
    suspend fun countAll(): Int
}
