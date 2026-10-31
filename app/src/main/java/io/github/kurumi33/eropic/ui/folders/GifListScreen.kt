package io.github.kurumi33.eropic.ui.folders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.EropicTopBar
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount
import kotlinx.coroutines.launch

/**
 * 「GIF」虚拟文件夹（v0.8）。
 *
 * GIF 不是单独的扫描来源，只是从图片索引里筛 mimeType=image/gif 的子集：
 * 这样 GIF 有独立的入口和统计，又不会改变图片识别 / 相似度 / 清理的既有口径
 * （GIF 依旧在图片索引里，仍是图库的一部分）。
 *
 * 本页只读：点开走查看器（支持动图），没有任何删除/移动/改名入口。
 */
@Composable
fun GifListScreen(
    viewModel: EropicViewModel,
    onBack: () -> Unit,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // 总数/总大小走独立统计 Flow：分页的 itemCount 只统计已加载的页，标题上的数字会跳。
    val gifCount by viewModel.gifCount.collectAsStateWithLifecycle(initialValue = 0)
    val gifTotalBytes by viewModel.gifTotalBytes.collectAsStateWithLifecycle(initialValue = 0L)

    val items = remember(viewModel) { viewModel.gifMedia() }.collectAsLazyPagingItems()
    val scope = rememberCoroutineScope()

    // 点一格 → 把「GIF 全集」的 id 列表交给查看器，这样才能在查看器里左右滑动浏览所有 GIF。
    // 列表可能很长，所以按需现取；取失败就退化成单张开图，不让点击变成无反应。
    var opening by remember { mutableStateOf(false) }
    val openGif: (Long) -> Unit = { mediaId ->
        if (!opening) {
            opening = true
            scope.launch {
                val ids = runCatching { viewModel.gifIds() }.getOrDefault(emptyList())
                val index = ids.indexOf(mediaId)
                opening = false
                if (index >= 0) {
                    onOpenViewer(ids, index)
                } else {
                    onOpenViewer(listOf(mediaId), 0)
                }
            }
        }
    }

    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize()) {
        EropicTopBar(
            title = "GIF",
            subtitle = formatCount(gifCount.toLong()) + " 张 · " + formatBytes(gifTotalBytes),
            onBack = onBack,
        )

        val refresh = items.loadState.refresh
        when {
            items.itemCount == 0 && refresh is LoadState.Loading -> GifCenteredState {
                CircularProgressIndicator()
                Text(
                    text = "正在读取 GIF…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            items.itemCount == 0 && refresh is LoadState.Error -> GifCenteredState {
                Text(
                    text = "GIF 列表加载失败",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = refresh.error.message?.takeIf { it.isNotBlank() } ?: "请稍后重试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(
                    onClick = { items.retry() },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("重试")
                }
            }

            items.itemCount == 0 -> GifCenteredState {
                Icon(
                    imageVector = Icons.Filled.Gif,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    text = "没有 GIF 图片",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "GIF 从图片索引里筛选而来，不影响图片的识别与清理统计。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            else -> GifGrid(
                items = items,
                columns = settings.gridColumns.coerceIn(2, 8),
                onOpen = openGif,
            )
        }
    }
}

/**
 * GIF 网格：复用图库的格子尺寸（1:1、2dp 间隔），但这里只有单击、没有多选。
 *
 * Coil 3 默认就会解码动图，所以直接用 AsyncImage 就是会动的预览，不需要额外依赖或开关。
 */
@Composable
private fun GifGrid(
    items: LazyPagingItems<MediaEntity>,
    columns: Int,
    onOpen: (Long) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(count = items.itemCount) { index ->
            val item = items[index]
            if (item == null) {
                // 占位符：分页还没取到这一格。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                )
            } else {
                GifCell(item = item, onClick = { onOpen(item.id) })
            }
        }

        // 追加分页：只有刷新成功、已有内容时才显示；否则交给上面的三态。
        when (val append = items.loadState.append) {
            is LoadState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                GifFooter {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(
                        text = "正在加载更多…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            is LoadState.Error -> item(span = { GridItemSpan(maxLineSpan) }) {
                GifFooter {
                    Text(
                        text = "加载更多失败",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = { items.retry() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text("重试")
                    }
                }
            }

            else -> Unit
        }
    }
}

/** 单个 GIF 格子：有图就铺满裁切，没有就留浅底色，点空白也算点这一格。 */
@Composable
private fun GifCell(
    item: MediaEntity,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = MediaUris.of(item.id),
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 整行居中的状态块：加载、错误、空态共用，避免三套布局漂移。 */
@Composable
private fun GifCenteredState(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
        }
    }
}

/** 网格底部的加载更多 / 失败重试行。 */
@Composable
private fun GifFooter(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            content()
        }
    }
}
