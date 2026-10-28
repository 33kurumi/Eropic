package io.github.kurumi33.eropic.ui.gallery

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.core.settings.DateGroupMode
import java.io.File

/**
 * 图库网格（支持日期分组标题、折叠、按月/按日/不分组）。
 *
 * 与 [io.github.kurumi33.eropic.ui.components.MediaGrid] 的区别：
 * 1. 行由 [buildGalleryDisplayRows] 从当前已加载的照片展开而来，可以在标题行上折叠整段；
 * 2. 折叠后可见行会变少，网格末尾额外放一个"分页哨兵"：只要它被渲染出来，
 *    就代表列表已经滚到（或短到）尽头，此时主动访问分页尾部，让 Paging 继续加载下一页；
 * 3. 照片单元格渲染时也会像普通网格一样访问分页下标，保证滚动到一半就开始预取。
 *
 * 注意：这里所有行数据都来自 [LazyPagingItems.peek]（只读快照，不会触发加载），
 * 真正触发加载的是单元格里的 [LazyPagingItems.get] 与末尾哨兵。
 */
@Composable
fun GalleryDisplayGrid(
    items: LazyPagingItems<MediaEntity>,
    mode: DateGroupMode,
    dateSorted: Boolean,
    collapsedKeys: Set<String>,
    onToggleGroup: (String) -> Unit,
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
    val loadedCount = items.itemCount
    val snapshot = items.itemSnapshotList
    val rows = remember(snapshot, loadedCount, mode, dateSorted, collapsedKeys) {
        val photos = ArrayList<MediaEntity>(loadedCount)
        for (index in 0 until loadedCount) {
            val entity = items.peek(index) ?: continue
            photos.add(entity)
        }
        buildGalleryDisplayRows(
            photos = photos,
            mode = mode,
            dateSorted = dateSorted,
            collapsedKeys = collapsedKeys,
        )
    }

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
            count = rows.size,
            key = { index ->
                when (val row = rows[index]) {
                    is GalleryDisplayRow.Header -> "header-" + row.key
                    is GalleryDisplayRow.Photo -> "photo-" + row.entity.id
                }
            },
            span = { index ->
                if (rows[index] is GalleryDisplayRow.Header) {
                    GridItemSpan(maxLineSpan)
                } else {
                    GridItemSpan(1)
                }
            },
            contentType = { index ->
                if (rows[index] is GalleryDisplayRow.Header) "header" else "photo"
            },
        ) { index ->
            when (val row = rows[index]) {
                is GalleryDisplayRow.Header -> GalleryGroupHeaderCell(row, onToggleGroup)

                is GalleryDisplayRow.Photo -> {
                    val entity = row.entity
                    // 与普通网格保持一致：访问分页下标，Paging 据此提前加载后面的页。
                    if (row.pagingIndex >= 0 && row.pagingIndex < items.itemCount) {
                        items.get(row.pagingIndex)
                    }
                    GalleryThumbCell(
                        item = entity,
                        selected = entity.id in selectedIds,
                        selectionActive = selectionActive,
                        thumbnailsEnabled = thumbnailsEnabled,
                        thumbnailProvider = thumbnailProvider,
                        onClick = { onClick(entity) },
                        onLongClick = { onLongClick(entity) },
                    )
                }
            }
        }

        // 分页哨兵：折叠后列表可能很短，用户永远滚不到分页边界，靠这一行继续加载。
        item(key = "gallery-paging-tail", span = { GridItemSpan(maxLineSpan) }) {
            PagingTail(items)
        }
    }
}

/** 分组标题单元格：整行可点击，标题显示"2026年10月 · 123 张"，右侧箭头表示折叠状态。 */
@Composable
private fun GalleryGroupHeaderCell(
    row: GalleryDisplayRow.Header,
    onToggleGroup: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleGroup(row.key) }
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.label + " · " + row.count + " 张",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = if (row.collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
            contentDescription = if (row.collapsed) "展开这一组" else "折叠这一组",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(18.dp),
        )
    }
}

/**
 * 分页哨兵：只有在"已经加载到最后一页"时才显示占位；
 * 否则主动访问一次分页尾部索引，让 Paging 继续往后加载（折叠场景下这是唯一的加载入口）。
 */
@Composable
private fun PagingTail(items: LazyPagingItems<MediaEntity>) {
    val append = items.loadState.append
    when (append) {
        is LoadState.Loading -> TailRow {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = "正在加载更多…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        is LoadState.Error -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            TextButton(onClick = { items.retry() }) {
                Text("加载失败，点击重试")
            }
        }

        is LoadState.NotLoading -> {
            if (append.endOfPaginationReached) {
                Spacer(Modifier.height(8.dp))
            } else {
                if (items.itemCount > 0) {
                    // 在组合期访问尾部下标：与 LazyGrid 自己访问单元格的效果相同，会触发下一页加载。
                    items.get(items.itemCount - 1)
                }
                TailRow {
                    Text(
                        text = "继续加载…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TailRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 单个单元格的缩略图状态。 */
private sealed interface ThumbState {
    data object Loading : ThumbState
    data class Ready(val file: File) : ThumbState
    data object Unavailable : ThumbState
}

/**
 * 缩略图单元格（复制自 ui/components/MediaGrid.kt 的 MediaCell，因为那份是 private）。
 *
 * 优先读缩略图磁盘缓存，没有缓存时退回系统图片 URI；收藏角标与多选遮罩行为保持一致。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryThumbCell(
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

/**
 * 双指捏合切换网格列数（复制自 ui/components/MediaGrid.kt，行为不变）。
 *
 * 只观察、不消费触摸事件：单指滑动仍然交给 LazyVerticalGrid 自己滚动。
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
                    accumulated = 1f
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
