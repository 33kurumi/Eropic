package io.github.kurumi33.eropic.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import io.github.kurumi33.eropic.AppContainer
import io.github.kurumi33.eropic.BuildConfig
import io.github.kurumi33.eropic.core.analysis.detectors.BurstResult
import io.github.kurumi33.eropic.core.analysis.detectors.LargeFileResult
import io.github.kurumi33.eropic.core.database.entity.DuplicateGroupEntity
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.database.entity.SavedFilterEntity
import io.github.kurumi33.eropic.core.database.entity.TrashItemEntity
import io.github.kurumi33.eropic.core.database.entity.VideoEntity
import io.github.kurumi33.eropic.core.fileops.BatchState
import io.github.kurumi33.eropic.core.fileops.EmptyFolderEntry
import io.github.kurumi33.eropic.core.fileops.FileOpReport
import io.github.kurumi33.eropic.core.fileops.MoveResult
import io.github.kurumi33.eropic.core.fileops.OpFailure
import io.github.kurumi33.eropic.core.fileops.OperationRecord
import io.github.kurumi33.eropic.core.fileops.RenamePreview
import io.github.kurumi33.eropic.core.folders.FolderOpResult
import io.github.kurumi33.eropic.core.index.IndexState
import io.github.kurumi33.eropic.core.log.CrashLogger
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.core.media.SortOrder
import io.github.kurumi33.eropic.core.model.FolderRow
import io.github.kurumi33.eropic.core.model.StatRow
import io.github.kurumi33.eropic.core.permissions.StorageAccess
import io.github.kurumi33.eropic.core.query.MediaFilter
import io.github.kurumi33.eropic.core.search.CharacterMatchResult
import io.github.kurumi33.eropic.core.search.ImageSearchRepository
import io.github.kurumi33.eropic.core.search.OnlineImageSearch
import io.github.kurumi33.eropic.core.search.OnlineSearchResult
import io.github.kurumi33.eropic.core.search.ReverseImageProvider
import io.github.kurumi33.eropic.core.search.SimilarImageHit
import io.github.kurumi33.eropic.core.search.SimilarityQuery
import io.github.kurumi33.eropic.core.settings.AppSettings
import io.github.kurumi33.eropic.core.settings.DateGroupMode
import io.github.kurumi33.eropic.core.settings.RetentionOption
import io.github.kurumi33.eropic.core.settings.SimilarityLevel
import io.github.kurumi33.eropic.core.settings.ThemeMode
import io.github.kurumi33.eropic.core.trash.DeleteResult
import io.github.kurumi33.eropic.core.update.UpdateChecker
import io.github.kurumi33.eropic.core.update.UpdateUiState
import io.github.kurumi33.eropic.core.update.VersionCompare
import io.github.kurumi33.eropic.core.trash.RestoreResult
import io.github.kurumi33.eropic.core.trash.TrashResult
import io.github.kurumi33.eropic.ui.gallery.GalleryDates
import io.github.kurumi33.eropic.ui.gallery.GalleryRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** 大图查看器目标：有序 id 列表 + 起始位置。放在 ViewModel 里以便旋转屏幕后不丢。 */
data class ViewerTarget(val ids: List<Long>, val startIndex: Int)

/**
 * 一次性界面提示（Snackbar）。
 *
 * [undoTrashIds] 非空时提示条会带一个「撤销」按钮，点了就把这些条目从回收站恢复——
 * 这是"删除可撤销"的最后一道保险，比二次确认对话框更符合移动端习惯。
 */
data class UiMessage(
    val text: String,
    val undoTrashIds: List<Long>? = null,
)

/** 存储仪表盘的聚合快照（M4）。 */
data class StorageOverview(
    val totalCount: Long = 0,
    val totalBytes: Long = 0,
    val formats: List<StatRow> = emptyList(),
    val years: List<StatRow> = emptyList(),
    val folders: List<StatRow> = emptyList(),
)

class EropicViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.mediaRepository
    private val duplicateDao = container.database.duplicateDao()
    private val settingsRepository = container.settingsRepository
    private val trashRepository = container.trashRepository

    // ---- 设置 ----

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val userSort = MutableStateFlow<SortOrder?>(null)

    /** 当前排序：用户在本会话里手动选过就用它，否则用设置里的默认排序。 */
    val sortOrder: StateFlow<SortOrder> = combine(
        settings.map { it.defaultSort },
        userSort,
    ) { default, chosen -> chosen ?: default }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SortOrder.DATE_DESC)

    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }

    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }

    fun setGridColumns(columns: Int) = viewModelScope.launch { settingsRepository.setGridColumns(columns) }

    fun setShowMeta(enabled: Boolean) = viewModelScope.launch { settingsRepository.setShowMeta(enabled) }

    fun setDefaultSort(sort: SortOrder) = viewModelScope.launch { settingsRepository.setDefaultSort(sort) }

    fun setSortOrder(sort: SortOrder) {
        userSort.value = sort
    }

    fun toggleFavoritesFilter() {
        _favoritesOnly.value = !_favoritesOnly.value
    }

    // ---- M4 新增设置 ----

    fun setDateGrouping(enabled: Boolean) = viewModelScope.launch { settingsRepository.setDateGrouping(enabled) }

    fun setSlideshowInterval(seconds: Int) =
        viewModelScope.launch { settingsRepository.setSlideshowInterval(seconds) }

    fun setRetention(option: RetentionOption) = viewModelScope.launch {
        settingsRepository.setRetention(option)
        // 关键：已有条目的到期时间是在"移入回收站"时写死的，必须按新档位重算。
        // 否则从 30 天改成"永久保留"后，旧条目仍会按旧日期被自动永久删除。
        container.trashRepository.applyRetention(option.days)
        // 调小保留期时立刻跑一次清理，让新策略马上生效。
        container.requestTrashCleanup()
    }

    fun setSimilarity(level: SimilarityLevel) = viewModelScope.launch { settingsRepository.setSimilarity(level) }

    fun setScanIncludeHidden(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setScanIncludeHidden(enabled) }

    fun addScanExcludedDir(raw: String) = viewModelScope.launch { settingsRepository.addScanExcludedDir(raw) }

    fun removeScanExcludedDir(dir: String) = viewModelScope.launch { settingsRepository.removeScanExcludedDir(dir) }

    fun clearScanExcludedDirs() = viewModelScope.launch { settingsRepository.clearScanExcludedDirs() }

    // ---- 图库 ----

    @OptIn(ExperimentalCoroutinesApi::class)
    val media: Flow<PagingData<MediaEntity>> = sortOrder.flatMapLatest { sort ->
        _favoritesOnly.flatMapLatest { favoritesOnly ->
            repository.pagingData(sort = sort, favoritesOnly = favoritesOnly)
        }
    }.cachedIn(viewModelScope)

    /**
     * 图库网格数据：与 [media] 同源，但在开启「按日期分组」且按时间排序时，
     * 会在每个月份的第一张照片前插入一个分组标题行。
     *
     * 注意：insertSeparators 的分隔项必须和条目同类型，所以这里先 map 成 [GalleryRow]，
     * 再用 GalleryRow.Header 作为分隔项，网格里按类型分支即可。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val galleryRows: Flow<PagingData<GalleryRow>> = combine(sortOrder, settings) { sort, current ->
        sort to current.dateGrouping
    }
        .distinctUntilChanged()
        .flatMapLatest { (sort, grouping) ->
            _favoritesOnly.flatMapLatest { favoritesOnly ->
                repository.pagingData(sort = sort, favoritesOnly = favoritesOnly).map { paging ->
                    val photos: PagingData<GalleryRow> = paging.map { GalleryRow.Photo(it) }
                    val byDate = sort == SortOrder.DATE_DESC || sort == SortOrder.DATE_ASC
                    if (!grouping || !byDate) {
                        photos
                    } else {
                        photos.insertSeparators { before, after ->
                            val next = after as? GalleryRow.Photo ?: return@insertSeparators null
                            val previous = before as? GalleryRow.Photo
                            val nextKey = GalleryDates.monthKey(next.entity.dateTakenMs)
                            if (previous == null ||
                                GalleryDates.monthKey(previous.entity.dateTakenMs) != nextKey
                            ) {
                                GalleryRow.Header(nextKey, GalleryDates.monthLabel(next.entity.dateTakenMs))
                            } else {
                                null
                            }
                        }
                    }
                }
            }
        }
        .cachedIn(viewModelScope)

    val count: StateFlow<Long> = repository.countFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val totalSize: StateFlow<Long> = repository.totalSizeFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val indexState: StateFlow<IndexState> = container.indexCoordinator.state

    // ---- 大图查看器 ----

    /** 打开查看器时调用：按当前排序/筛选取有序 id 列表。 */
    suspend fun viewerIds(): List<Long> = repository.ids(sortOrder.value, _favoritesOnly.value)

    private val _viewerTarget = MutableStateFlow<ViewerTarget?>(null)
    val viewerTarget: StateFlow<ViewerTarget?> = _viewerTarget

    fun openViewer(ids: List<Long>, startIndex: Int) {
        if (ids.isEmpty()) return
        _viewerTarget.value = ViewerTarget(ids, startIndex.coerceIn(0, ids.lastIndex))
    }

    fun closeViewer() {
        _viewerTarget.value = null
    }

    suspend fun mediaById(id: Long): MediaEntity? = repository.byId(id)

    /** 切换本地收藏；false 表示取消收藏。 */
    suspend fun toggleFavorite(id: Long): Boolean = repository.toggleLocalFavorite(id)

    // ---- 文件夹 ----

    suspend fun loadFolderRows(): List<FolderRow> = repository.folderRows()

    // 文件夹页不继承图库的「只看收藏」筛选：否则树里显示 500 张、点进去只有 3 张，
    // 而且那个筛选是图库里的临时状态，用户不会想到它会影响到这里。
    // v0.3：文件夹页也有自己的排序（用户反馈"文件夹界面为什么没有图库的排序功能"）。
    // sort = null 表示沿用图库当前的排序，页面自己选了排序就传具体值。
    fun folderMedia(
        path: String,
        includeSubfolders: Boolean,
        sort: SortOrder? = null,
    ): Flow<PagingData<MediaEntity>> =
        repository.pagingData(
            sort = sort ?: sortOrder.value,
            favoritesOnly = false,
            folderPath = path,
            includeSubfolders = includeSubfolders,
        )

    suspend fun folderIds(
        path: String,
        includeSubfolders: Boolean,
        sort: SortOrder? = null,
    ): List<Long> = repository.ids(
        sort = sort ?: sortOrder.value,
        favoritesOnly = false,
        folderPath = path,
        includeSubfolders = includeSubfolders,
    )

    // ---- 清理 ----

    val groups: StateFlow<List<DuplicateGroupEntity>> = duplicateDao.groupsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun members(groupId: Long): List<MediaEntity> = duplicateDao.members(groupId)

    fun startIndex() = container.indexCoordinator.start(
        similarity = settings.value.similarity,
        scope = settings.value.scanScope(),
    )

    fun cancelIndex() = container.indexCoordinator.cancel()

    /**
     * v0.7：暂停指纹计算。
     *
     * 暂停当前批次跑完后就把已算好的结果落库并休眠；不会「白算」——
     * 已经解码算出的指纹要么已写库，要么在 [io.github.kurumi33.eropic.core.index.IndexCoordinator]
     * 的待写缓冲里，恢复时先落盘再继续。
     */
    fun pauseIndex() = container.indexCoordinator.pause()

    /** v0.7：恢复指纹计算。 */
    fun resumeIndex() = container.indexCoordinator.resume()

    fun rebuildIndex() {
        viewModelScope.launch {
            // 先让正在跑的任务停下来（IndexCoordinator.start 在运行中会直接返回，
            // 否则这里的 clear() 会把索引清空却不会重建）。
            if (container.indexCoordinator.state.value.isRunning) {
                container.indexCoordinator.cancel()
                // 取消是异步生效的：等一下，避免旧任务最后一批落库和 clear() 交错。
                kotlinx.coroutines.delay(300)
            }
            repository.clear()
            container.indexCoordinator.start(
                similarity = settings.value.similarity,
                scope = settings.value.scanScope(),
            )
        }
    }

    // ---- M2 检索与检测 ----

    suspend fun largeFiles(): LargeFileResult = container.largeFileDetector.find()

    suspend fun oldPhotos(nowMs: Long = System.currentTimeMillis()): List<MediaEntity> =
        container.oldPhotoDetector.find(nowMs)

    suspend fun screenshots(): List<MediaEntity> = container.screenshotDetector.find()

    suspend fun blurred(): List<MediaEntity> = container.blurDetector.find()

    /** 连拍候选：5 秒内同目录 >=3 张；结果里也带可释放字节。 */
    suspend fun bursts(): BurstResult = container.burstDetector.find()

    /** 小图候选：小于 200x200。 */
    suspend fun smallImages(): List<MediaEntity> = container.smallImageDetector.find()

    /**
     * v0.3：大图候选（按分辨率，>= 800 万像素，从大到小）。
     *
     * 清理页把原来的「小图」分类换成了「大图」：小图一般不影响空间，
     * 而大图（长截图、全景、老旗舰直出）才是用户想找出来看的。
     */
    suspend fun largeImages(): List<MediaEntity> = container.largeImageDetector.find()

    /** 大图的全库命中数与总字节（不按列表 LIMIT 截断，清理报告用）。 */
    suspend fun largeImageSummary(): io.github.kurumi33.eropic.core.analysis.detectors.LargeFileSummaryRow =
        container.largeImageDetector.summary()

    fun filteredMedia(filter: MediaFilter): Flow<PagingData<MediaEntity>> =
        repository.pagingData(filter, sortOrder.value)

    suspend fun filteredIds(filter: MediaFilter): List<Long> = repository.ids(filter, sortOrder.value)

    /** M4 B-10：当前筛选命中的张数与总大小。 */
    suspend fun filterCount(filter: MediaFilter): Long = repository.count(filter)

    suspend fun filterSumSize(filter: MediaFilter): Long = repository.sumSize(filter)

    /** M4 A-11：按文件名/目录模糊搜索（复用组合筛选的 nameLike）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun searchMedia(query: String): Flow<PagingData<MediaEntity>> =
        sortOrder.flatMapLatest { sort ->
            repository.pagingData(MediaFilter(nameLike = query), sort)
        }.cachedIn(viewModelScope)

    suspend fun searchIds(query: String): List<Long> =
        repository.ids(MediaFilter(nameLike = query), sortOrder.value)

    suspend fun distinctFolders(): List<String> = repository.distinctFolders()

    // ---- v0.6 搜索历史（独立 DataStore，最多 50 条，本机保存） ----

    val searchHistory: StateFlow<List<String>> = container.searchHistoryStore.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 记录一次搜索：去空白、去重、最新在前。只在用户真正提交/防抖结束后调用一次。 */
    fun recordSearch(query: String) {
        viewModelScope.launch { container.searchHistoryStore.add(query) }
    }

    /** 删除单条搜索历史。 */
    fun deleteSearchHistory(entry: String) {
        viewModelScope.launch { container.searchHistoryStore.remove(entry) }
    }

    /** 一键清空搜索历史（不动图片、不动保存的筛选）。 */
    fun clearSearchHistory() {
        viewModelScope.launch { container.searchHistoryStore.clear() }
    }

    // ---- M4 保存的筛选条件（B-17） ----

    val savedFilters: StateFlow<List<SavedFilterEntity>> = repository.savedFilters()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun saveFilter(name: String, filter: MediaFilter): Long = repository.saveFilter(name, filter)

    suspend fun deleteSavedFilter(id: Long) = repository.deleteSavedFilter(id)

    // ---- M4 存储仪表盘（E-04） ----

    /** 一次取齐存储仪表盘需要的聚合数据（按格式/年份/目录）。 */
    suspend fun storageOverview(): StorageOverview {
        val formats = repository.statsByFormat()
        val years = repository.statsByYear()
        val folders = repository.statsByFolder()
        return StorageOverview(
            totalCount = repository.count().toLong(),
            totalBytes = formats.sumOf { it.totalBytes },
            formats = formats,
            years = years,
            folders = folders,
        )
    }

    // ---- 缩略图缓存 ----

    /** 网格单元格用的缩略图：命中缓存直接给文件，未命中则按需生成并落盘。 */
    suspend fun thumbnailFile(item: MediaEntity): File? = container.thumbnailCache.get(item)

    private val _thumbnailCacheSize = MutableStateFlow(0L)
    val thumbnailCacheSize: StateFlow<Long> = _thumbnailCacheSize

    fun refreshThumbnailCacheSize() {
        viewModelScope.launch { _thumbnailCacheSize.value = container.thumbnailCache.sizeBytes() }
    }

    fun clearThumbnailCache() {
        viewModelScope.launch {
            container.thumbnailCache.clear()
            _thumbnailCacheSize.value = 0L
        }
    }

    fun setThumbnailCacheEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setThumbnailCacheEnabled(enabled) }
    }

    fun setThumbnailCacheMb(mb: Int) {
        viewModelScope.launch { settingsRepository.setThumbnailCacheMb(mb) }
    }

    /** 补齐老图片缩略图（一次性任务，只读照片）；是否仅充电时执行由设置决定。 */
    fun backfillThumbnails() =
        container.requestThumbnailBackfill(settings.value.thumbnailBackfillChargingOnly)

    fun cancelBackfillThumbnails() = container.cancelThumbnailBackfill()

    // ---- 批量收藏 ----

    /**
     * 批量收藏/取消收藏。
     *
     * 取消收藏只能改 App 内的 localFavorite：系统相册的 IS_FAVORITE 是 MediaStore 字段，
     * 侧载应用没有写权限，所以这批里属于"系统收藏"的会原样保留，需要在提示里说清楚。
     * 返回无法取消的张数。
     */
    suspend fun setFavorites(ids: Collection<Long>, favorite: Boolean): Int {
        repository.setLocalFavorites(ids, favorite)
        if (favorite) return 0
        val systemFavorites = runCatching { repository.countSystemFavorites(ids) }.getOrDefault(0)
        if (systemFavorites > 0) {
            postMessage("$systemFavorites 张是系统相册里的收藏，需要在系统相册里取消")
        }
        return systemFavorites
    }

    // ---- M3 回收站（删除先移入回收目录，保留期可配，可一键恢复） ----

    val trashItems: StateFlow<List<TrashItemEntity>> = trashRepository.items
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val trashCount: StateFlow<Int> = trashRepository.countFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val trashTotalBytes: StateFlow<Long> = trashRepository.totalBytesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** 移入回收站（同卷 rename，瞬时完成；不是删除）。保留期跟随设置。 */
    suspend fun moveToTrash(ids: List<Long>): TrashResult =
        trashRepository.moveToTrash(ids, settings.value.retention.days)

    suspend fun restoreFromTrash(ids: List<Long>): RestoreResult = trashRepository.restore(ids)

    suspend fun deleteFromTrashPermanently(ids: List<Long>): DeleteResult =
        trashRepository.deletePermanently(ids)

    suspend fun emptyTrashNow(): DeleteResult = trashRepository.emptyTrash()

    // ---- M3 真实移动（改变实际存储路径） ----

    suspend fun moveTargetFolders(): List<String> = container.moveRepository.targetFolders()

    suspend fun moveMedia(ids: List<Long>, targetRelativePath: String): MoveResult =
        container.moveRepository.moveTo(ids, targetRelativePath, container.batchController)

    // ---- M4 复制 / 重命名 / 文件夹 ----

    suspend fun copyTargetFolders(): List<String> = container.copyRepository.targetFolders()

    suspend fun copyMedia(ids: List<Long>, targetRelativePath: String): FileOpReport =
        container.copyRepository.copyTo(ids, targetRelativePath, container.batchController)

    /** 单张重命名。 */
    suspend fun renameMedia(id: Long, newName: String): FileOpReport =
        container.renameRepository.rename(id, newName)

    /** 批量重命名预览（不落盘，仅算新名字与冲突）。 */
    suspend fun renamePreview(ids: List<Long>, template: String): List<RenamePreview> {
        val items = repository.byIds(ids)
        return container.renameRepository.preview(items, template)
    }

    suspend fun renameBatch(ids: List<Long>, template: String): FileOpReport =
        container.renameRepository.renameBatch(ids, template, container.batchController)

    suspend fun emptyFolders(): List<EmptyFolderEntry> = container.folderOps.listEmptyFolders()

    suspend fun createFolder(relativePath: String): FileOpReport = container.folderOps.createFolder(relativePath)

    suspend fun deleteEmptyFolders(paths: List<String>): FileOpReport =
        container.folderOps.deleteEmptyFolders(paths)

    // ---- M4 批量任务控制（C-06） ----

    val batchState: StateFlow<BatchState> = container.batchController.state

    fun pauseBatch() = container.batchController.pause()

    fun resumeBatch() = container.batchController.resume()

    fun cancelBatch() = container.batchController.cancel()

    // ---- M4 操作历史与撤销（C-07 / C-08） ----

    val operationRecords: StateFlow<List<OperationRecord>> = container.operationHistoryRepository.records
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 撤销一条可撤销的操作（移动/重命名/复制/移入回收站）。 */
    suspend fun undoOperation(logId: Long): FileOpReport =
        container.operationHistoryRepository.undo(logId, settings.value.retention.days)

    private val _lastReport = MutableStateFlow<FileOpReport?>(null)

    /** 最近一次写操作结果：写完后跳报告页用。 */
    val lastReport: StateFlow<FileOpReport?> = _lastReport

    fun publishReport(report: FileOpReport) {
        _lastReport.value = report
    }

    fun clearReport() {
        _lastReport.value = null
    }

    // ---- M4 二级页面导航 ----

    private val _screen = MutableStateFlow<AppScreen?>(null)
    val screen: StateFlow<AppScreen?> = _screen

    fun openScreen(screen: AppScreen) {
        _screen.value = screen
    }

    fun closeScreen() {
        _screen.value = null
    }

    // ---- 一次性提示（Snackbar + 撤销） ----

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: MutableSharedFlow<UiMessage> = _messages

    fun postMessage(text: String, undoTrashIds: List<Long>? = null) {
        _messages.tryEmit(UiMessage(text, undoTrashIds))
    }

    /** 撤销一次"移入回收站"。 */
    suspend fun undoTrash(trashIds: List<Long>) {
        val result = restoreFromTrash(trashIds)
        postMessage(result.message)
    }

    // ---- M3 权限状态 ----

    fun hasAllFilesAccess(): Boolean = StorageAccess.hasAllFilesAccess()

    // ---- 后台与电量设置 ----

    fun setThumbnailBackfillChargingOnly(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setThumbnailBackfillChargingOnly(enabled) }
    }

    fun setAutoResumeIndex(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoResumeIndex(enabled) }
    }

    // ---- 崩溃日志（关于页） ----

    fun latestCrashLog(): String? = CrashLogger.latest(container.appContext)

    fun clearCrashLogs() = CrashLogger.clear(container.appContext)

    // ---- v0.2：图库时间分组粒度（不分组 / 按月 / 按日） ----

    fun setDateGroupMode(mode: DateGroupMode) {
        viewModelScope.launch { settingsRepository.setDateGroupMode(mode) }
    }

    // ---- v0.2：回收站位置可见、可改 ----

    /** 当前设置对应的回收站绝对路径。 */
    suspend fun configuredTrashDir(): String = trashRepository.configuredTrashDir()

    /** 当前设置目录 + 已有条目所在的旧目录（改过位置后老条目仍留在原处）。 */
    suspend fun trashLocations(): List<String> = trashRepository.trashLocations()

    /** 改回收站位置；空串 = 默认 <卷根>/.EropicTrash。 */
    fun setTrashRelativeDir(relativeDir: String) {
        viewModelScope.launch {
            settingsRepository.setTrashRelativeDir(relativeDir)
            postMessage("回收站新位置：" + trashRepository.configuredTrashDir())
        }
    }

    // ---- v0.7：应用内更新（GitHub Release 检查 + 下载 + 调系统安装器） ----

    private val updateChecker = UpdateChecker(container.appContext)

    private val _updateState = MutableStateFlow(UpdateUiState())

    /** 更新卡片状态（含正在下载的进度）。 */
    val updateState: StateFlow<UpdateUiState> = _updateState

    /**
     * 当前安装包的版本名（例如 "0.7.0-debug"）。
     *
     * 直接问 PackageManager 而不是读 BuildConfig.VERSION_NAME：debug 变体会带 "-debug" 后缀，
     * 而 VersionCompare 会剥掉后缀再比较数字段，两者都能用，但 PackageManager 才是"真正装上去的那个"。
     */
    val currentVersionName: String = runCatching {
        val info = container.appContext.packageManager
            .getPackageInfo(container.appContext.packageName, 0)
        info.versionName
    }.getOrNull() ?: BuildConfig.VERSION_NAME

    fun checkForUpdates() {
        if (_updateState.value.checking) return
        viewModelScope.launch {
            _updateState.update { it.copy(checking = true, checkingError = null, message = null) }
            val result = runCatching { updateChecker.check(currentVersionName) }
            val info = result.getOrNull()
            _updateState.update { state ->
                result.exceptionOrNull()?.let { error ->
                    state.copy(checking = false, checkingError = error.message ?: "检查更新失败")
                } ?: if (info == null) {
                    state.copy(
                        checking = false,
                        info = null,
                        updateAvailable = false,
                        message = "已是最新版本（" + currentVersionName + "）",
                    )
                } else {
                    state.copy(
                        checking = false,
                        info = info,
                        // 用版本名比较，不用 versionCode：本项目 versionCode 是构建时间戳，
                        // 本地刚构建的包永远比线上 release 的 versionCode 大，比 versionCode 会永远"已是最新"。
                        updateAvailable = VersionCompare.isNewer(info.versionName, currentVersionName),
                        message = "发现新版本 " + info.versionName,
                    )
                }
            }
        }
    }

    fun downloadUpdate() {
        val info = _updateState.value.info ?: return
        if (_updateState.value.downloading) return
        viewModelScope.launch {
            _updateState.update { it.copy(downloading = true, progress = 0f, message = "开始下载…") }
            val result = runCatching {
                updateChecker.download(info) { fraction ->
                    // download() 在 IO 线程回调；StateFlow 本身线程安全，直接写即可
                    _updateState.update { it.copy(progress = fraction) }
                }
            }
            _updateState.update { state ->
                result.fold(
                    onSuccess = { path ->
                        state.copy(
                            downloading = false,
                            progress = 1f,
                            downloadedPath = path,
                            message = "下载完成，点「安装」继续",
                        )
                    },
                    onFailure = { error ->
                        state.copy(
                            downloading = false,
                            progress = 0f,
                            message = "下载失败：" + (error.message ?: "未知错误"),
                        )
                    },
                )
            }
        }
    }

    /**
     * 调系统安装器安装已下载的 APK。
     *
     * 必须走 FileProvider：Android 7+ 直接传 file:// 会抛 FileUriExposedException；
     * 临时授权 FLAG_GRANT_READ_URI_PERMISSION，安装器才能读到缓存目录里的文件。
     * 侧载场景下系统还会要求用户为「Eropic」打开「安装未知应用」权限，这一步只能在系统设置里做。
     */
    fun installUpdate() {
        val path = _updateState.value.downloadedPath ?: return
        val file = File(path)
        if (!file.exists()) {
            _updateState.update { it.copy(message = "安装包不存在，请重新下载", downloadedPath = null) }
            return
        }
        val context = container.appContext
        runCatching {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure { error ->
            _updateState.update {
                it.copy(message = "无法启动安装器：" + (error.message ?: "未知错误"))
            }
        }
    }

    fun clearUpdateMessage() {
        _updateState.update { it.copy(message = null, checkingError = null) }
    }

    // ---- v0.2：识图（本地相似图检索 + 联网以图搜图） ----

    // v0.6：SauceNAO 的 key 内置在 OnlineImageSearch 里，不再从设置读，构造参数只剩 ContentResolver
    private val onlineSearchClient = OnlineImageSearch(container.appContext.contentResolver)

    // v0.7：本地识图接上缩略图缓存（颜色精排时读 320px 缩略图，比解码原图快得多），
    // 并把联网识图客户端传进去，「找同角色」才能先问 SauceNAO / ascii2d 这是谁。
    private val imageSearch = ImageSearchRepository(
        context = container.appContext,
        database = container.database,
        thumbnailCache = container.thumbnailCache,
        onlineImageSearch = onlineSearchClient,
    )

    private val _similarityHits = MutableStateFlow<List<SimilarImageHit>>(emptyList())

    /** 识图结果，按相似度从高到低。 */
    val similarityHits: StateFlow<List<SimilarImageHit>> = _similarityHits

    private val _similaritySearching = MutableStateFlow(false)
    val similaritySearching: StateFlow<Boolean> = _similaritySearching

    private var similaritySourceId: Long? = null
    private var similaritySourceUri: Uri? = null
    private var similarityKeyword: String? = null

    /** 以图库里的某张图作为源图检索相似图。 */
    fun findSimilarByMediaId(mediaId: Long) {
        similaritySourceId = mediaId
        similaritySourceUri = null
        runSimilaritySearch()
    }

    /** 以系统相册选出的 Uri 作为源图检索。 */
    fun findSimilarByUri(uri: Uri) {
        similaritySourceUri = uri
        similaritySourceId = null
        runSimilaritySearch()
    }

    /** 关键词辅助（文件名/目录），空串表示不过滤；有源图时立刻重跑。 */
    fun setSimilarityKeyword(text: String) {
        similarityKeyword = text.trim().ifEmpty { null }
        if (similaritySourceId != null || similaritySourceUri != null) runSimilaritySearch()
    }

    private fun runSimilaritySearch() {
        viewModelScope.launch {
            _similaritySearching.value = true
            val query = SimilarityQuery(keyword = similarityKeyword)
            val hits = runCatching {
                val id = similaritySourceId
                val uri = similaritySourceUri
                when {
                    id != null -> imageSearch.findByMediaId(id, query)
                    uri != null -> imageSearch.findByUri(uri, query)
                    else -> emptyList()
                }
            }.getOrElse { emptyList() }
            _similarityHits.value = hits
            _similaritySearching.value = false
        }
    }

    // ---- v0.7：找同角色（联网问「这是谁」+ 本地颜色/结构排序） ----

    private val _characterResult = MutableStateFlow<CharacterMatchResult?>(null)

    /** 「找同角色」结果；null 表示还没点过。 */
    val characterResult: StateFlow<CharacterMatchResult?> = _characterResult

    private val _characterSearching = MutableStateFlow(false)
    val characterSearching: StateFlow<Boolean> = _characterSearching

    /** 以图库里的图找同角色。 */
    fun findCharacterByMediaId(mediaId: Long) {
        similaritySourceId = mediaId
        similaritySourceUri = null
        runCharacterMatch()
    }

    /** 以系统选图找同角色。 */
    fun findCharacterByUri(uri: Uri) {
        similaritySourceUri = uri
        similaritySourceId = null
        runCharacterMatch()
    }

    /**
     * 与 [findCharacterByMediaId] 相同，但把结果直接返回给调用方（识图页要自己管加载态）。
     *
     * 为什么同时存在两套：识图页把结果存在页面级 session 里（进程内可恢复），
     * 不想再额外订阅一份 StateFlow；而其他地方用 [findCharacterByMediaId] 读 [characterResult] 更方便。
     */
    suspend fun findCharacterMatchesByMediaId(mediaId: Long): CharacterMatchResult? {
        similaritySourceId = mediaId
        similaritySourceUri = null
        return imageSearch.findCharacterByMediaId(mediaId, SimilarityQuery(keyword = similarityKeyword))
    }

    /** 与 [findCharacterByUri] 相同，但把结果直接返回给调用方。 */
    suspend fun findCharacterMatchesByUri(uri: Uri): CharacterMatchResult? {
        similaritySourceUri = uri
        similaritySourceId = null
        return imageSearch.findCharacterByUri(uri, SimilarityQuery(keyword = similarityKeyword))
    }

    fun clearCharacterResult() {
        _characterResult.value = null
    }

    private fun runCharacterMatch() {
        viewModelScope.launch {
            _characterSearching.value = true
            val id = similaritySourceId
            val uri = similaritySourceUri
            val query = SimilarityQuery(keyword = similarityKeyword)
            _characterResult.value = runCatching {
                when {
                    id != null -> imageSearch.findCharacterByMediaId(id, query)
                    uri != null -> imageSearch.findCharacterByUri(uri, query)
                    else -> null
                }
            }.getOrNull()
            _characterSearching.value = false
        }
    }

    /** 识图页「从图库选图」用的候选：最近的一批图片。 */
    suspend fun searchSourceCandidates(limit: Int = 60): List<MediaEntity> =
        repository.byIds(repository.ids(SortOrder.DATE_DESC, false).take(limit))

    // ---- v0.5：文件夹级操作（重命名 / 删除到回收站 / 置顶 / 更换封面） ----

    /** 重命名文件夹（真实改磁盘目录名，并改写该目录下所有图片的索引路径）。 */
    suspend fun renameFolder(relativePath: String, newName: String): FolderOpResult =
        container.folderRepository.renameFolder(relativePath, newName)

    /** 删除文件夹：目录下图片全部移入回收站（可恢复），空目录随后删掉。 */
    suspend fun trashFolder(relativePath: String): FolderOpResult =
        container.folderRepository.trashFolder(relativePath)

    /** 置顶 / 取消置顶（偏好，不涉及文件系统）。 */
    fun setFolderPinned(relativePath: String, pinned: Boolean) {
        viewModelScope.launch {
            container.folderRepository.setPinned(relativePath, pinned)
            postMessage(if (pinned) "已置顶该文件夹" else "已取消置顶")
        }
    }

    /** 更换图集封面；[mediaId] = null 表示恢复默认（该目录最新一张图）。 */
    fun setFolderCover(relativePath: String, mediaId: Long?) {
        viewModelScope.launch {
            container.folderRepository.setCover(relativePath, mediaId)
            postMessage(if (mediaId == null) "已恢复默认封面" else "已更换图集封面")
        }
    }

    /** 该目录用户自定义的封面 id（没有则 null，界面回落到最新一张）。 */
    fun folderCoverOverride(relativePath: String): Long? =
        settings.value.folderCovers[relativePath]

    /** 该目录是否置顶。 */
    fun isFolderPinned(relativePath: String): Boolean =
        settings.value.pinnedFolders.contains(relativePath)

    /** 更换封面时的候选图：该目录（含子目录）最近的 [limit] 张。 */
    suspend fun folderImagesForCover(relativePath: String, limit: Int = 300): List<MediaEntity> {
        val path = relativePath.trim().trimEnd('/')
        val ids = repository.ids(
            SortOrder.DATE_DESC,
            false,
            folderPath = path,
            includeSubfolders = true,
        ).take(limit)
        return if (ids.isEmpty()) emptyList() else repository.byIds(ids)
    }

    /** 联网识图：成功返回 resultUrl 交给 UI 打开浏览器，失败返回 message。 */
    suspend fun onlineImageSearch(provider: ReverseImageProvider): OnlineSearchResult {
        val uri = similaritySourceUri ?: similaritySourceId?.let { MediaUris.of(it) }
            ?: return OnlineSearchResult(provider, false, null, "请先选择一张图片")
        return runCatching { onlineSearchClient.search(uri, provider) }
            .getOrElse { OnlineSearchResult(provider, false, null, "联网识图失败：" + (it.message ?: "网络异常")) }
    }

    // ---- v0.8：文件夹真实计数（分页的 itemCount 只是「已加载数」，不能当总数显示） ----

    suspend fun folderCount(relativePath: String, includeSubfolders: Boolean): Long =
        repository.count(
            MediaFilter(folders = listOf(relativePath), includeSubfolders = includeSubfolders),
        )

    suspend fun folderTotalBytes(relativePath: String, includeSubfolders: Boolean): Long =
        repository.sumSize(
            MediaFilter(folders = listOf(relativePath), includeSubfolders = includeSubfolders),
        )

    // ---- v0.8：文件夹长按多选后的批量操作 ----

    /**
     * 批量把文件夹移入回收站。
     *
     * 一个文件夹的操作本身是「把目录下所有图片移入回收站 + 删掉空目录」，没有跨文件夹事务，
     * 所以这里逐个执行再汇总：成功数、受影响图片数、失败明细都带回给界面做结果提示。
     * 根目录（空串）不允许删除，列表页已过滤，这里再兜一次底。
     */
    suspend fun trashFolders(paths: List<String>): FolderOpResult {
        val targets = paths.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return FolderOpResult(ok = false, message = "没有选中文件夹")
        var changed = 0
        var succeeded = 0
        val failures = mutableListOf<OpFailure>()
        for (path in targets) {
            val result = runCatching { container.folderRepository.trashFolder(path) }.getOrNull()
            if (result == null) {
                failures += OpFailure(path, "操作失败")
                continue
            }
            changed += result.changed
            if (result.ok) {
                succeeded++
            } else {
                failures += result.failures.ifEmpty { listOf(OpFailure(path, result.message)) }
            }
        }
        val message = if (failures.isEmpty()) {
            "已把 " + succeeded + " 个文件夹里的 " + changed + " 张图片移入回收站"
        } else {
            "已处理 " + succeeded + "/" + targets.size + " 个文件夹，" + failures.size + " 项失败"
        }
        return FolderOpResult(ok = failures.isEmpty(), message = message, changed = changed, failures = failures)
    }

    /** 批量置顶 / 取消置顶（纯偏好，不碰文件系统）。 */
    fun setFoldersPinned(paths: List<String>, pinned: Boolean) {
        val targets = paths.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            targets.forEach { container.folderRepository.setPinned(it, pinned) }
            postMessage(
                if (pinned) "已置顶 " + targets.size + " 个文件夹"
                else "已取消置顶 " + targets.size + " 个文件夹",
            )
        }
    }

    // ---- v0.8：视频 / GIF 独立统计（视频只识别统计，不改变文件位置） ----

    val videoCount: Flow<Int> = container.videoRepository.countFlow()
    val videoTotalBytes: Flow<Long> = container.videoRepository.totalBytesFlow()
    val gifCount: Flow<Int> = container.database.mediaDao().gifCountFlow()
    val gifTotalBytes: Flow<Long> = container.database.mediaDao().gifBytesFlow()

    suspend fun loadVideos(): List<VideoEntity> = container.videoRepository.list()

    /** 视频封面：系统缩略图接口 loadThumbnail，失败返回 null 由界面显示占位图标。 */
    suspend fun videoThumbnail(id: Long): Bitmap? = container.videoRepository.thumbnail(id)

    /** 手动触发一次视频扫描，返回中文结果提示。 */
    suspend fun refreshVideos(): String {
        val result = container.videoRepository.syncFromMediaStore(settings.value.scanScope())
        return if (result.scanned > 0) {
            "已索引 " + result.scanned + " 个视频"
        } else {
            "没有扫描到视频（检查是否已授予「照片和视频」权限）"
        }
    }

    /** GIF 独立视图：GIF 仍然算图片，仍参与相册与去重，只是这里单独列出来。 */
    fun gifMedia(): Flow<PagingData<MediaEntity>> =
        repository.pagingData(filter = GIF_FILTER, sort = SortOrder.DATE_DESC).cachedIn(viewModelScope)

    suspend fun gifIds(): List<Long> = repository.ids(filter = GIF_FILTER, sort = SortOrder.DATE_DESC)

    companion object {
        /** v0.8：GIF 视图的固定筛选（mimeType=image/gif），不影响图片索引口径。 */
        private val GIF_FILTER = MediaFilter(mimeTypes = setOf("image/gif"))
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { EropicViewModel(container) }
        }
    }
}