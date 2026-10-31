package io.github.kurumi33.eropic.ui.folders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount

/**
 * 文件夹页最下面的两个「虚拟文件夹」入口（v0.8）。
 *
 * 为什么叫虚拟文件夹：视频和 GIF 都不在目录树里单独占一个目录，它们只是索引里按类型筛出来的一批条目。
 * - 视频：独立的 video_item 表，只做识别、统计和浏览，不会移动或删除任何文件；
 * - GIF：仍然算图片，仍然参与图库与去重，这里只是按 mimeType 单独列出来方便集中查看。
 *
 * 两个入口横排等宽，各显示「条目数 · 占用」，数据直接订阅数据库聚合 Flow，
 * 索引/扫描完成后会自动刷新，本组件不需要主动拉取。
 *
 * @param onOpenVideos 打开视频列表页
 * @param onOpenGifs 打开 GIF 网格页
 */
@Composable
fun SpecialFoldersSection(
    viewModel: EropicViewModel,
    onOpenVideos: () -> Unit,
    onOpenGifs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 四个计数都是 Room 的聚合 Flow：VIDEO 走 video_item 表，GIF 走图片表的 mimeType 过滤。
    val videoCount by viewModel.videoCount.collectAsStateWithLifecycle(initialValue = 0)
    val videoBytes by viewModel.videoTotalBytes.collectAsStateWithLifecycle(initialValue = 0L)
    val gifCount by viewModel.gifCount.collectAsStateWithLifecycle(initialValue = 0)
    val gifBytes by viewModel.gifTotalBytes.collectAsStateWithLifecycle(initialValue = 0L)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SpecialFolderCard(
            title = "视频",
            summary = formatCount(videoCount.toLong()) + " 个 · " + formatBytes(videoBytes),
            icon = Icons.Filled.Movie,
            onClick = onOpenVideos,
            modifier = Modifier.weight(1f),
        )
        SpecialFolderCard(
            title = "GIF",
            summary = formatCount(gifCount.toLong()) + " 张 · " + formatBytes(gifBytes),
            icon = Icons.Filled.Gif,
            onClick = onOpenGifs,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 一张入口卡：图标 + 标题 + 「数量 · 占用」一行；半宽显示，放不下时省略而不是换行。 */
@Composable
private fun SpecialFolderCard(
    title: String,
    summary: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
