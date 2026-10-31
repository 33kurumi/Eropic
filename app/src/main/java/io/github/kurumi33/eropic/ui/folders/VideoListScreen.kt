package io.github.kurumi33.eropic.ui.folders

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kurumi33.eropic.core.database.entity.VideoEntity
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.EropicTopBar
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount
import io.github.kurumi33.eropic.ui.common.formatDate
import java.util.Locale

/**
 * 「视频」虚拟文件夹（v0.8）。
 *
 * 数据来自独立的 video_item 表（MediaStore.Video 对账出来的结果），本页只做三件事：
 * 列表浏览、缩略图、交给系统播放器打开。这里没有任何删除/移动/改名入口：
 * 视频只是被识别和统计，文件位置始终由用户自己的目录结构决定。
 *
 * 三种状态都明确呈现：读索引中（首次进入）/ 索引为空时自动扫一次 / 读取或扫描失败可重试。
 * 二级页没有 Snackbar 宿主（只有主界面 Scaffold 里有），所以扫描结果用页面内一行小字展示，
 * 不用 postMessage——否则提示会延迟到回主界面才弹出来。
 */
@Composable
fun VideoListScreen(
    viewModel: EropicViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    var videos by remember { mutableStateOf(emptyList<VideoEntity>()) }
    var loading by remember { mutableStateOf(true) }
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanMessage by remember { mutableStateOf<String?>(null) }

    // 触发一次「读索引（必要时再扫一遍）」：首次进入自动跑，错误重试与手动重新扫描也走同一条路径。
    var loadTick by remember { mutableStateOf(0) }
    // 用户点了「重新扫描」：即便索引里已有视频，也要强制再对一次 MediaStore。
    var forceScan by remember { mutableStateOf(false) }

    var sortName by rememberSaveable { mutableStateOf(VideoSort.DATE_DESC.name) }
    val sort = remember(sortName) { VideoSort.fromName(sortName) }

    LaunchedEffect(loadTick) {
        loading = true
        error = null
        val loaded = runCatching { viewModel.loadVideos() }
        if (loaded.isFailure) {
            error = loaded.exceptionOrNull()?.message ?: "读取视频列表失败"
            loading = false
            forceScan = false
            return@LaunchedEffect
        }
        videos = loaded.getOrDefault(emptyList())
        // 索引为空（刚升级到 v0.8、还没同步过视频）时自动扫一次；
        // 用户手动点过「重新扫描」也走这里，所以 forceScan 优先于「列表已非空」。
        if (videos.isEmpty() || forceScan) {
            forceScan = false
            scanning = true
            scanMessage = runCatching { viewModel.refreshVideos() }
                .getOrElse { throwable -> throwable.message ?: "扫描视频失败" }
            videos = runCatching { viewModel.loadVideos() }.getOrDefault(videos)
            scanning = false
        }
        loading = false
    }

    val ordered = remember(videos, sort) { sortVideos(videos, sort) }
    val totalBytes = remember(videos) { videos.sumOf { item -> item.sizeBytes } }

    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize()) {
        EropicTopBar(
            title = "视频",
            subtitle = formatCount(ordered.size.toLong()) + " 个 · " + formatBytes(totalBytes),
            onBack = onBack,
            actions = {
                VideoSortMenu(activeSort = sort, onSelect = { order -> sortName = order.name })
                IconButton(
                    onClick = {
                        forceScan = true
                        loadTick++
                    },
                    enabled = !loading && !scanning,
                ) {
                    Icon(imageVector = Icons.Filled.Refresh, contentDescription = "重新扫描视频")
                }
            },
        )

        when {
            loading && videos.isEmpty() -> VideoLoadingState(
                scanning = scanning,
            )

            error != null && videos.isEmpty() -> VideoErrorState(
                message = error,
                onRetry = {
                    forceScan = false
                    loadTick++
                },
            )

            videos.isEmpty() -> VideoEmptyState(
                scanning = scanning,
                scanMessage = scanMessage,
                onRescan = {
                    forceScan = true
                    loadTick++
                },
            )

            else -> Column(modifier = Modifier.weight(1f)) {
                // 扫描结果就地展示：二级页的 Snackbar 会压到回主界面后才弹。
                scanMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = ordered, key = { item -> item.id }) { item ->
                        VideoRow(
                            item = item,
                            loadThumbnail = viewModel::videoThumbnail,
                            onClick = { openVideo(context, item) },
                        )
                    }
                }
            }
        }
    }
}

/** 视频列表排序：量级是几千条，直接在内存里排，不必为排序再查一次数据库。 */
private enum class VideoSort(val label: String) {
    DATE_DESC("拍摄时间 · 新→旧"),
    DATE_ASC("拍摄时间 · 旧→新"),
    SIZE_DESC("文件大小 · 大→小"),
    SIZE_ASC("文件大小 · 小→大"),
    NAME_ASC("文件名 A→Z"),
    ;

    companion object {
        fun fromName(name: String?): VideoSort =
            entries.firstOrNull { order -> order.name == name } ?: DATE_DESC
    }
}

/** 排序：时间优先用拍摄时间，缺拍摄时间（0）时退回修改时间，避免这些行全堆在列表末尾。 */
private fun sortVideos(list: List<VideoEntity>, sort: VideoSort): List<VideoEntity> = when (sort) {
    VideoSort.DATE_DESC -> list.sortedWith(
        compareByDescending<VideoEntity> { item -> timeOf(item) }
            .thenBy { item -> item.displayName.lowercase(Locale.ROOT) },
    )
    VideoSort.DATE_ASC -> list.sortedWith(
        compareBy<VideoEntity> { item -> timeOf(item) }
            .thenBy { item -> item.displayName.lowercase(Locale.ROOT) },
    )
    VideoSort.SIZE_DESC -> list.sortedWith(
        compareByDescending<VideoEntity> { item -> item.sizeBytes }
            .thenBy { item -> item.displayName.lowercase(Locale.ROOT) },
    )
    VideoSort.SIZE_ASC -> list.sortedWith(
        compareBy<VideoEntity> { item -> item.sizeBytes }
            .thenBy { item -> item.displayName.lowercase(Locale.ROOT) },
    )
    VideoSort.NAME_ASC -> list.sortedWith(
        compareBy<VideoEntity> { item -> item.displayName.lowercase(Locale.ROOT) },
    )
}

private fun timeOf(item: VideoEntity): Long =
    if (item.dateTakenMs > 0L) item.dateTakenMs else item.dateModifiedMs

@Composable
private fun VideoSortMenu(
    activeSort: VideoSort,
    onSelect: (VideoSort) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(imageVector = Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            VideoSort.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label) },
                    trailingIcon = {
                        if (order == activeSort) {
                            Icon(imageVector = Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        menuOpen = false
                        onSelect(order)
                    },
                )
            }
        }
    }
}

/** 一行视频：80dp 缩略图 + 文件名 + 「时长 · 大小 · 目录」+ 拍摄日期。 */
@Composable
private fun VideoRow(
    item: VideoEntity,
    loadThumbnail: suspend (Long) -> Bitmap?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoThumbnail(
            id = item.id,
            displayName = item.displayName,
            loadThumbnail = loadThumbnail,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = metaLine(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = "拍摄于 " + formatDate(item.dateTakenMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 视频缩略图。
 *
 * 用 MediaStore 的 loadThumbnail 现取（见 VideoRepository），解不出来是常态，
 * 所以失败就显示占位图标，不弹错误、不阻塞列表。
 */
@Composable
private fun VideoThumbnail(
    id: Long,
    displayName: String,
    loadThumbnail: suspend (Long) -> Bitmap?,
) {
    val bitmap by produceState<Bitmap?>(null, id) {
        value = runCatching { loadThumbnail(id) }.getOrNull()
    }
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/** 第二行元信息：「时长 01:23 · 12.3 MB · Camera」。 */
private fun metaLine(item: VideoEntity): String = buildString {
    append("时长 ")
    append(formatDuration(item.durationMs))
    append(" · ")
    append(formatBytes(item.sizeBytes))
    append(" · ")
    append(folderLabel(item.relativePath))
}

/** 目录只显示最后一段（例如 DCIM/Camera/ 显示 Camera），根目录单独兜底。 */
private fun folderLabel(relativePath: String): String {
    val trimmed = relativePath.trim('/')
    if (trimmed.isEmpty()) return "根目录"
    return trimmed.substringAfterLast('/').ifEmpty { "根目录" }
}

/** 时长 → mm:ss；超过一小时显示 h:mm:ss，未知时长给占位符。 */
private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

@Composable
private fun VideoLoadingState(scanning: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = if (scanning) "正在扫描视频…" else "正在读取视频…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VideoErrorState(message: String?, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "视频列表加载失败",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message?.takeIf { it.isNotBlank() } ?: "请稍后重试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) {
                Text("重试")
            }
        }
    }
}

@Composable
private fun VideoEmptyState(
    scanning: Boolean,
    scanMessage: String?,
    onRescan: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = "还没有索引到视频，点这里重新扫描",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = scanMessage?.takeIf { it.isNotBlank() }
                    ?: "视频只做识别与统计，不会移动或删除文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Button(onClick = onRescan, modifier = Modifier.padding(top = 4.dp)) {
                    Text("重新扫描")
                }
            }
        }
    }
}

/**
 * 交给系统播放器打开。
 *
 * 和图片查看器的「用其他应用打开」同一套写法：ACTION_VIEW + 带授权的 content Uri，
 * 外层再套一个选择器让用户自己挑播放器；个别 ROM 上没有候选应用时 startActivity 会抛异常，
 * 兜一层 Toast 而不是把 App 带崩。
 */
private fun openVideo(context: Context, item: VideoEntity) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(MediaUris.ofVideo(item.id), item.mimeType.ifBlank { "video/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, "用其他应用打开").apply {
        // 有些机型只对 chooser 这一层的 flag 生效，这里也带上读取权限。
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(chooser) }.onFailure {
        Toast.makeText(context, "没有可以打开这个视频的应用", Toast.LENGTH_SHORT).show()
    }
}
