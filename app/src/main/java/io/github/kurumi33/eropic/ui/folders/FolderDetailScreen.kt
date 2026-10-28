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
import io.github.kurumi33.eropic.core.media.SortOrder
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.formatCount
import io.github.kurumi33.eropic.ui.components.ConfirmDialog
import io.github.kurumi33.eropic.ui.components.MediaGrid
import io.github.kurumi33.eropic.ui.components.SelectionTopBar
import io.github.kurumi33.eropic.ui.components.shareImages
import io.github.kurumi33.eropic.ui.trash.MoveDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderDetailScreen(
    viewModel: EropicViewModel,
    folderPath: String,
    folderName: String,
    onBack: () -> Unit,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
    hasWriteAccess: Boolean = false,
    /** 初始是否聚合子文件夹：网格页点「查看本层全部图片」时把网格页的开关状态带进来。 */
    initialIncludeSubfolders: Boolean = false,
    /** 初始排序：外层目录树页/网格页选过的排序；为 null 时跟随图库当前排序。 */
    initialSort: SortOrder? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // 根目录 = 全部照片（MediaQuery 对空 path 不加目录条件），所以根目录下聚合开关没有意义。
    var includeSubfolders by rememberSaveable(folderPath, initialIncludeSubfolders) {
        mutableStateOf(initialIncludeSubfolders)
    }
    // 只有非根目录的开关才生效；根目录下两种取值查到的都是全部照片，统一按"不聚合"展示。
    val effectiveIncludeSubfolders = includeSubfolders && folderPath.isNotEmpty()

    // 排序：进页面时继承外层选过的排序（initialSort），之后用户在本页改就只影响本页。
    // SortOrder 不是 Saveable 类型，存枚举名；initialSort 作为 key，从网格页带着排序进来时能生效。
    var sortName by rememberSaveable(folderPath, initialSort) { mutableStateOf(initialSort?.name) }
    val sortOverride = remember(sortName) { sortOrderFromName(sortName) }
    val gallerySort by viewModel.sortOrder.collectAsStateWithLifecycle()
    val activeSort = sortOverride ?: gallerySort

    val items = remember(folderPath, effectiveIncludeSubfolders, activeSort) {
        viewModel.folderMedia(folderPath, effectiveIncludeSubfolders, activeSort)
    }.collectAsLazyPagingItems()

    var selectedIds by rememberSaveable(folderPath) { mutableStateOf<Set<Long>>(emptySet()) }
    val selectionActive = selectedIds.isNotEmpty()
    var moveTargetIds by remember { mutableStateOf<List<Long>?>(null) }
    var trashTargetIds by remember { mutableStateOf<List<Long>?>(null) }

    // 返回键：多选态先退出多选，否则回目录树（以前会直接退出 App）。
    androidx.activity.compose.BackHandler {
        if (selectionActive) selectedIds = emptySet() else onBack()
    }

    Column(Modifier.fillMaxSize()) {
        if (selectionActive) {
            SelectionTopBar(
                selectedCount = selectedIds.size,
                onClose = { selectedIds = emptySet() },
                onSelectAll = {
                    scope.launch {
                        selectedIds = viewModel.folderIds(folderPath, effectiveIncludeSubfolders, activeSort).toSet()
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
                onMove = { moveTargetIds = selectedIds.toList() },
                onTrash = { trashTargetIds = selectedIds.toList() },
                hasWriteAccess = hasWriteAccess,
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
                                append(
                                    when {
                                        folderPath.isEmpty() -> "全部照片"
                                        effectiveIncludeSubfolders -> "含子文件夹"
                                        else -> "仅当前文件夹"
                                    },
                                )
                                append(" · ")
                                append(formatCount(items.itemCount.toLong()))
                                append(" 张")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    FolderSortMenu(
                        activeSort = activeSort,
                        onSelect = { order -> sortName = order.name },
                    )
                },
            )
        }

        SubfolderModeBar(
            includeSubfolders = effectiveIncludeSubfolders,
            enabled = folderPath.isNotEmpty(),
            onToggle = { checked ->
                includeSubfolders = checked
                // 切换范围后屏幕上的照片集合会变，之前的选中项可能已经不可见，
                // 不清空的话批量移动/回收站会作用到用户看不见的照片。
                selectedIds = emptySet()
            },
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
                                val ids = viewModel.folderIds(folderPath, effectiveIncludeSubfolders, activeSort)
                                val index = ids.indexOf(item.id)
                                // 索引正好在变（后台同步/重新索引）时可能查不到这张图，
                                // 以前会 coerce 成 0，结果打开的是另一张。
                                if (index >= 0) onOpenViewer(ids, index) else onOpenViewer(listOf(item.id), 0)
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
internal fun SubfolderModeBar(
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
