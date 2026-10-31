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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.paging.ItemSnapshotList
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
    /**
     * 把当前可见的分组键回报给上层，供「更多 → 显示方式 → 全部收起」一次性折叠所有分组。
     * 默认空实现：不是所有调用方都关心分组键。
     */
    onVisibleGroupKeys: (Set<String>) -> Unit = {},
    /**
     * 分组键 → 该组真实张数（全库聚合，来自 ViewModel.galleryGroupStats）。
     *
     * 行数据本身只能从"已加载的分页项"展开，所以 Header 的 row.count 只是**已加载**张数：
     * 首屏只加载几十条时，标题会显示「2026年10月 · 800 张」这种偏小的数字（v1.0.0 缺陷）。
     * 这里用数据库聚合结果覆盖显示张数；查不到（比如不按时间排序时键里带段号、
     * 或聚合还没回来）就退回已加载张数，绝不显示空。
     */
    groupCounts: Map<String, Long> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val loadedCount = items.itemCount
    // 折叠/滚动性能：不要把 itemSnapshotList 直接当 remember 的 key。
    // 它是 mutableStateOf，Paging 每派发一次数据事件（插入/丢弃/加载状态变化）
    // 都会换成一个新的 ItemSnapshotList 对象，而 remember 用 equals 比较 key；
    // ItemSnapshotList 继承自 AbstractList，相等性是逐元素比较——屏幕上几千张图时，
    // 每次重组都要做几千次 MediaEntity.equals（选择模式切换、设置变更这类
    // 与数据无关的重组也逃不掉），折叠时紧接着还要全量重建行，
    // 两者叠加就是用户反馈的卡顿和闪烁。
    // 改用 SnapshotStamp：只判断"快照对象是不是同一个实例"，O(1)。
    // 代价是分页状态回调也会换快照 → 多算一次行（O(n)，和原来一次 equals 同量级），
    // 但这换来的是：数据真变了（收藏/删除/排序/筛选）一定重建，绝不显示旧状态。
    // 也正因为这样，不能只留 loadedCount 当 key：这些改动常常不改变总张数。
    val snapshotStamp = SnapshotStamp(items.itemSnapshotList)
    val rows = remember(snapshotStamp, loadedCount, mode, dateSorted, collapsedKeys) {
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

    // 上报可见分组键：回调用 rememberUpdatedState 包一层，
    // 避免上层每次重组传进来的新 lambda 触发 Effect 重启。
    val latestOnVisibleGroupKeys = rememberUpdatedState(onVisibleGroupKeys)
    // key 用与 rows 相同的输入：不要用 rows 本身当 key —— List 的 equals 又是逐元素比较，
    // 等于把刚省下来的 O(n) 又加回来。
    LaunchedEffect(snapshotStamp, loadedCount, mode, dateSorted, collapsedKeys) {
        latestOnVisibleGroupKeys.value(
            rows.asSequence()
                .filterIsInstance<GalleryDisplayRow.Header>()
                .map { it.key }
                .toSet(),
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
                is GalleryDisplayRow.Header -> GalleryGroupHeaderCell(
                    row = row,
                    // 优先用全库聚合张数；没有这个键（未分组/非时间排序的段键）时退回已加载张数。
                    totalCount = groupCounts[row.key] ?: row.count.toLong(),
                    onToggleGroup = onToggleGroup,
                )

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

/**
 * 分组标题单元格：整行可点击，标题显示"2026年10月 · 123 张"，右侧箭头表示折叠状态。
 *
 * [totalCount] 是该组的**全库真实张数**（由上层聚合查询给出），不是已加载张数：
 * 分页只加载了一部分，用 row.count 当标题会在刚打开图库时严重偏小。
 */
@Composable
private fun GalleryGroupHeaderCell(
    row: GalleryDisplayRow.Header,
    totalCount: Long,
    onToggleGroup: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 行高至少 48dp：折叠后整组只剩这一行，太矮既不好点，也和图片行挤在一起
            .heightIn(min = 48.dp)
            .clickable { onToggleGroup(row.key) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.label + " · " + totalCount + " 张",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // 占满剩余宽度：把箭头顶到最右侧，折叠/展开的方向一眼可见
            modifier = Modifier.weight(1f),
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

/**
 * remember 的缓存键：只认"快照对象是不是同一个实例"，不比较内容。
 *
 * 不直接把 [ItemSnapshotList] 当 key：它继承自 AbstractList，equals 是逐元素比较，
 * 数据上千时每次重组都是 O(n) 次 MediaEntity.equals（见 [GalleryDisplayGrid] 里的说明）。
 * 也不能只按"已加载张数"做 key：收藏、改名、移入回收站等改动不改变总张数，
 * 那样网格会一直显示旧实体。
 */
private class SnapshotStamp(val snapshot: ItemSnapshotList<MediaEntity>) {
    override fun equals(other: Any?): Boolean =
        other is SnapshotStamp && other.snapshot === snapshot

    override fun hashCode(): Int = System.identityHashCode(snapshot)
}
