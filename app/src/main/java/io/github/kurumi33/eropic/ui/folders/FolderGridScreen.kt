package io.github.kurumi33.eropic.ui.folders

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.EropicApplication
import io.github.kurumi33.eropic.core.database.dao.MediaDao
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.folders.FolderNode
import io.github.kurumi33.eropic.core.folders.findFolderNode
import io.github.kurumi33.eropic.core.folders.folderStatsOf
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.core.media.SortOrder
import io.github.kurumi33.eropic.core.settings.AppSettings
import io.github.kurumi33.eropic.ui.AppScreen
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.EropicTopBar
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * 网格列数范围（v0.6 由 3..6 放开到 2..8）。
 * 直接引用 AppSettings 常量，与设置页、SettingsRepository 的钳制保持同一来源。
 */
private const val GRID_COLUMNS_MIN = AppSettings.MIN_GRID_COLUMNS
private const val GRID_COLUMNS_MAX = AppSettings.MAX_GRID_COLUMNS

/**
 * 网格模式：只展示当前这一层的子文件夹（不做图片网格）。
 *
 * 打开方式：点卡片下钻到下一层；长按卡片弹出文件夹操作弹层（打开 / 选择 / 重命名 / 封面 / 置顶 / 删除）。
 * 想看这一层的照片请切到列表模式点这一行，或用弹层里的「打开 / 选择」。
 *
 * 浏览位置（当前层级）由调用方 FoldersSection 持有，所以转屏、切 Tab 都能回到原处。
 */
@Composable
internal fun FolderGridScreen(
    viewModel: EropicViewModel,
    tree: List<FolderNode>,
    path: String,
    includeSubfolders: Boolean,
    refreshToken: Long,
    activeSort: SortOrder,
    onSortSelected: (SortOrder) -> Unit,
    onToggleIncludeSubfolders: (Boolean) -> Unit,
    onOpenFolder: (String) -> Unit,
    onShowActions: (FolderNode) -> Unit,
    onUp: () -> Unit,
    onSwitchToList: () -> Unit,
    // v0.8 多选：状态由 FoldersSection 持有，与列表页各自独立。
    selecting: Boolean,
    selectedPaths: List<String>,
    hasWriteAccess: Boolean,
    onEnterSelection: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onExitSelection: () -> Unit,
    onSelectAll: (List<String>) -> Unit,
    onPinSelected: () -> Unit,
    onUnpinSelected: () -> Unit,
    onTrashSelected: () -> Unit,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // 当前层级的子目录：路径已被删除时退回顶层，避免空白页。
    val nodes = remember(tree, path) {
        if (path.isEmpty()) tree else findFolderNode(tree, path)?.children.orEmpty()
    }
    val pinnedPaths = remember(settings.pinnedFolders) { pinnedPathSet(settings.pinnedFolders) }
    val selectedSet = remember(selectedPaths) { selectedPaths.toSet() }
    // 置顶的目录排前面，与列表模式保持同一种顺序。
    val ordered = remember(nodes, pinnedPaths) { orderPinnedFolders(nodes, pinnedPaths) }
    // 「包含子文件夹」只影响后续打开的文件夹，以及这里显示的张数统计口径。
    val stats = remember(tree, path, includeSubfolders) {
        folderStatsOf(tree, path, includeSubfolders)
    }

    // 封面：优先用用户在「更换封面」里指定的图片，没有就取该目录下最新的一张。
    val mediaDao: MediaDao? = remember(context) {
        (context.applicationContext as? EropicApplication)?.container?.database?.mediaDao()
    }
    val thumbnailsEnabled = settings.thumbnailCacheEnabled
    val coverLoader = remember(mediaDao, viewModel, thumbnailsEnabled) {
        FolderCoverLoader(
            loadCover = { folderPath -> mediaDao?.folderCover(coverPatternOf(folderPath)) },
            loadThumb = { item -> if (thumbnailsEnabled) viewModel.thumbnailFile(item) else null },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selecting) {
            FolderSelectionBar(
                count = selectedPaths.size,
                onClose = onExitSelection,
                onSelectAll = {
                    // 「全选」只选这一层看得到的文件夹卡片。
                    onSelectAll(
                        ordered
                            .map { node -> node.path }
                            .filter { nodePath -> nodePath.isNotEmpty() },
                    )
                },
                onPin = onPinSelected,
                onUnpin = onUnpinSelected,
                onTrash = onTrashSelected,
                hasWriteAccess = hasWriteAccess,
            )
        } else {
            EropicTopBar(
                title = folderNameOf(tree, path),
                subtitle = formatCount(ordered.size.toLong()) + " 个子文件夹 · " +
                    formatCount(stats.count) + " 张",
                onBack = if (path.isNotEmpty()) onUp else null,
                actions = {
                    // 搜索入口：与列表模式一致，直接打开搜索页。
                    IconButton(onClick = { viewModel.openScreen(AppScreen.Search) }) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "搜索图片",
                        )
                    }
                    // 列数缩放：范围与设置页、SettingsRepository 同源（AppSettings 2..8），
                    // 到边界就禁用按钮，避免点了没反应。
                    CompactIconButton(
                        imageVector = Icons.Filled.Remove,
                        contentDescription = "减少每行图片列数",
                        enabled = settings.gridColumns > GRID_COLUMNS_MIN,
                        onClick = { viewModel.setGridColumns(settings.gridColumns - 1) },
                    )
                    CompactIconButton(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "增加每行图片列数",
                        enabled = settings.gridColumns < GRID_COLUMNS_MAX,
                        onClick = { viewModel.setGridColumns(settings.gridColumns + 1) },
                    )
                    FolderPageMenu(
                        activeSort = activeSort,
                        onSortSelected = onSortSelected,
                        includeSubfolders = includeSubfolders,
                        onToggleIncludeSubfolders = onToggleIncludeSubfolders,
                    )
                    IconButton(onClick = onSwitchToList) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = "切换为列表视图",
                        )
                    }
                },
            )
        }

        if (ordered.isEmpty()) {
            EmptySubfolders(
                hasPath = path.isNotEmpty(),
                onSwitchToList = onSwitchToList,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(settings.gridColumns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = ordered, key = { node -> node.path }) { node ->
                    val pinned = node.path.trim('/').lowercase() in pinnedPaths
                    val overrideId = folderCoverIdOf(settings.folderCovers, node.path)
                    FolderCard(
                        node = node,
                        pinned = pinned,
                        showMeta = settings.showMeta,
                        overrideCoverId = overrideId,
                        refreshToken = refreshToken,
                        coverLoader = coverLoader,
                        viewModel = viewModel,
                        thumbnailsEnabled = thumbnailsEnabled,
                        selecting = selecting,
                        selected = node.path in selectedSet,
                        // 选择模式下点按 / 长按都只切换选中，不再进入目录或弹操作层。
                        // 根目录是合成节点（path 为空），不可选：非选择模式下长按仍打开操作弹层。
                        onClick = {
                            if (!selecting) {
                                onOpenFolder(node.path)
                            } else if (node.path.isNotEmpty()) {
                                onToggleSelect(node.path)
                            }
                        },
                        onLongClick = {
                            if (selecting) {
                                if (node.path.isNotEmpty()) onToggleSelect(node.path)
                            } else if (node.path.isNotEmpty()) {
                                onEnterSelection(node.path)
                            } else {
                                onShowActions(node)
                            }
                        },
                        onShowActions = { onShowActions(node) },
                    )
                }
            }
        }
    }
}

/**
 * 顶栏上的小号图标按钮（列数加减用）。
 *
 * 默认 IconButton 占 48dp，网格页顶栏同时有搜索 / 减 / 加 / 三点菜单 / 切列表五个动作，
 * 用 32dp 的小按钮能把空间留给标题和副标题。
 */
@Composable
private fun CompactIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = if (enabled) 1f else 0.38f,
            ),
        )
    }
}

/** 一个文件夹卡片：方形封面 + 名字 +（打开元信息时）张数与大小。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    node: FolderNode,
    pinned: Boolean,
    showMeta: Boolean,
    overrideCoverId: Long?,
    refreshToken: Long,
    coverLoader: FolderCoverLoader?,
    viewModel: EropicViewModel,
    thumbnailsEnabled: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShowActions: () -> Unit,
) {
    // 封面是挂起加载（查 DAO + 生成缩略图），用 produceState 让它随卡片进入组合自动开始。
    val cover by produceState<FolderCover?>(
        null,
        // 影响封面的一切都打包成一个键：路径、刷新令牌、用户指定的封面、以及加载器本身。
        listOf(node.path, refreshToken, overrideCoverId, coverLoader),
    ) {
        // 用户指定的封面优先，但它可能已经被删或已不在设备上，这种情况退回自动封面。
        if (overrideCoverId != null) {
            val entity = runCatching { viewModel.mediaById(overrideCoverId) }.getOrNull()
            if (entity != null && entity.isPresent) {
                value = FolderCover(
                    id = entity.id,
                    file = if (thumbnailsEnabled) viewModel.thumbnailFile(entity) else null,
                )
                return@produceState
            }
        }
        value = coverLoader?.cover(node.path)
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp),
                    )
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(bottom = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val current = cover
            if (current != null) {
                AsyncImage(
                    model = current.file?.let { file -> android.net.Uri.fromFile(file) }
                        ?: MediaUris.of(current.id),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (pinned) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                        .padding(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "已置顶",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .padding(3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "已选中",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            // 长按现在用于进入多选，单张卡片的操作弹层改由右下角 ⋮ 打开，功能不丢。
            // 用 Box + clickable 而不是 IconButton：后者最小 48dp 触控区会撑出封面。
            if (!selecting) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .clickable(onClick = onShowActions),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "文件夹操作",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = node.name,
            style = MaterialTheme.typography.titleMedium,
            color = Color.Unspecified,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 6.dp),
        )
        if (showMeta) {
            Text(
                text = formatCount(node.totalCount) + " 张 · " + formatBytes(node.totalBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 2.dp),
            )
        }
    }
}

/** 这一层没有子文件夹时的提示；根层级不会走到这里（根层级为空说明整个库为空）。 */
@Composable
private fun EmptySubfolders(
    hasPath: Boolean,
    onSwitchToList: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (hasPath) "这个文件夹里没有子文件夹" else "还没有文件夹",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = if (hasPath) {
                    "切到列表模式，点这个文件夹就能看这里的照片。"
                } else {
                    "请先到图库页建立索引。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (hasPath) {
                Text(
                    text = "切换到列表模式",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onSwitchToList)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** 一张文件夹封面的加载结果：id 用于兜底直接读原图，file 是命中缩略图缓存时的本地文件。 */
internal data class FolderCover(val id: Long, val file: File?)

/**
 * 文件夹封面加载器：限制同时查库 / 生成缩略图的并发（[Semaphore] 3），
 * 并把结果（包括「没有封面」的 null）按目录路径缓存，滚动来回不会重复查。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class FolderCoverLoader(
    private val loadCover: suspend (String) -> MediaEntity?,
    private val loadThumb: suspend (MediaEntity) -> File?,
) {
    private val permits = Semaphore(permits = 3)
    private val cache = HashMap<String, FolderCover?>()

    suspend fun cover(path: String): FolderCover? {
        if (cache.containsKey(path)) return cache[path]
        return permits.withPermit {
            // 等锁期间可能已被其它卡片填过，二次检查避免重复加载。
            if (cache.containsKey(path)) return@withPermit cache[path]
            val cover = runCatching { load(path) }.getOrNull()
            cache[path] = cover
            cover
        }
    }

    private suspend fun load(path: String): FolderCover? {
        val item = loadCover(path) ?: return null
        return FolderCover(id = item.id, file = loadThumb(item))
    }
}

/** 目录下最新一张图片的 LIKE 前缀模式（目录路径已带结尾斜杠）。 */
internal fun coverPatternOf(path: String): String = escapeLike(path) + "%"

/** 转义 LIKE 通配符：先转义反斜杠本身，再转义 % 和 _。 */
private fun escapeLike(raw: String): String =
    raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
