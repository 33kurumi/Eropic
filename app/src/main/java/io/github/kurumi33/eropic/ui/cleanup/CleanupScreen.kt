package io.github.kurumi33.eropic.ui.cleanup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.analysis.DuplicateScanner
import io.github.kurumi33.eropic.core.cleanup.KeepCandidate
import io.github.kurumi33.eropic.core.cleanup.KeepSuggestion
import io.github.kurumi33.eropic.core.database.entity.DuplicateGroupEntity
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.index.IndexPhase
import io.github.kurumi33.eropic.core.index.IndexState
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.ui.AppScreen
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount
import io.github.kurumi33.eropic.ui.common.formatMediaSubtitle
import kotlinx.coroutines.launch
import java.io.File

/** 清理页的八个入口。前两个来自查重分组，其余来自检测器。 */
enum class CleanupCategory(val label: String) {
    DUPLICATES("重复"),
    SIMILAR("相似"),
    LARGE("大文件"),
    OLD("久远"),
    SCREENSHOT("截图"),
    BLURRY("模糊"),
    BURST("连拍"),
    SMALL("小图"),
}

/** 当前分类要展示的内容。 */
private sealed interface CleanupContent {
    data object Loading : CleanupContent
    data class Grouped(val groups: List<DuplicateGroupEntity>) : CleanupContent
    data class Detected(val items: List<MediaEntity>, val summary: String) : CleanupContent
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupScreen(
    viewModel: EropicViewModel,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
    hasWriteAccess: Boolean = false,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val indexState by viewModel.indexState.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()

    var category by rememberSaveable { mutableStateOf(CleanupCategory.DUPLICATES) }
    var filterState by remember { mutableStateOf(FilterUiState()) }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    // 非空表示筛选结果页正在最上层。
    var filteredState by remember { mutableStateOf<FilterUiState?>(null) }
    var compareGroupId by rememberSaveable { mutableStateOf(-1L) }
    var refreshToken by remember { mutableIntStateOf(0) }

    filteredState?.let { applied ->
        FilteredScreen(
            viewModel = viewModel,
            filterState = applied,
            onBack = { filteredState = null },
            onOpenViewer = onOpenViewer,
        )
        return
    }
    if (compareGroupId > 0L) {
        CompareScreen(
            viewModel = viewModel,
            groupId = compareGroupId,
            onClose = { compareGroupId = -1L },
            onOpenViewer = onOpenViewer,
        )
        return
    }
    if (showFilter) {
        FilterSheet(
            initial = filterState,
            onDismiss = { showFilter = false },
            onApply = {
                filterState = it
                showFilter = false
                if (!it.isEmpty) filteredState = it
            },
        )
    }

    val content by produceState<CleanupContent>(
        initialValue = CleanupContent.Loading,
        category,
        refreshToken,
        groups,
    ) {
        value = CleanupContent.Loading
        value = runCatching {
            when (category) {
                CleanupCategory.DUPLICATES -> CleanupContent.Grouped(
                    groups.filter { it.kind == DuplicateScanner.KIND_EXACT },
                )

                CleanupCategory.SIMILAR -> CleanupContent.Grouped(
                    groups.filter { it.kind == DuplicateScanner.KIND_NEAR },
                )

                CleanupCategory.LARGE -> {
                    val result = viewModel.largeFiles()
                    CleanupContent.Detected(
                        items = result.items,
                        summary = "共 ${formatCount(result.hitCount)} 张 ≥ 5 MB，合计 ${formatBytes(result.reclaimableBytes)}",
                    )
                }

                CleanupCategory.OLD -> CleanupContent.Detected(
                    items = viewModel.oldPhotos(),
                    summary = "拍摄时间超过 3 年、且未收藏的图片（按时间从旧到新）",
                )

                CleanupCategory.SCREENSHOT -> CleanupContent.Detected(
                    items = viewModel.screenshots(),
                    summary = "疑似截图，最多列出 2000 张候选：命中截图目录、屏幕分辨率或图标/文字特征",
                )

                CleanupCategory.BLURRY -> CleanupContent.Detected(
                    items = viewModel.blurred(),
                    summary = "清晰度低于阈值（Laplacian 方差 < 100，已完成指纹计算）",
                )

                CleanupCategory.BURST -> {
                    val result = viewModel.bursts()
                    CleanupContent.Detected(
                        items = result.groups.flatMap { it.members },
                        summary = "疑似连拍：${result.groups.size} 组 · ${formatCount(result.hitCount.toLong())} 张 · " +
                            "每组留一张可释放约 ${formatBytes(result.reclaimableBytes)}" +
                            "（同目录、5 秒内、连续 3 张以上）",
                    )
                }

                CleanupCategory.SMALL -> {
                    val items = viewModel.smallImages()
                    CleanupContent.Detected(
                        items = items,
                        summary = "小图：面积小于 200×200 的图片，共 ${formatCount(items.size.toLong())} 张",
                    )
                }
            }
        }.getOrElse { error ->
            CleanupContent.Detected(
                items = emptyList(),
                summary = "读取失败：${error.message ?: error::class.simpleName}",
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("清理") },
            actions = {
                TextButton(onClick = { viewModel.openScreen(AppScreen.CleanupReport) }) {
                    Text("清理报告")
                }
                TextButton(onClick = { showFilter = true }) {
                    Text(if (filterState.isEmpty) "组合筛选" else "筛选 · 已设置")
                }
                IconButton(onClick = { showFilter = true }) {
                    Icon(imageVector = Icons.Filled.Tune, contentDescription = "组合筛选")
                }
            },
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(CleanupCategory.entries.toList(), key = { it.name }) { item ->
                FilterChip(
                    selected = category == item,
                    onClick = { category = item },
                    label = { Text(item.label) },
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { IndexCard(indexState, onStart = viewModel::startIndex, onCancel = viewModel::cancelIndex) }
            item { ReadOnlyNotice() }

            when (val current = content) {
                CleanupContent.Loading -> item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }

                is CleanupContent.Grouped -> {
                    item {
                        ContentHeader(
                            summary = when (category) {
                                CleanupCategory.DUPLICATES ->
                                    if (current.groups.isEmpty()) "还没有精确重复分组。" else
                                        "精确重复：文件内容（SHA-256）完全一致的图片，默认按可释放空间排序。"

                                else ->
                                    if (current.groups.isEmpty()) "还没有相似图片分组。" else
                                        "相似图片：dHash 与 pHash 双重距离阈值内的图片，不一定真的相同，需逐组确认。"
                            },
                            onRefresh = { refreshToken++ },
                        )
                    }
                    if (current.groups.isEmpty()) {
                        item { EmptyHint() }
                    } else {
                        items(current.groups, key = { it.groupId }) { group ->
                            GroupCard(
                                group = group,
                                loadMembers = viewModel::members,
                                loadThumbnail = viewModel::thumbnailFile,
                                hasWriteAccess = hasWriteAccess,
                                onCompare = { compareGroupId = group.groupId },
                                onApplyKeep = { suggestion ->
                                    scope.launch {
                                        val result = viewModel.moveToTrash(suggestion.trashIds)
                                        viewModel.postMessage(result.message, undoTrashIds = result.trashIds)
                                        refreshToken++
                                    }
                                },
                            )
                        }
                    }
                }

                is CleanupContent.Detected -> {
                    item {
                        ContentHeader(
                            summary = current.summary,
                            onRefresh = { refreshToken++ },
                        )
                    }
                    if (current.items.isEmpty()) {
                        item { EmptyHint() }
                    } else {
                        val ids = current.items.map { it.id }
                        itemsIndexed(current.items, key = { _, item -> item.id }) { index, item ->
                            MediaListRow(
                                media = item,
                                loadThumbnail = viewModel::thumbnailFile,
                                onClick = { onOpenViewer(ids, index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentHeader(summary: String, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRefresh) { Text("刷新") }
    }
}

@Composable
private fun EmptyHint() {
    Text(
        text = "没有符合条件的图片。索引完成后重新刷新再看看。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(8.dp),
    )
}

@Composable
private fun IndexCard(indexState: IndexState, onStart: () -> Unit, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "索引与查重", style = MaterialTheme.typography.titleMedium)
            Text(
                text = when (indexState.phase) {
                    IndexPhase.IDLE -> "尚未运行。建立索引会读取系统相册元数据，并在后台计算图片指纹。"
                    IndexPhase.SYNCING -> "正在读取系统相册：${indexState.scanned} 条"
                    IndexPhase.HASHING -> "正在计算指纹：${indexState.signatureDone}/${indexState.signatureTotal}"
                    IndexPhase.DEDUPING -> indexState.message
                    IndexPhase.DONE -> "完成：精确重复 ${indexState.exactGroups} 组，相似 ${indexState.nearGroups} 组，可释放约 ${formatBytes(indexState.reclaimableBytes)}"
                    IndexPhase.ERROR -> indexState.message
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (indexState.isRunning) {
                val total = indexState.signatureTotal
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = {
                        if (indexState.phase == IndexPhase.HASHING && total > 0) {
                            (indexState.signatureDone.toFloat() / total).coerceIn(0f, 1f)
                        } else 0f
                    },
                )
                OutlinedButton(onClick = onCancel) { Text("取消") }
            } else {
                Button(onClick = onStart) {
                    Text(if (indexState.phase == IndexPhase.DONE) "重新扫描" else "开始扫描")
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyNotice() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = "不自动删除", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Eropic 只负责找候选，不会自动删除或移动任何文件。" +
                    "所有写操作都要你逐组确认；删除只是移入回收站，30 天内可随时恢复。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun GroupCard(
    group: DuplicateGroupEntity,
    loadMembers: suspend (Long) -> List<MediaEntity>,
    loadThumbnail: suspend (MediaEntity) -> File?,
    hasWriteAccess: Boolean,
    onCompare: () -> Unit,
    onApplyKeep: (KeepSuggestion) -> Unit,
) {
    val members by produceState(initialValue = emptyList<MediaEntity>(), group.groupId) {
        value = loadMembers(group.groupId)
    }
    val isExact = group.kind == DuplicateScanner.KIND_EXACT
    var showKeepDialog by remember { mutableStateOf(false) }
    if (showKeepDialog) {
        KeepStrategyDialog(
            candidates = members.map {
                KeepCandidate(
                    id = it.id,
                    sizeBytes = it.sizeBytes,
                    dateTakenMs = it.dateTakenMs,
                    path = it.path,
                )
            },
            onDismiss = { showKeepDialog = false },
            onApply = { suggestion ->
                showKeepDialog = false
                onApplyKeep(suggestion)
            },
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isExact) "精确重复" else "相似图片",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isExact) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = " · ${group.memberCount} 张 · 可释放 ${formatBytes(group.reclaimableBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!isExact) {
                Text(
                    text = "相似度参考：dHash 最大距离 ${group.maxDistance}/64",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(members, key = { it.id }) { media ->
                    GroupThumb(media = media, loadThumbnail = loadThumbnail)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCompare, enabled = members.size >= 2) { Text("对比预览") }
                TextButton(
                    onClick = { showKeepDialog = true },
                    enabled = hasWriteAccess && members.size >= 2,
                ) { Text("选择保留") }
                if (!hasWriteAccess) {
                    Text(
                        text = "未授予「所有文件访问」权限，写操作已禁用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupThumb(media: MediaEntity, loadThumbnail: suspend (MediaEntity) -> File?) {
    val thumb by produceState<File?>(initialValue = null, media.id, media.sizeBytes) {
        value = loadThumbnail(media)
    }
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = thumb?.let { android.net.Uri.fromFile(it) } ?: MediaUris.of(media.id),
            contentDescription = media.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun MediaListRow(
    media: MediaEntity,
    loadThumbnail: suspend (MediaEntity) -> File?,
    onClick: () -> Unit,
) {
    val thumb by produceState<File?>(initialValue = null, media.id, media.sizeBytes) {
        value = loadThumbnail(media)
    }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AsyncImage(
                model = thumb?.let { android.net.Uri.fromFile(it) } ?: MediaUris.of(media.id),
                contentDescription = media.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = media.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatMediaSubtitle(
                        sizeBytes = media.sizeBytes,
                        width = media.width,
                        height = media.height,
                        dateTakenMs = media.dateTakenMs,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(modifier = Modifier.width(4.dp))
        }
    }
}