package io.github.kurumi33.eropic.ui.folders

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.EropicApplication
import io.github.kurumi33.eropic.core.database.dao.MediaDao
import io.github.kurumi33.eropic.core.folders.FolderNode
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.formatCount

/**
 * 文件夹详情页顶部的「子文件夹」横向卡片条（v0.6）。
 *
 * 为什么放在详情页而不是只留在列表页：用户点进一个相册后，最常见的下一步就是再往下走一层，
 * 回到列表模式再点到子目录会让「进文件夹看图」变成三四步操作。
 *
 * 数据来源是目录树（由 FoldersSection 直接传 [FolderNode] 列表过来），**不再查库**，
 * 因为目录树本来就是一次聚合的结果，这里再查一次只会多一次全表扫描。
 *
 * 封面复用网格页的 [FolderCoverLoader]：并发限制 3、结果按目录缓存，滚动来回不重复查库。
 * 没有子文件夹时整个条目不渲染（返回空，不占高度）。
 */
@Composable
internal fun SubfolderStrip(
    subfolders: List<FolderNode>,
    viewModel: EropicViewModel,
    refreshToken: Long,
    thumbnailsEnabled: Boolean,
    folderCovers: Map<String, Long>,
    onOpenSubfolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (subfolders.isEmpty()) return

    val coverLoader = rememberFolderCoverLoader(viewModel, thumbnailsEnabled)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "子文件夹",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = formatCount(subfolders.size.toLong()) + " 个",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items = subfolders, key = { node -> node.path }) { node ->
                SubfolderCard(
                    node = node,
                    viewModel = viewModel,
                    coverLoader = coverLoader,
                    refreshToken = refreshToken,
                    thumbnailsEnabled = thumbnailsEnabled,
                    overrideCoverId = folderCoverIdOf(folderCovers, node.path),
                    onClick = { onOpenSubfolder(node.path) },
                )
            }
        }
    }
}

/**
 * 建一个目录封面加载器（详情页的子文件夹条用）。
 *
 * 与网格页走的是同一条路径：DAO 取该目录最新一张图，再取它的缩略图文件。
 */
@Composable
internal fun rememberFolderCoverLoader(
    viewModel: EropicViewModel,
    thumbnailsEnabled: Boolean,
): FolderCoverLoader {
    val context = LocalContext.current
    val mediaDao: MediaDao? = remember(context) {
        (context.applicationContext as? EropicApplication)?.container?.database?.mediaDao()
    }
    return remember(mediaDao, viewModel, thumbnailsEnabled) {
        FolderCoverLoader(
            loadCover = { folderPath -> mediaDao?.folderCover(coverPatternOf(folderPath)) },
            loadThumb = { item -> if (thumbnailsEnabled) viewModel.thumbnailFile(item) else null },
        )
    }
}

/** 一个子文件夹卡片：方形封面 + 目录名 + 张数。 */
@Composable
private fun SubfolderCard(
    node: FolderNode,
    viewModel: EropicViewModel,
    coverLoader: FolderCoverLoader,
    refreshToken: Long,
    thumbnailsEnabled: Boolean,
    overrideCoverId: Long?,
    onClick: () -> Unit,
) {
    // 与网格卡片同一套逻辑：用户指定的封面优先，失效了就退回自动封面。
    val cover by produceState<FolderCover?>(
        null,
        listOf(node.path, refreshToken, overrideCoverId, coverLoader),
    ) {
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
        value = coverLoader.cover(node.path)
    }

    Column(
        modifier = Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
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
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            text = node.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, end = 2.dp, top = 6.dp),
        )
        Text(
            text = formatCount(node.totalCount) + " 张",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        )
    }
}
