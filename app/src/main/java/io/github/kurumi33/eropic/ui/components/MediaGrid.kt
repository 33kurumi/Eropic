package io.github.kurumi33.eropic.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.ui.gallery.GalleryRow
import java.io.File

/**
 * 图库与文件夹详情共用的网格。
 *
 * 单元格优先读缩略图磁盘缓存（快、省内存），没有缓存时退回系统图片 URI（正确但慢），
 * 后台索引/补齐任务会逐步把这些缺口填上。
 */
@Composable
fun MediaGrid(
    items: LazyPagingItems<MediaEntity>,
    columns: Int,
    thumbnailsEnabled: Boolean,
    selectedIds: Set<Long>,
    selectionActive: Boolean,
    thumbnailProvider: suspend (MediaEntity) -> File?,
    onClick: (MediaEntity) -> Unit,
    onLongClick: (MediaEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(count = items.itemCount) { index ->
            val item = items[index]
            if (item == null) {
                // 占位符：分页还没取到这一格。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                )
            } else {
                MediaCell(
                    item = item,
                    selected = item.id in selectedIds,
                    selectionActive = selectionActive,
                    thumbnailsEnabled = thumbnailsEnabled,
                    thumbnailProvider = thumbnailProvider,
                    onClick = { onClick(item) },
                    onLongClick = { onLongClick(item) },
                )
            }
        }
    }
}

/**
 * 带日期分组标题的图库网格。
 *
 * 与 [MediaGrid] 的区别：行类型是 [GalleryRow]，标题行占满整行（span = maxLineSpan），
 * 另外支持双指捏合实时切换列数（3~8 列），松手不持久化，只影响本次浏览。
 */
@Composable
fun GalleryGrid(
    rows: LazyPagingItems<GalleryRow>,
    columns: Int,
    thumbnailsEnabled: Boolean,
    selectedIds: Set<Long>,
    selectionActive: Boolean,
    thumbnailProvider: suspend (MediaEntity) -> File?,
    onClick: (MediaEntity) -> Unit,
    onLongClick: (MediaEntity) -> Unit,
    onColumnsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier
            .fillMaxSize()
            .pinchToChangeColumns(columns, onColumnsChange),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(
            count = rows.itemCount,
            key = { index ->
                when (val row = rows.peek(index)) {
                    is GalleryRow.Header -> "header-" + row.key + "-" + index
                    is GalleryRow.Photo -> "photo-" + row.entity.id
                    null -> "placeholder-" + index
                }
            },
            span = { index ->
                if (rows.peek(index) is GalleryRow.Header) {
                    GridItemSpan(maxLineSpan)
                } else {
                    GridItemSpan(1)
                }
            },
        ) { index ->
            when (val row = rows[index]) {
                null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                )

                is GalleryRow.Header -> GalleryHeaderCell(row.label)

                is GalleryRow.Photo -> {
                    val item = row.entity
                    MediaCell(
                        item = item,
                        selected = item.id in selectedIds,
                        selectionActive = selectionActive,
                        thumbnailsEnabled = thumbnailsEnabled,
                        thumbnailProvider = thumbnailProvider,
                        onClick = { onClick(item) },
                        onLongClick = { onLongClick(item) },
                    )
                }
            }
        }
    }
}

/** 分组标题单元格：整行一条浅底色标签。 */
@Composable
private fun GalleryHeaderCell(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 双指捏合切换网格列数。
 *
 * 只观察、不消费触摸事件：单指滑动仍然交给 LazyVerticalGrid 自己滚动，
 * 只有真的检测到双指缩放累积超过阈值时才回调一次，避免翻一张图就把列数跳乱。
 */
@Composable
private fun Modifier.pinchToChangeColumns(
    columns: Int,
    onColumnsChange: (Int) -> Unit,
): Modifier {
    val latestColumns = rememberUpdatedState(columns)
    val latestOnChange = rememberUpdatedState(onColumnsChange)
    return pointerInput(Unit) {
        var accumulated = 1f
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val pressedCount = event.changes.count { it.pressed }
                if (pressedCount >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom.isFinite() && zoom > 0f && zoom != 1f) {
                        accumulated *= zoom
                        val current = latestColumns.value
                        if (accumulated > 1.3f && current > 3) {
                            latestOnChange.value(current - 1)
                            accumulated = 1f
                        } else if (accumulated < 0.7f && current < 8) {
                            latestOnChange.value(current + 1)
                            accumulated = 1f
                        }
                    }
                } else {
                    // 手指减少时重置累积，避免一次捏合被算成连续多次。
                    accumulated = 1f
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

/** 单个单元格的缩略图状态。 */
private sealed interface ThumbState {
    data object Loading : ThumbState
    data class Ready(val file: File) : ThumbState
    data object Unavailable : ThumbState
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(
    item: MediaEntity,
    selected: Boolean,
    selectionActive: Boolean,
    thumbnailsEnabled: Boolean,
    thumbnailProvider: suspend (MediaEntity) -> File?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val thumb by produceState<ThumbState>(
        initialValue = ThumbState.Loading,
        item.id,
        thumbnailsEnabled,
    ) {
        value = if (!thumbnailsEnabled) {
            ThumbState.Unavailable
        } else {
            val file = runCatching { thumbnailProvider(item) }.getOrNull()
            if (file != null) ThumbState.Ready(file) else ThumbState.Unavailable
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        when (val state = thumb) {
            is ThumbState.Ready -> AsyncImage(
                model = Uri.fromFile(state.file),
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            ThumbState.Unavailable -> AsyncImage(
                model = MediaUris.of(item.id),
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            ThumbState.Loading -> Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
            )
        }

        if (item.localFavorite || item.isFavorite) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.35f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .size(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = "已收藏",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(2.dp),
                )
            }
        }

        if (selectionActive) {
            if (selected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                )
            }
            Surface(
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp),
            ) {
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (selected) "已选中" else "未选中",
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                    modifier = Modifier.padding(1.dp),
                )
            }
        }
    }
}

/** 多选模式下替换标题栏的操作条。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onFavorite: () -> Unit,
    onUnfavorite: () -> Unit,
    onShare: () -> Unit,
    onMove: (() -> Unit)? = null,
    onTrash: (() -> Unit)? = null,
    hasWriteAccess: Boolean = true,
    onRename: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    // 没传时退回系统设置页，保证「未授权」提示在任何调用方那里都点得动。
    onOpenPermission: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "退出多选")
            }
        },
        title = { Text("已选 $selectedCount 张") },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(imageVector = Icons.Filled.CheckCircle, contentDescription = "全选")
            }
            IconButton(onClick = onFavorite) {
                Icon(imageVector = Icons.Filled.Favorite, contentDescription = "收藏所选")
            }
            IconButton(onClick = onUnfavorite) {
                Icon(imageVector = Icons.Filled.FavoriteBorder, contentDescription = "取消收藏所选")
            }
            IconButton(onClick = onShare) {
                Icon(imageVector = Icons.Filled.Share, contentDescription = "分享所选")
            }
            // 写操作（重命名/复制/移动/移入回收站）只在调用方提供了回调时出现：
            // 例如清理页的历史分组不允许直接删，只允许在对比后由用户决定。
            if (onMove != null || onTrash != null || onRename != null || onCopy != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        // 写操作被禁用时不要只置灰：菜单顶部先给一条能点的授权提示。
                        if (!hasWriteAccess) {
                            DropdownMenuItem(
                                text = { Text(WriteAccessHintFullText) },
                                onClick = {
                                    menuOpen = false
                                    val target = onOpenPermission
                                    if (target != null) {
                                        target()
                                    } else {
                                        openSystemStorageAccessSettings(context)
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                        if (onRename != null) {
                            DropdownMenuItem(
                                text = { Text("重命名…") },
                                enabled = hasWriteAccess,
                                onClick = {
                                    menuOpen = false
                                    onRename()
                                },
                            )
                        }
                        if (onCopy != null) {
                            DropdownMenuItem(
                                text = { Text("复制到文件夹…") },
                                enabled = hasWriteAccess,
                                onClick = {
                                    menuOpen = false
                                    onCopy()
                                },
                            )
                        }
                        if (onMove != null) {
                            DropdownMenuItem(
                                text = { Text("移动到文件夹…") },
                                enabled = hasWriteAccess,
                                onClick = {
                                    menuOpen = false
                                    onMove()
                                },
                            )
                        }
                        if (onTrash != null) {
                            DropdownMenuItem(
                                text = { Text("移入回收站") },
                                enabled = hasWriteAccess,
                                onClick = {
                                    menuOpen = false
                                    onTrash()
                                },
                            )
                        }
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

/** 分享所选图片（只读 Intent，不会改动文件）。 */
fun shareImages(context: Context, ids: Collection<Long>) {
    if (ids.isEmpty()) return
    val uris = ArrayList<Uri>(ids.size)
    ids.forEach { uris.add(MediaUris.of(it)) }
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(send, "分享图片").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}
