package io.github.kurumi33.eropic.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.kurumi33.eropic.core.index.IndexPhase
import io.github.kurumi33.eropic.core.media.SortOrder
import io.github.kurumi33.eropic.ui.AppScreen
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount
import io.github.kurumi33.eropic.ui.components.ConfirmDialog
import io.github.kurumi33.eropic.ui.components.GalleryGrid
import io.github.kurumi33.eropic.ui.components.SelectionTopBar
import io.github.kurumi33.eropic.ui.components.shareImages
import io.github.kurumi33.eropic.ui.trash.MoveDialog
import io.github.kurumi33.eropic.ui.permission.MediaAccess
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: EropicViewModel,
    access: MediaAccess,
    onRequestFullAccess: () -> Unit,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
    hasWriteAccess: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val indexState by viewModel.indexState.collectAsStateWithLifecycle()
    val count by viewModel.count.collectAsStateWithLifecycle()
    val totalSize by viewModel.totalSize.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.favoritesOnly.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()

    // 图库网格用带日期分组标题的行数据；捏合改列数只覆盖本次浏览，不回写设置。
    val rows = viewModel.galleryRows.collectAsLazyPagingItems()
    var columnOverride by remember { mutableStateOf<Int?>(null) }
    val columns = (columnOverride ?: settings.gridColumns).coerceIn(3, 8)
    LaunchedEffect(settings.gridColumns) { columnOverride = null }

    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val selectionActive = selectedIds.isNotEmpty()
    var sortMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    // 写操作的待确认目标：移动对话框 / 移入回收站二次确认共用同一份选中集合。
    var moveTargetIds by remember { mutableStateOf<List<Long>?>(null) }
    var trashTargetIds by remember { mutableStateOf<List<Long>?>(null) }

    Column(Modifier.fillMaxSize()) {
        if (selectionActive) {
            SelectionTopBar(
                selectedCount = selectedIds.size,
                onClose = { selectedIds = emptySet() },
                onSelectAll = {
                    scope.launch { selectedIds = viewModel.viewerIds().toSet() }
                },
                onFavorite = {
                    val ids = selectedIds
                    scope.launch {
                        viewModel.setFavorites(ids, true)
                        selectedIds = emptySet()
                    }
                },
                onUnfavorite = {
                    val ids = selectedIds
                    scope.launch {
                        viewModel.setFavorites(ids, false)
                        selectedIds = emptySet()
                    }
                },
                onShare = { shareImages(context, selectedIds) },
                onMove = { moveTargetIds = selectedIds.toList() },
                onTrash = { trashTargetIds = selectedIds.toList() },
                hasWriteAccess = hasWriteAccess,
            )
        } else {
            TopAppBar(
                title = {
                    Column {
                        Text("图库")
                        if (settings.showMeta) {
                            Text(
                                text = formatCount(count) + " 张 · " + formatBytes(totalSize),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.setShowMeta(!settings.showMeta) }) {
                        Icon(
                            imageVector = if (settings.showMeta) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (settings.showMeta) "隐藏张数与大小" else "显示张数与大小",
                        )
                    }
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            SortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = { Text(order.label) },
                                    trailingIcon = {
                                        if (order == sortOrder) {
                                            Icon(imageVector = Icons.Filled.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        viewModel.setSortOrder(order)
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { moreMenuOpen = true }) {
                            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                            listOf(
                                "搜索图片" to AppScreen.Search,
                                "存储总览" to AppScreen.Storage,
                                "操作历史" to AppScreen.History,
                                "文件夹管理" to AppScreen.FolderManager,
                                "清理报告" to AppScreen.CleanupReport,
                            ).forEach { (label, screen) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        moreMenuOpen = false
                                        viewModel.openScreen(screen)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !favoritesOnly,
                onClick = { if (favoritesOnly) viewModel.toggleFavoritesFilter() },
                label = { Text("全部") },
            )
            FilterChip(
                selected = favoritesOnly,
                onClick = { if (!favoritesOnly) viewModel.toggleFavoritesFilter() },
                label = { Text("收藏") },
            )
        }

        if (!access.full) {
            PartialAccessBanner(onRequestFullAccess = onRequestFullAccess)
        }

        IndexBanner(indexState = indexState, onRetry = viewModel::startIndex)

        when {
            indexState.phase == IndexPhase.SYNCING && rows.itemCount == 0 -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在读取系统相册索引…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            rows.itemCount == 0 -> {
                EmptyGallery(
                    phase = indexState.phase,
                    favoritesOnly = favoritesOnly,
                    onStart = viewModel::startIndex,
                )
            }

            else -> {
                GalleryGrid(
                    rows = rows,
                    columns = columns,
                    thumbnailsEnabled = settings.thumbnailCacheEnabled,
                    selectedIds = selectedIds,
                    selectionActive = selectionActive,
                    thumbnailProvider = { item -> viewModel.thumbnailFile(item) },
                    onColumnsChange = { columnOverride = it },
                    onClick = { item ->
                        if (selectionActive) {
                            selectedIds = if (item.id in selectedIds) {
                                selectedIds - item.id
                            } else {
                                selectedIds + item.id
                            }
                        } else {
                            scope.launch {
                                val ids = viewModel.viewerIds()
                                onOpenViewer(ids, ids.indexOf(item.id).coerceAtLeast(0))
                            }
                        }
                    },
                    onLongClick = { item -> selectedIds = selectedIds + item.id },
                )
            }
        }
    }

    moveTargetIds?.let { ids ->
        MoveDialog(
            viewModel = viewModel,
            mediaIds = ids,
            onDismiss = { moveTargetIds = null },
            onMessage = { text ->
                viewModel.postMessage(text)
                selectedIds = emptySet()
            },
        )
    }

    trashTargetIds?.let { ids ->
        ConfirmDialog(
            title = "移入回收站",
            text = "将把 " + ids.size + " 张图片移入回收站，30 天内可恢复，不会立即删除。",
            confirmText = "移入回收站",
            onConfirm = {
                scope.launch {
                    val result = viewModel.moveToTrash(ids)
                    viewModel.postMessage(result.message, undoTrashIds = result.trashIds)
                    selectedIds = emptySet()
                }
            },
            onDismiss = { trashTargetIds = null },
        )
    }
}

@Composable
private fun PartialAccessBanner(onRequestFullAccess: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "目前只授权了部分照片，图库可能看不到全部。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onRequestFullAccess) { Text("允许全部") }
        }
    }
}

@Composable
private fun IndexBanner(
    indexState: io.github.kurumi33.eropic.core.index.IndexState,
    onRetry: () -> Unit,
) {
    when (indexState.phase) {
        IndexPhase.HASHING, IndexPhase.DEDUPING -> {
            val total = indexState.signatureTotal.coerceAtLeast(1)
            val progress = indexState.signatureDone.toFloat() / total
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "正在计算指纹 " + indexState.signatureDone + " / " + indexState.signatureTotal,
                    style = MaterialTheme.typography.labelSmall,
                )
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }

        IndexPhase.ERROR -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "索引出错：" + (indexState.message ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }

        else -> Unit
    }
}

@Composable
private fun EmptyGallery(
    phase: IndexPhase,
    favoritesOnly: Boolean,
    onStart: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when {
                    favoritesOnly -> "还没有收藏的图片"
                    phase == IndexPhase.HASHING -> "正在建立索引…"
                    else -> "还没有照片索引"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    favoritesOnly -> "在网格里长按或点开大图后即可收藏。"
                    phase == IndexPhase.HASHING -> "首次索引需要几分钟，可以先去逛逛其他页面。"
                    else -> "Eropic 只读取系统相册索引，不会改动你的任何文件。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!favoritesOnly && phase != IndexPhase.HASHING) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onStart) { Text("开始建立索引") }
            }
        }
    }
}
