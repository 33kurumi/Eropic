package io.github.kurumi33.eropic.ui.folders

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.EropicApplication
import io.github.kurumi33.eropic.core.database.dao.MediaDao
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.folders.FolderCrumb
import io.github.kurumi33.eropic.core.folders.FolderNode
import io.github.kurumi33.eropic.core.folders.breadcrumbsOf
import io.github.kurumi33.eropic.core.folders.findFolderNode
import io.github.kurumi33.eropic.core.folders.folderStatsOf
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.core.media.SortOrder
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * 文件夹网格页：像相册首页那样展示"当前这一层的文件夹卡片"，可以逐级下钻。
 *
 * 和列表模式的区别是列表一次显示整棵树，这里一次只显示一层：
 * 顶部是面包屑 + 「查看本层全部图片」+「包含子文件夹」，下面是子文件夹卡片，再下面是图片网格。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderGridScreen(
    viewModel: EropicViewModel,
    tree: List<FolderNode>,
    path: String,
    includeSubfolders: Boolean,
    refreshToken: Long,
    sortOverride: SortOrder?,
    onSortSelected: (SortOrder) -> Unit,
    onToggleIncludeSubfolders: (Boolean) -> Unit,
    onOpenFolder: (String) -> Unit,
    onUp: () -> Unit,
    onOpenImages: () -> Unit,
    onSwitchToList: () -> Unit,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val columns = settings.gridColumns.coerceIn(2, 4)
    val thumbnailsEnabled = settings.thumbnailCacheEnabled

    // 排序与本页一致：用户在外层（目录树/网格切换处）选过就跟着外层走，否则跟随图库当前排序。
    val gallerySort by viewModel.sortOrder.collectAsStateWithLifecycle()
    val activeSort = sortOverride ?: gallerySort

    // 根层级就是整库：既没有"上一级"，聚合开关也没有意义（两种取值查到的都是全部照片）。
    val includeSubfoldersEnabled = path.isNotEmpty()
    val effectiveIncludeSubfolders = includeSubfolders && includeSubfoldersEnabled

    // 当前层的子文件夹：根层级取所有顶层节点，其它层级取该节点的 children。
    val subfolders = remember(tree, path) {
        if (path.isEmpty()) {
            tree.filter { it.path.isNotEmpty() }
        } else {
            findFolderNode(tree, path)?.children ?: emptyList<FolderNode>()
        }
    }
    val stats = remember(tree, path, effectiveIncludeSubfolders) {
        folderStatsOf(tree, path, effectiveIncludeSubfolders)
    }
    val crumbs = remember(path) { breadcrumbsOf(path) }
    val title = crumbs.lastOrNull()?.name ?: "根目录"

    // 只有 path/聚合开关变化时才重新订阅分页流，开关切换会换一条新 Flow。
    val images = remember(path, effectiveIncludeSubfolders, activeSort) {
        viewModel.folderMedia(path, effectiveIncludeSubfolders, activeSort)
    }.collectAsLazyPagingItems()

    // ViewModel 没有暴露"按目录取最新一张"的 API，这里直连 AppContainer 的 DAO；
    // 取不到容器（理论上不会发生）时封面降级成文件夹图标，不影响浏览。
    val mediaDao: MediaDao? = remember(context) {
        (context.applicationContext as? EropicApplication)?.container?.database?.mediaDao()
    }
    val coverLoader = remember(mediaDao, viewModel, thumbnailsEnabled, refreshToken) {
        mediaDao?.let { dao ->
            FolderCoverLoader(
                loadCover = { folderPath -> dao.folderCover(coverPatternOf(folderPath)) },
                loadThumb = { item -> if (thumbnailsEnabled) viewModel.thumbnailFile(item) else null },
            )
        }
    }

    // 系统返回键：在子层级里先回上一级，根层级交给外层（退出 App 由 Activity 决定）。
    if (path.isNotEmpty()) {
        BackHandler { onUp() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                if (path.isNotEmpty()) {
                    IconButton(onClick = onUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回上一级",
                        )
                    }
                }
            },
            title = {
                Column {
                    Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = buildString {
                            append(formatCount(subfolders.size.toLong()))
                            append(" 个子文件夹 · ")
                            append(formatCount(stats.count))
                            append(" 张")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                FolderSortMenu(activeSort = activeSort, onSelect = onSortSelected)
                IconButton(onClick = onSwitchToList) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = "切换为列表视图",
                    )
                }
            },
        )

        BreadcrumbBar(crumbs = crumbs, currentPath = path, onOpen = onOpenFolder)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onOpenImages,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (effectiveIncludeSubfolders) "查看全部照片" else "查看本层全部图片",
                )
            }
        }

        SubfolderModeBar(
            includeSubfolders = effectiveIncludeSubfolders,
            enabled = includeSubfoldersEnabled,
            onToggle = onToggleIncludeSubfolders,
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (subfolders.isNotEmpty()) {
                item(key = "folders_header", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader("子文件夹 · " + formatCount(subfolders.size.toLong()) + " 个")
                }
                items(
                    count = subfolders.size,
                    key = { index -> "folder:" + subfolders[index].path },
                ) { index ->
                    val node = subfolders[index]
                    FolderCard(
                        node = node,
                        showMeta = settings.showMeta,
                        coverLoader = coverLoader,
                        refreshToken = refreshToken,
                        onClick = { onOpenFolder(node.path) },
                    )
                }
            }

            item(key = "images_header", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(
                    text = buildString {
                        append(if (effectiveIncludeSubfolders) "含子文件夹的图片" else "本文件夹的图片")
                        append(" · ")
                        append(formatCount(stats.count))
                        append(" 张")
                    },
                )
            }

            if (images.itemCount == 0) {
                item(key = "images_empty", span = { GridItemSpan(maxLineSpan) }) {
                    if (images.loadState.refresh is LoadState.Loading) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    } else {
                        Text(
                            text = "这个文件夹下没有照片",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
                }
            } else {
                items(count = images.itemCount) { index ->
                    val item = images[index]
                    if (item == null) {
                        // 分页还没把这一页取回来：先占位，避免滚动时高度跳动。
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    } else {
                        ImageCell(
                            item = item,
                            thumbnailsEnabled = thumbnailsEnabled,
                            thumbnailProvider = { media -> viewModel.thumbnailFile(media) },
                            onClick = {
                                scope.launch {
                                    val ids = viewModel.folderIds(path, effectiveIncludeSubfolders, activeSort)
                                    val startIndex = ids.indexOf(item.id)
                                    // 索引正在更新时可能查不到这一张，别打开成别的照片。
                                    if (startIndex >= 0) {
                                        onOpenViewer(ids, startIndex)
                                    } else {
                                        onOpenViewer(listOf(item.id), 0)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 面包屑：根目录 → 逐级目录，当前层高亮且不能再点。 */
@Composable
private fun BreadcrumbBar(
    crumbs: List<FolderCrumb>,
    currentPath: String,
    onOpen: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(count = crumbs.size, key = { index -> "crumb:" + crumbs[index].path }) { index ->
            val crumb = crumbs[index]
            val isCurrent = crumb.path == currentPath
            Text(
                text = crumb.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (isCurrent) {
                    Modifier.padding(horizontal = 2.dp)
                } else {
                    Modifier
                        .clickable { onOpen(crumb.path) }
                        .padding(horizontal = 2.dp)
                },
            )
            if (index != crumbs.lastIndex) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** 文件夹卡片：封面 + 名字 + 张数/大小（设置里关掉显示信息时只留名字）。 */
@Composable
private fun FolderCard(
    node: FolderNode,
    showMeta: Boolean,
    coverLoader: FolderCoverLoader?,
    refreshToken: Long,
    onClick: () -> Unit,
) {
    // 每张卡片自己懒加载封面：滚出屏幕后 produceState 的协程会随组合一起取消。
    val cover by produceState<FolderCover?>(
        initialValue = null,
        node.path,
        refreshToken,
        coverLoader,
    ) {
        value = coverLoader?.cover(node.path)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
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
            if (current == null) {
                // 还没加载出来（或这个目录确实没有照片）：先给文件夹图标。
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val file = current.file
                AsyncImage(
                    model = if (file != null) Uri.fromFile(file) else MediaUris.of(current.id),
                    contentDescription = node.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = node.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp),
        )
        if (showMeta) {
            Text(
                text = formatCount(node.totalCount) + " 张 · " + formatBytes(node.totalBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
            )
        } else {
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** 图片格子：优先用缩略图，没有缩略图就直接读原图（和 MediaGrid 的降级逻辑一致）。 */
@Composable
private fun ImageCell(
    item: MediaEntity,
    thumbnailsEnabled: Boolean,
    thumbnailProvider: suspend (MediaEntity) -> File?,
    onClick: () -> Unit,
) {
    val thumb by produceState<File?>(initialValue = null, item.id, thumbnailsEnabled) {
        value = if (thumbnailsEnabled) {
            runCatching { thumbnailProvider(item) }.getOrNull()
        } else {
            null
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        val file = thumb
        AsyncImage(
            model = if (file != null) Uri.fromFile(file) else MediaUris.of(item.id),
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 网格里的分组小标题，跨整行显示。 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
    )
}

/** 封面加载结果：id 用来回退到 MediaUris，file 是已经生成好的缩略图（可能还没生成）。 */
private data class FolderCover(
    val id: Long,
    val file: File?,
)

/**
 * 文件夹封面加载器。
 *
 * 同屏可能有几十张卡片，如果一起查库 + 解码缩略图，滚动会很卡，所以：
 * 1. 用 [Semaphore] 限制同时加载的封面数量；
 * 2. 结果（包括"这个目录没有照片"）按目录缓存，来回切层级不重复查。
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class FolderCoverLoader(
    private val loadCover: suspend (String) -> MediaEntity?,
    private val loadThumb: suspend (MediaEntity) -> File?,
) {
    private val permits = Semaphore(permits = 3)
    private val cache = HashMap<String, FolderCover?>()

    suspend fun cover(path: String): FolderCover? {
        if (cache.containsKey(path)) return cache[path]
        return permits.withPermit {
            // 等名额期间可能已经被别的卡片填上了，再查一次。
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

/**
 * 目录路径转成 LIKE 匹配串：目录本身以 "/" 结尾，加 % 表示"该目录及它的所有子目录"，
 * 正好是"这个文件夹里最新的一张照片"。
 */
private fun coverPatternOf(path: String): String = escapeLike(path) + "%"

/** 与 MediaQuery 的转义规则保持一致：先转义反斜杠，再转义 % 和 _（SQLite 用 ESCAPE '\'）。 */
private fun escapeLike(raw: String): String =
    raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
