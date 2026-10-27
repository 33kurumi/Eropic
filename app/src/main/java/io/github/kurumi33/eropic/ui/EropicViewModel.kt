package io.github.kurumi33.eropic.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.kurumi33.eropic.AppContainer
import io.github.kurumi33.eropic.core.database.entity.DuplicateGroupEntity
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.index.IndexState
import io.github.kurumi33.eropic.core.media.SortOrder
import io.github.kurumi33.eropic.core.analysis.detectors.LargeFileResult
import io.github.kurumi33.eropic.core.model.FolderRow
import io.github.kurumi33.eropic.core.query.MediaFilter
import io.github.kurumi33.eropic.core.settings.AppSettings
import io.github.kurumi33.eropic.core.settings.ThemeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** 大图查看器目标：有序 id 列表 + 起始位置。放在 ViewModel 里以便旋转屏幕后不丢。 */
data class ViewerTarget(val ids: List<Long>, val startIndex: Int)

class EropicViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.mediaRepository
    private val duplicateDao = container.database.duplicateDao()
    private val settingsRepository = container.settingsRepository

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

    // ---- 图库 ----

    @OptIn(ExperimentalCoroutinesApi::class)
    val media: Flow<PagingData<MediaEntity>> = sortOrder.flatMapLatest { sort ->
        _favoritesOnly.flatMapLatest { favoritesOnly ->
            repository.pagingData(sort = sort, favoritesOnly = favoritesOnly)
        }
    }.cachedIn(viewModelScope)

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

    fun folderMedia(path: String, includeSubfolders: Boolean): Flow<PagingData<MediaEntity>> =
        repository.pagingData(
            sort = sortOrder.value,
            favoritesOnly = _favoritesOnly.value,
            folderPath = path,
            includeSubfolders = includeSubfolders,
        )

    suspend fun folderIds(path: String, includeSubfolders: Boolean): List<Long> = repository.ids(
        sort = sortOrder.value,
        favoritesOnly = _favoritesOnly.value,
        folderPath = path,
        includeSubfolders = includeSubfolders,
    )

    // ---- 清理 ----

    val groups: StateFlow<List<DuplicateGroupEntity>> = duplicateDao.groupsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun members(groupId: Long): List<MediaEntity> = duplicateDao.members(groupId)

    fun startIndex() = container.indexCoordinator.start()

    fun cancelIndex() = container.indexCoordinator.cancel()

    fun rebuildIndex() {
        viewModelScope.launch {
            repository.clear()
            container.indexCoordinator.start()
        }
    }

    // ---- M2 检索与检测 ----

    suspend fun largeFiles(): LargeFileResult = container.largeFileDetector.find()

    suspend fun oldPhotos(nowMs: Long = System.currentTimeMillis()): List<MediaEntity> =
        container.oldPhotoDetector.find(nowMs)

    suspend fun screenshots(): List<MediaEntity> = container.screenshotDetector.find()

    suspend fun blurred(): List<MediaEntity> = container.blurDetector.find()

    fun filteredMedia(filter: MediaFilter): Flow<PagingData<MediaEntity>> =
        repository.pagingData(filter, sortOrder.value)

    suspend fun filteredIds(filter: MediaFilter): List<Long> = repository.ids(filter, sortOrder.value)

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

    /** 充电时补齐老图片缩略图（一次性任务，只读照片）。 */
    fun backfillThumbnails() = container.requestThumbnailBackfill()

    fun cancelBackfillThumbnails() = container.cancelThumbnailBackfill()

    // ---- 批量收藏 ----

    suspend fun setFavorites(ids: Collection<Long>, favorite: Boolean) =
        repository.setLocalFavorites(ids, favorite)

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { EropicViewModel(container) }
        }
    }
}
