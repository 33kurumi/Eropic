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
import io.github.kurumi33.eropic.core.model.StatRow
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MediaEntity>)

    @Query("SELECT id, dateModifiedMs, displayName, relativePath, sizeBytes, isFavorite FROM media_item")
    suspend fun idStates(): List<MediaIdState>

    @Query("UPDATE media_item SET isPresent = 0")
    suspend fun markAllAbsent()

    /** 只把指定 id 标为缺席（对账末尾用差集，避免"先全清再逐条恢复"被中断后库变空）。 */
    @Query("UPDATE media_item SET isPresent = 0 WHERE id IN (:ids)")
    suspend fun markAbsent(ids: List<Long>)

    /**
     * 只更新 MediaStore 侧的字段，保留 [MediaEntity.localFavorite] 与已算好的指纹。
     * 用 REPLACE 整行插入会在"外部编辑导致 mtime 变化"时把本地收藏和 dHash/pHash 一起清掉。
     */
    @Query(
        "UPDATE media_item SET displayName = :displayName, path = :path, relativePath = :relativePath, " +
            "bucketId = :bucketId, bucketName = :bucketName, sizeBytes = :sizeBytes, mimeType = :mimeType, " +
            "width = :width, height = :height, dateTakenMs = :dateTakenMs, dateModifiedMs = :dateModifiedMs, " +
            "dateAddedSecs = :dateAddedSecs, orientation = :orientation, isFavorite = :isFavorite, isPresent = 1 " +
            "WHERE id = :id"
    )
    suspend fun updateStoreFields(
        id: Long,
        displayName: String,
        path: String,
        relativePath: String,
        bucketId: Long,
        bucketName: String,
        sizeBytes: Long,
        mimeType: String,
        width: Int,
        height: Int,
        dateTakenMs: Long,
        dateModifiedMs: Long,
        dateAddedSecs: Long,
        orientation: Int,
        isFavorite: Boolean,
    )

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

    /** 这些 id 里有多少张是"系统相册收藏"（MediaStore 的 IS_FAVORITE，App 只能读不能改）。 */
    @Query("SELECT COUNT(*) FROM media_item WHERE id IN (:ids) AND isFavorite = 1")
    suspend fun countSystemFavorites(ids: List<Long>): Int

    @Query("UPDATE media_item SET localFavorite = :favorite WHERE id = :id")
    suspend fun setLocalFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE media_item SET localFavorite = :favorite WHERE id IN (:ids)")
    suspend fun setLocalFavoriteBatch(ids: List<Long>, favorite: Boolean)

    @Query("SELECT relativePath, COUNT(*) AS mediaCount, SUM(sizeBytes) AS totalBytes FROM media_item WHERE isPresent = 1 GROUP BY relativePath")
    suspend fun folderRows(): List<FolderRow>

    @Query("SELECT * FROM media_item WHERE isPresent = 1 ORDER BY dateTakenMs DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_item WHERE isPresent = 1 AND signatureVersion = 0 ORDER BY dateTakenMs DESC LIMIT :limit")
    suspend fun itemsNeedingSignature(limit: Int): List<MediaEntity>

    /** 按 id 键集分页：后台补缩略图等"遍历全库"的任务用它，避免 OFFSET 越翻越慢。 */
    @Query("SELECT * FROM media_item WHERE isPresent = 1 AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun pagePresentItems(afterId: Long, limit: Int): List<MediaEntity>

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

    /** 在场条目数：图库/统计的口径（countAll 把已缺席的旧行也算进去了）。 */
    @Query("SELECT COUNT(*) FROM media_item WHERE isPresent = 1")
    suspend fun countPresent(): Int

    /** 已算过指纹的数量：用于判断上次索引是否被中途打断（已算 > 0 且还有未算 = 被打断）。 */
    @Query("SELECT COUNT(*) FROM media_item WHERE isPresent = 1 AND signatureVersion > 0")
    suspend fun countHashed(): Int

    @Query("DELETE FROM media_item WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 重命名/复制后改写索引里的路径与文件名；extension 由调用方保证。 */
    @Query("UPDATE media_item SET path = :path, displayName = :displayName, relativePath = :relativePath WHERE id = :id")
    suspend fun updatePathAndName(id: Long, path: String, displayName: String, relativePath: String)

    /** 所有出现过的目录（M2 组合筛选 / M4 搜索的目录选择器）。 */
    @Query("SELECT DISTINCT relativePath FROM media_item WHERE isPresent = 1 ORDER BY relativePath COLLATE NOCASE")
    suspend fun distinctFolders(): List<String>

    /** 组合筛选的命中数与命中总量（B-10）；SQL 由 MediaFilterSql.toQuery 拼好。 */
    @RawQuery
    suspend fun countRaw(query: SupportSQLiteQuery): Long

    @RawQuery
    suspend fun sumSizeRaw(query: SupportSQLiteQuery): Long

    /** 存储仪表盘：按格式聚合。 */
    @Query(
        "SELECT mimeType AS groupKey, COUNT(*) AS itemCount, COALESCE(SUM(sizeBytes), 0) AS totalBytes " +
            "FROM media_item WHERE isPresent = 1 GROUP BY mimeType ORDER BY totalBytes DESC"
    )
    suspend fun statsByFormat(): List<StatRow>

    /** 存储仪表盘：按年份聚合（dateTakenMs 缺失时扫描层已回退到修改时间）。 */
    @Query(
        "SELECT strftime('%Y', dateTakenMs / 1000, 'unixepoch', 'localtime') AS groupKey, " +
            "COUNT(*) AS itemCount, COALESCE(SUM(sizeBytes), 0) AS totalBytes " +
            "FROM media_item WHERE isPresent = 1 GROUP BY groupKey ORDER BY groupKey DESC"
    )
    suspend fun statsByYear(): List<StatRow>

    /** 存储仪表盘：占用最大的目录。 */
    @Query(
        "SELECT relativePath AS groupKey, COUNT(*) AS itemCount, COALESCE(SUM(sizeBytes), 0) AS totalBytes " +
            "FROM media_item WHERE isPresent = 1 GROUP BY relativePath ORDER BY totalBytes DESC LIMIT :limit"
    )
    suspend fun statsByFolder(limit: Int): List<StatRow>

    /** 真实移动文件和索引里的旧路径都可能给到一个范围（用于"滑动窗口"式操作）。 */
    @Query("SELECT id FROM media_item WHERE isPresent = 1 AND dateTakenMs >= :fromMs AND dateTakenMs < :toMs ORDER BY dateTakenMs ASC")
    suspend fun idsInRange(fromMs: Long, toMs: Long): List<Long>

    /** 真实移动文件后立刻改写索引里的位置，避免界面还显示旧目录（随后同步会再校正 MediaStore 新 id）。 */
    @Query("UPDATE media_item SET path = :path, relativePath = :relativePath, bucketId = :bucketId, bucketName = :bucketName WHERE id = :id")
    suspend fun updateLocation(id: Long, path: String, relativePath: String, bucketId: Long, bucketName: String)
}