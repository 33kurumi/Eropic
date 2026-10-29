package io.github.kurumi33.eropic.ui.folders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.kurumi33.eropic.core.folders.FolderNode
import io.github.kurumi33.eropic.core.media.SortOrder
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.EropicTopBar
import io.github.kurumi33.eropic.ui.common.formatCount
import io.github.kurumi33.eropic.ui.components.ConfirmDialog
import io.github.kurumi33.eropic.ui.components.MediaGrid
import io.github.kurumi33.eropic.ui.components.SelectionTopBar
import io.github.kurumi33.eropic.ui.components.shareImages
import io.github.kurumi33.eropic.ui.trash.MoveDialog
import kotlinx.coroutines.launch

/**
 * 某个文件夹的照片详情页（列表模式点进来、网格弹层「打开 / 选择」也进这里）。
 *
 * 核心是「包含子文件夹」开关：关掉只看这一层的照片，打开就把所有子目录里的照片一起显示。
 * 关掉时的结果与目录树上那一行的 directCount 一致；打开时与 totalCount 一致（只算存在的图片）。
 *
 * @param initialIncludeSubfolders 外层网格页「包含子文件夹」选择的状态，作为本页初始值；
 *   进入后本页自己记住，不再跟随外层。
 * @param initialSort 外层选择的排序（null = 沿用图库排序），同样只作为初始值。
 * @param initialSelectionActive 从弹层「选择」进入时直接处于多选模式。
 * @param subfolders 本目录的直接子目录（由列表页从目录树直接传过来，不再查库），
 *   用于在图片网格上方渲染「子文件夹」横条；没有子目录时整条不渲染。
 * @param onOpenSubfolder 点横条里的子目录：外层负责把这一层压栈，返回键逐层回退。
 * @param refreshToken 索引刷新标记，变化时子文件夹封面会重新取（列表页传索引完成时间）。
 */
@Composable
fun FolderDetailScreen(
    viewModel: EropicViewModel,
    folderPath: String,
    folderName: String,
    onBack: () -> Unit,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
    hasWriteAccess: Boolean = false,
    initialIncludeSubfolders: Boolean = false,
    initialSort: SortOrder? = null,
    initialSelectionActive: Boolean = false,
    subfolders: List<FolderNode> = emptyList(),
    onOpenSubfolder: (String) -> Unit = {},
    refreshToken: Long = 0L,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 根目录是合成节点，本来就已经是整库，没必要也不会再聚合。
    var includeSubfolders by rememberSaveable(folderPath, initialIncludeSubfolders) {
        mutableStateOf(initialIncludeSubfolders)
    }
    val effectiveIncludeSubfolders = includeSubfolders && folderPath.isNotEmpty()

    // 排序：没选过就用外层的初值（可能为 null，即跟随图库）；选了就固定在本页。
    var sortName by rememberSaveable(folderPath, initialSort) { mutableStateOf(initialSort?.name) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val gallerySort by viewModel.sortOrder.collectAsStateWithLifecycle()
    val activeSort = remember(sortName) { sortOrderFromName(sortName) } ?: gallerySort

    // 多选：从弹层「选择」进来的会直接是 true。
    var selectionMode by rememberSaveable(folderPath, initialSelectionActive) {
        mutableStateOf(initialSelectionActive)
    }
    var selectedIds by rememberSaveable(folderPath) { mutableStateOf(setOf<Long>()) }
    var moveTargetIds by remember { mutableStateOf<List<Long>?>(null) }
    var trashTargetIds by remember { mutableStateOf<List<Long>?>(null) }

    // 分页流随「包含子文件夹 / 排序」重建：开关一拨就是一次重新订阅，不需要手动刷新。
    val items = remember(folderPath, effectiveIncludeSubfolders, activeSort) {
        viewModel.folderMedia(folderPath, effectiveIncludeSubfolders, activeSort)
    }.collectAsLazyPagingItems()

    // 多选时返回键先退出多选，再按一次才回上一页。
    BackHandler {
        if (selectionMode) {
            selectionMode = false
            selectedIds = emptySet()
        } else {
            onBack()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selectionMode) {
            SelectionTopBar(
                selectedCount = selectedIds.size,
                onClose = {
                    selectionMode = false
                    selectedIds = emptySet()
                },
                onSelectAll = {
                    scope.launch {
                        selectedIds = viewModel
                            .folderIds(folderPath, effectiveIncludeSubfolders, activeSort)
                            .toSet()
                    }
                },
                onFavorite = {
                    val ids = selectedIds.toList()
                    scope.launch {
                        viewModel.setFavorites(ids, true)
                        selectedIds = emptySet()
                    }
                },
                onUnfavorite = {
                    val ids = selectedIds.toList()
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
            EropicTopBar(
                title = folderName,
                subtitle = modeLabel(folderPath, effectiveIncludeSubfolders) + " · " +
                    formatCount(items.itemCount.toLong()) + " 张",
                onBack = onBack,
                actions = {
                    FolderSortMenu(
                        activeSort = activeSort,
                        onSelect = { order -> sortName = order.name },
                    )
                },
            )
            SubfolderModeBar(
                includeSubfolders = includeSubfolders,
                enabled = folderPath.isNotEmpty(),
                onToggle = { checked ->
                    includeSubfolders = checked
                    // 换了一批图片，之前的选择已经没有意义。
                    selectedIds = emptySet()
                },
            )
            // 本目录的直接子目录横条：继续往下走一层不用退回列表页。
            // 多选模式下不渲染，避免误触（此时页面顶部是多选顶栏）。
            SubfolderStrip(
                subfolders = subfolders,
                viewModel = viewModel,
                refreshToken = refreshToken,
                thumbnailsEnabled = settings.thumbnailCacheEnabled,
                folderCovers = settings.folderCovers,
                onOpenSubfolder = onOpenSubfolder,
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                items.itemCount > 0 -> MediaGrid(
                    items = items,
                    columns = settings.gridColumns,
                    thumbnailsEnabled = settings.thumbnailCacheEnabled,
                    selectedIds = selectedIds,
                    selectionActive = selectionMode,
                    thumbnailProvider = { item -> viewModel.thumbnailFile(item) },
                    onClick = { item ->
                        if (selectionMode) {
                            selectedIds = toggleSelection(selectedIds, item.id)
                        } else {
                            scope.launch {
                                val ids = viewModel.folderIds(
                                    folderPath,
                                    effectiveIncludeSubfolders,
                                    activeSort,
                                )
                                val index = ids.indexOf(item.id)
                                if (index >= 0) {
                                    onOpenViewer(ids, index)
                                } else {
                                    // 列表在打开查看器前刚好变了（比如别处删了图）：至少能看这一张。
                                    onOpenViewer(listOf(item.id), 0)
                                }
                            }
                        }
                    },
                    onLongClick = { item ->
                        selectionMode = true
                        selectedIds = selectedIds + item.id
                    },
                )

                items.loadState.refresh is LoadState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                items.loadState.refresh is LoadState.Error -> EmptyFolderContent(
                    title = "加载失败",
                    hint = "下拉重试，或回到上一页再进来。",
                )

                else -> EmptyFolderContent(
                    title = "这个文件夹下没有照片",
                    hint = when {
                        // 子目录里有图但本层没有：直接指路到子文件夹横条。
                        subfolders.isNotEmpty() && !effectiveIncludeSubfolders ->
                            "点上方「子文件夹」里的一项进去看看，或打开「包含子文件夹」。"
                        folderPath.isNotEmpty() && !effectiveIncludeSubfolders && !includeSubfolders ->
                            "打开上方「包含子文件夹」可以一起看所有子文件夹里的照片。"
                        else -> "换个文件夹，或到图库页重新建立索引。"
                    },
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
                selectionMode = false
            },
        )
    }

    trashTargetIds?.let { ids ->
        ConfirmDialog(
            title = "移入回收站",
            text = "将把 " + formatCount(ids.size.toLong()) +
                " 张图片移入回收站，30 天内可恢复，不会立即删除。",
            confirmText = "移入回收站",
            onConfirm = {
                scope.launch {
                    val result = viewModel.moveToTrash(ids)
                    viewModel.postMessage(result.message, undoTrashIds = result.trashIds)
                    selectedIds = emptySet()
                    selectionMode = false
                }
            },
            onDismiss = { trashTargetIds = null },
        )
    }
}

/** 顶栏副标题前半段：说明当前是哪种聚合口径。 */
private fun modeLabel(folderPath: String, includeSubfolders: Boolean): String = when {
    folderPath.isEmpty() -> "全部照片"
    includeSubfolders -> "含子文件夹"
    else -> "仅当前文件夹"
}

/** 多选切换：已选就取消，未选就加上。 */
private fun toggleSelection(selected: Set<Long>, id: Long): Set<Long> =
    if (id in selected) selected - id else selected + id

/** 空态 / 出错态的统一展示。 */
@Composable
private fun EmptyFolderContent(
    title: String,
    hint: String,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * 「包含子文件夹」开关条：开关本身 + 一行解释当前口径。
 *
 * 根目录（合成节点）时开关禁用并说明原因，避免用户以为点了没反应。
 * 网格页的开关放在页面级三点菜单里（FolderPageMenu），文案与这里保持一致。
 */
@Composable
internal fun SubfolderModeBar(
    includeSubfolders: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "包含子文件夹",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = when {
                    !enabled -> "根目录包含全部照片，无需再聚合"
                    includeSubfolders -> "显示当前文件夹及其所有子文件夹里的照片"
                    else -> "只看当前文件夹里的照片"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = includeSubfolders && enabled,
            onCheckedChange = onToggle,
            enabled = enabled,
        )
    }
}
