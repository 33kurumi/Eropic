package io.github.kurumi33.eropic.ui.cleanup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.formatCount
import io.github.kurumi33.eropic.ui.components.MediaGrid
import io.github.kurumi33.eropic.ui.components.SelectionTopBar
import io.github.kurumi33.eropic.ui.components.shareImages
import kotlinx.coroutines.launch

/**
 * 组合筛选结果页。
 *
 * 只读：支持收藏/分享/进查看器，不提供删除或移动（M3）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilteredScreen(
    viewModel: EropicViewModel,
    filterState: FilterUiState,
    onBack: () -> Unit,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val filter = remember(filterState) { filterState.toFilter() }
    val items = remember(filter) {
        viewModel.filteredMedia(filter)
    }.collectAsLazyPagingItems()

    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val selectionActive = selectedIds.isNotEmpty()

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
            )
        } else {
            TopAppBar(
                title = {
                    Column {
                        Text("筛选结果")
                        Text(
                            text = formatCount(items.itemCount.toLong()) + " 张",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        }

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
                        onOpenViewer(ids, ids.indexOf(item.id).coerceAtLeast(0))
                    }
                }
            },
            onLongClick = { item -> selectedIds = selectedIds + item.id },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
