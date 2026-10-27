package io.github.kurumi33.eropic.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.media.MediaUris
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
) {
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
