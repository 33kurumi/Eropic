package io.github.kurumi33.eropic.core.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import io.github.kurumi33.eropic.core.database.EropicDatabase
import io.github.kurumi33.eropic.core.database.entity.HashBandEntity
import io.github.kurumi33.eropic.core.database.entity.SavedFilterEntity
import io.github.kurumi33.eropic.core.index.ScanScope
import io.github.kurumi33.eropic.core.model.FolderRow
import io.github.kurumi33.eropic.core.model.MediaIdState
import io.github.kurumi33.eropic.core.model.StatRow
import io.github.kurumi33.eropic.core.query.SavedFilterCodec
import io.github.kurumi33.eropic.core.query.MediaFilter
import io.github.kurumi33.eropic.core.query.MediaFilterSql
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 索引读写与首屏查询。 */
class MediaRepository(
    private val context: Context,
    private val database: EropicDatabase,
) {
    private val mediaDao = database.mediaDao()
    private val dataSource = MediaStoreDataSource(context)
    private val scanMutex = Mutex()

    private val pagingConfig = PagingConfig(
        pageSize = 200,
        initialLoadSize = 400,
        prefetchDistance = 600,
        enablePlaceholders = false,
    )

    /** 带排序/筛选/文件夹范围的分页流；sort 改变时由调用方重新订阅（VM 内部 flatMapLatest）。 */
    fun pagingData(
        sort: SortOrder = SortOrder.DATE_DESC,
        favoritesOnly: Boolean = false,
        folderPath: String? = null,
        includeSubfolders: Boolean = true,
    ): Flow<PagingData<MediaEntity>> = Pager(
        config = pagingConfig,
        pagingSourceFactory = {
            val filter = MediaQueryFilter(
                folderPath = folderPath,
                includeSubfolders = includeSubfolders,
                favoritesOnly = favoritesOnly,
            )
            mediaDao.pagingSourceRaw(MediaQuery.paging(filter, sort))
        },
    ).flow

    /** 给大图查看器用的有序 id 列表（与 pagingData 的排序/筛选保持一致）。 */
    suspend fun ids(
        sort: SortOrder = SortOrder.DATE_DESC,
        favoritesOnly: Boolean = false,
        folderPath: String? = null,
        includeSubfolders: Boolean = true,
    ): List<Long> = mediaDao.idsRaw(
        MediaQuery.ids(
            MediaQueryFilter(
                folderPath = folderPath,
                includeSubfolders = includeSubfolders,
                favoritesOnly = favoritesOnly,
            ),
            sort,
        )
    )

    /** M2 组合筛选（大小/分辨率/格式/日期/收藏/目录），分页配置与图库一致。 */
    fun pagingData(filter: MediaFilter, sort: SortOrder = SortOrder.DATE_DESC): Flow<PagingData<MediaEntity>> =
        Pager(
            config = pagingConfig,
            pagingSourceFactory = {
                // MediaFilterSql 不追加 id 兜底，这里补上，保证分页稳定。
                mediaDao.pagingSourceRaw(MediaFilterSql.toQuery("*", filter, sort.sql + ", id DESC"))
            },
        ).flow

    /** 与 filter 分页同一口径的有序 id 列表（给查看器用）。 */
    suspend fun ids(filter: MediaFilter, sort: SortOrder = SortOrder.DATE_DESC): List<Long> =
        mediaDao.idsRaw(MediaFilterSql.toQuery("id", filter, sort.sql + ", id DESC"))

    suspend fun byId(id: Long): MediaEntity? = mediaDao.byId(id)

    /** 切换本地收藏，返回切换后的状态。 */
    suspend fun toggleLocalFavorite(id: Long): Boolean {
        val next = !(mediaDao.byId(id)?.localFavorite ?: false)
        mediaDao.setLocalFavorite(id, next)
        return next
    }

    /** 统计这批 id 中有多少张是系统相册收藏（App 无法取消，只能提示用户去系统相册操作）。 */
    suspend fun countSystemFavorites(ids: Collection<Long>): Int =
        if (ids.isEmpty()) 0 else mediaDao.countSystemFavorites(ids.toList())

    suspend fun folderRows(): List<FolderRow> = mediaDao.folderRows()

    fun countFlow(): Flow<Long> = mediaDao.countFlow()

    fun totalSizeFlow(): Flow<Long> = mediaDao.totalSizeFlow()

    /**
     * 用 MediaStore 做一次全量对账（幂等，可反复调用）。
     *
     * [scope] 来自设置（隐藏目录 / 排除目录），直接进 MediaStore 查询条件；
     * 被排除的图片不会进索引，已索引但后来被排除的行会在这一轮因为"没被看到"而标为缺席。
     */
    suspend fun syncFromMediaStore(
        scope: ScanScope = ScanScope.Default,
        onProgress: suspend (scanned: Int, changed: Int) -> Unit,
    ): SyncResult =
        scanMutex.withLock {
            val existing = mediaDao.idStates().associateBy { it.id }
            val previousPresent = runCatching { mediaDao.countPresent() }.getOrDefault(0)
            var scanned = 0
            var changed = 0
            val seenIds = HashSet<Long>(existing.size + 1024)
            dataSource.queryImages(scope) { batch ->
                val inserts = ArrayList<MediaEntity>(batch.size)
                val updates = ArrayList<MediaEntity>(batch.size)
                for (item in batch) {
                    val old = existing[item.id]
                    if (old == null) {
                        inserts += item
                    } else if (isStoreStateChanged(old, item)) {
                        updates += item
                    }
                    seenIds += item.id
                }
                if (inserts.isNotEmpty() || updates.isNotEmpty()) {
                    // 单批一个事务：中途被取消时已落库的批次完整可见，不会出现"改了一半"的行；
                    // 也不再用"先全库清在场、再逐恢复"的写法（进程被杀会把图库清空）。
                    database.withTransaction {
                        if (inserts.isNotEmpty()) mediaDao.upsertAll(inserts)
                        // 老行只更新 MediaStore 侧字段，保留本地收藏与已算指纹（REPLACE 会全清）。
                        updates.forEach { item ->
                            mediaDao.updateStoreFields(
                                id = item.id,
                                displayName = item.displayName,
                                path = item.path,
                                relativePath = item.relativePath,
                                bucketId = item.bucketId,
                                bucketName = item.bucketName,
                                sizeBytes = item.sizeBytes,
                                mimeType = item.mimeType,
                                width = item.width,
                                height = item.height,
                                dateTakenMs = item.dateTakenMs,
                                dateModifiedMs = item.dateModifiedMs,
                                dateAddedSecs = item.dateAddedSecs,
                                orientation = item.orientation,
                                isFavorite = item.isFavorite,
                            )
                        }
                    }
                    changed += inserts.size + updates.size
                }
                scanned += batch.size
                onProgress(scanned, changed)
            }
            // 缺席标记用差集：只把"这一轮没看到"的旧行标掉。这样即便中途被取消，
            // 也只会留下部分旧行，而不是把整库清空。scanned == 0 时区分两种原因：
            //  - 没有读取全部照片的权限（Android 14 仅选中部分照片）→ 绝不能清库；
            //  - 有权限但确实一张都没有（用户清空了相册）→ 应该清空，否则图库留幽灵行。
            val hasFullAccess = hasFullMediaAccess()
            val dropRatio = if (previousPresent <= 0) 0.0 else scanned.toDouble() / previousPresent
            val shouldMarkAbsent = hasFullAccess && when {
                scanned == 0 -> true
                // 数量骤降一半以上（例如 SD 卡只挂载了一部分卷）时不轻信结果。
                dropRatio < 0.5 -> false
                else -> true
            }
            if (shouldMarkAbsent) {
                val stale = existing.keys.filter { it !in seenIds }
                database.withTransaction {
                    stale.chunked(500).forEach { mediaDao.markAbsent(it) }
                }
            }
            SyncResult(
                scanned = scanned,
                changed = changed,
                totalIndexed = mediaDao.countPresent(),
                markedAbsent = if (shouldMarkAbsent) existing.size - seenIds.size.coerceAtMost(existing.size) else 0,
            )
        }

    /** 与 MediaStore 有关、且需要写回索引的字段是否变了（收藏加星不改 mtime，也要算变化）。 */
    private fun isStoreStateChanged(old: MediaIdState, item: MediaEntity): Boolean =
        old.dateModifiedMs != item.dateModifiedMs ||
            old.sizeBytes != item.sizeBytes ||
            old.displayName != item.displayName ||
            old.relativePath != item.relativePath ||
            old.isFavorite != item.isFavorite

    /**
     * 是否拿到了"读取全部照片"的权限。
     *
     * Android 13+ 用 READ_MEDIA_IMAGES；Android 12 及以下只有 READ_EXTERNAL_STORAGE。
     * 只有全量权限才允许把未看到的旧行标为缺席，否则部分授权会把索引清空。
     */
    private fun hasFullMediaAccess(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun itemsNeedingSignature(limit: Int): List<MediaEntity> = mediaDao.itemsNeedingSignature(limit)

    suspend fun hashedItems(): List<MediaEntity> = mediaDao.hashedItems()

    /** 键集分页遍历（id 递增），供后台任务使用。 */
    suspend fun pagePresentItems(afterId: Long, limit: Int): List<MediaEntity> =
        mediaDao.pagePresentItems(afterId, limit)

    /** 批量收藏/取消收藏（只改 App 内的 localFavorite，不写回系统相册）。 */
    suspend fun setLocalFavorites(ids: Collection<Long>, favorite: Boolean) {
        if (ids.isEmpty()) return
        ids.chunked(500).forEach { mediaDao.setLocalFavoriteBatch(it, favorite) }
    }

    suspend fun updateSignature(id: Long, dhash: Long, phash: Long, blur: Double) = mediaDao.updateSignature(id, dhash, phash, blur)

    suspend fun updateSha(id: Long, sha: String) = mediaDao.updateSha(id, sha)

    suspend fun countNeedingSignature(): Int = mediaDao.countNeedingSignature()

    /** 已算过指纹的数量：用于判断上次索引是否被中断（自动续算判断）。 */
    suspend fun countHashed(): Int = mediaDao.countHashed()

    suspend fun byIds(ids: List<Long>): List<MediaEntity> = mediaDao.byIds(ids)

    /** 标记无法解码/不支持的条目，跳过指纹计算（否则下一批查询会再次取到它们）。 */
    suspend fun markSignatureUnsupported(ids: List<Long>) {
        if (ids.isEmpty()) return
        mediaDao.markSignatureUnsupported(ids)
    }

    /** 指纹与倒排桶一起落库；桶只用于未来增量索引，M0 的内存分组不读它。 */
    suspend fun saveSignature(
        id: Long,
        dhash: Long,
        phash: Long,
        blurScore: Double,
        bands: List<HashBandEntity>,
    ) {
        mediaDao.updateSignature(id, dhash, phash, blurScore)
        val hashBandDao = database.hashBandDao()
        hashBandDao.deleteForMedia(id)
        hashBandDao.insertAll(bands)
    }

    suspend fun exactCandidates() = mediaDao.exactCandidates()

    suspend fun count(): Int = mediaDao.countPresent()

    suspend fun clear() {
        database.hashBandDao().clear()
        database.duplicateDao().clearMembers()
        database.duplicateDao().clearGroups()
        mediaDao.clear()
    }

    /** 组合筛选的命中数与占用空间（规划 B-10）。 */
    suspend fun count(filter: MediaFilter): Long =
        mediaDao.countRaw(MediaFilterSql.toQuery("COUNT(*)", filter, SortOrder.DATE_DESC.sql))

    suspend fun sumSize(filter: MediaFilter): Long =
        mediaDao.sumSizeRaw(MediaFilterSql.toQuery("COALESCE(SUM(sizeBytes), 0)", filter, SortOrder.DATE_DESC.sql))

    /** 存储仪表盘（规划 E-02/E-03）。 */
    suspend fun statsByFormat(): List<StatRow> = mediaDao.statsByFormat()

    suspend fun statsByYear(): List<StatRow> = mediaDao.statsByYear()

    suspend fun statsByFolder(limit: Int = 12): List<StatRow> = mediaDao.statsByFolder(limit)

    suspend fun distinctFolders(): List<String> = mediaDao.distinctFolders()

    /** 按拍摄时间区间取 id（"这一年/这一月"批量操作）。 */
    suspend fun idsInRange(fromMs: Long, toMs: Long): List<Long> = mediaDao.idsInRange(fromMs, toMs)

    // ---- 保存的筛选条件（规划 B-17） ----

    fun savedFilters(): Flow<List<SavedFilterEntity>> = database.savedFilterDao().observeAll()

    suspend fun saveFilter(name: String, filter: MediaFilter): Long = database.savedFilterDao().insert(
        SavedFilterEntity(
            name = name.trim().ifBlank { "未命名筛选" },
            filterJson = SavedFilterCodec.encode(filter),
            createdAtMs = System.currentTimeMillis(),
        ),
    )

    suspend fun deleteSavedFilter(id: Long) = database.savedFilterDao().deleteById(id)

    suspend fun stats(): RepositoryStats {
        val count = mediaDao.countPresent()
        return RepositoryStats(count)
    }
}

data class SyncResult(
    val scanned: Int,
    val changed: Int,
    val totalIndexed: Int,
    /** 本轮被判定为已不在 MediaStore 里的行数（缺席标记；权限不足或数量骤降时为 0）。 */
    val markedAbsent: Int = 0,
)

data class RepositoryStats(val totalCount: Int)