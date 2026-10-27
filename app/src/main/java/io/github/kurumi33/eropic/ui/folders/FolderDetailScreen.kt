package io.github.kurumi33.eropic.ui.folders

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.formatCount
import io.github.kurumi33.eropic.ui.components.MediaGrid
import io.github.kurumi33.eropic.ui.components.SelectionTopBar
import io.github.kurumi33.eropic.ui.components.shareImages
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderDetailScreen(
    viewModel: EropicViewModel,
    folderPath: String,
    folderName: String,
    onBack: () -> Unit,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // 根目录无法用 LIKE 匹配全库，聚合开关对它无意义。
    val isRoot = folderPath.isEmpty()
    var includeSubfolders by rememberSaveable(folderPath) { mutableStateOf(false) }
    val effectiveIncludeSubfolders = includeSubfolders && !isRoot

    val items = remember(folderPath, effectiveIncludeSubfolders) {
        viewModel.folderMedia(folderPath, effectiveIncludeSubfolders)
    }.collectAsLazyPagingItems()

    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val selectionActive = selectedIds.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        if (selectionActive) {
            SelectionTopBar(
                selectedCount = selectedIds.size,
                onClose = { selectedIds = emptySet() },
                onSelectAll = {
                    scope.launch {
                        selectedIds = viewModel.folderIds(folderPath, effectiveIncludeSubfolders).toSet()
                    }
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
            )
        } else {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text(text = folderName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = buildString {
                                append(if (effectiveIncludeSubfolders) "含子文件夹" else "仅当前文件夹")
                                append(" · ")
                                append(formatCount(items.itemCount.toLong()))
                                append(" 张")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }

        SubfolderModeBar(
            includeSubfolders = includeSubfolders,
            enabled = !isRoot,
            onToggle = { includeSubfolders = it },
        )

        when {
            items.itemCount == 0 && items.loadState.refresh is androidx.paging.LoadState.Loading -> {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }

            items.itemCount == 0 -> {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "这个文件夹下没有照片",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                MediaGrid(
                    items = items,
                    columns = settings.gridColumns,
                    thumbnailsEnabled = settings.thumbnailCacheEnabled,
                    selectedIds = selectedIds,
                    selectionActive = selectionActive,
                    thumbnailProvider = { item -> viewModel.thumbnailFile(item) },
                    onClick = { item ->
                        if (selectionActive) {
                            selectedIds = if (item.id in selectedIds) {
                                selectedIds - item.id
                            } else {
                                selectedIds + item.id
                            }
                        } else {
                            scope.launch {
                                val ids = viewModel.folderIds(folderPath, effectiveIncludeSubfolders)
                                onOpenViewer(ids, ids.indexOf(item.id).coerceAtLeast(0))
                            }
                        }
                    },
                    onLongClick = { item -> selectedIds = selectedIds + item.id },
                )
            }
        }
    }
}

@Composable
private fun SubfolderModeBar(
    includeSubfolders: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "包含子文件夹",
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (enabled) {
                    if (includeSubfolders) "显示当前文件夹及其所有子文件夹里的照片" else "只看当前文件夹里的照片"
                } else {
                    "根目录包含全部照片，无需再聚合"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = includeSubfolders && enabled,
            onCheckedChange = onToggle,
            enabled = enabled,
        )
    }
}
