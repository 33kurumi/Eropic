package io.github.kurumi33.eropic.ui.cleanup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.kurumi33.eropic.ui.AppScreen
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.BatchProgressBar
import io.github.kurumi33.eropic.ui.common.EropicTopBar
import io.github.kurumi33.eropic.ui.common.formatCount
import io.github.kurumi33.eropic.ui.components.ConfirmDialog
import io.github.kurumi33.eropic.ui.components.MediaGrid
import io.github.kurumi33.eropic.ui.components.SelectionTopBar
import io.github.kurumi33.eropic.ui.components.WriteAccessHintBar
import io.github.kurumi33.eropic.ui.components.shareImages
import io.github.kurumi33.eropic.ui.fileops.BatchRenameDialog
import io.github.kurumi33.eropic.ui.fileops.CopyDialog
import io.github.kurumi33.eropic.ui.trash.MoveDialog
import kotlinx.coroutines.launch

/**
 * 组合筛选结果页。
 *
 * 支持收藏/分享/进查看器；有「所有文件访问」权限时还可真实移动与移入回收站。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilteredScreen(
    viewModel: EropicViewModel,
    filterState: FilterUiState,
    onBack: () -> Unit,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
    hasWriteAccess: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val batchState by viewModel.batchState.collectAsStateWithLifecycle()

    val filter = remember(filterState) { filterState.toFilter() }
    val items = remember(filter) {
        viewModel.filteredMedia(filter)
    }.collectAsLazyPagingItems()

    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val selectionActive = selectedIds.isNotEmpty()
    var moveTargetIds by remember { mutableStateOf<List<Long>?>(null) }
    var trashTargetIds by remember { mutableStateOf<List<Long>?>(null) }
    var renameTargetIds by remember { mutableStateOf<List<Long>?>(null) }
    var copyTargetIds by remember { mutableStateOf<List<Long>?>(null) }

    // 多选时返回键先取消选择，避免一按就退出筛选页。
    BackHandler {
        if (selectionActive) selectedIds = emptySet() else onBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selectionActive) {
            SelectionTopBar(
                selectedCount = selectedIds.size,
                onClose = { selectedIds = emptySet() },
                onSelectAll = {
                    scope.launch { selectedIds = viewModel.filteredIds(filter).toSet() }
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
                onRename = { renameTargetIds = selectedIds.toList() },
                onCopy = { copyTargetIds = selectedIds.toList() },
                onOpenPermission = { viewModel.openScreen(AppScreen.Permission) },
            )
        } else {
            EropicTopBar(
                title = "筛选结果",
                subtitle = formatCount(items.itemCount.toLong()) + " 张",
                onBack = onBack,
            )
        }

        // 没有写权限时给一条能点的提示：否则用户只看到菜单置灰，找不到授权入口。
        if (!hasWriteAccess) {
            WriteAccessHintBar(
                onOpenPermission = { viewModel.openScreen(AppScreen.Permission) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // 批量复制/重命名进行中时显示进度条（state.active 为 false 时它自己返回、不占空间）。
        BatchProgressBar(
            state = batchState,
            onPause = viewModel::pauseBatch,
            onResume = viewModel::resumeBatch,
            onCancel = viewModel::cancelBatch,
        )

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
                        val ids = viewModel.filteredIds(filter)
                        val index = ids.indexOf(item.id)
                        if (index >= 0) onOpenViewer(ids, index) else onOpenViewer(listOf(item.id), 0)
                    }
                }
            },
            onLongClick = { item -> selectedIds = selectedIds + item.id },
            modifier = Modifier.fillMaxSize(),
        )
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
            onReport = { report ->
                selectedIds = emptySet()
                viewModel.publishReport(report)
                viewModel.openScreen(AppScreen.Report(report))
            },
        )
    }

    trashTargetIds?.let { ids ->
        ConfirmDialog(
            title = "移入回收站",
            text = "将把 " + ids.size + " 张图片移入回收站，" +
                (if (settings.retention.days <= 0) "长期保留（不会自动清理），" else settings.retention.label + " 内可随时恢复，") +
                "之后可一键恢复。",
            confirmText = "移入回收站",
            destructive = true,
            onDismiss = { trashTargetIds = null },
            onConfirm = {
                trashTargetIds = null
                scope.launch {
                    val result = viewModel.moveToTrash(ids)
                    viewModel.postMessage(result.message, undoTrashIds = result.trashIds)
                    selectedIds = emptySet()
                }
            },
        )
    }

    renameTargetIds?.let { ids ->
        BatchRenameDialog(
            viewModel = viewModel,
            ids = ids,
            onDismiss = { renameTargetIds = null },
            onMessage = { text -> viewModel.postMessage(text) },
            onReport = { report ->
                selectedIds = emptySet()
                viewModel.publishReport(report)
                viewModel.openScreen(AppScreen.Report(report))
            },
        )
    }

    copyTargetIds?.let { ids ->
        CopyDialog(
            viewModel = viewModel,
            mediaIds = ids,
            onDismiss = { copyTargetIds = null },
            onMessage = { text -> viewModel.postMessage(text) },
            onReport = { report ->
                selectedIds = emptySet()
                viewModel.publishReport(report)
                viewModel.openScreen(AppScreen.Report(report))
            },
        )
    }
}
