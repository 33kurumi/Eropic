package io.github.kurumi33.eropic.ui.folders

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.kurumi33.eropic.core.folders.FolderNode
import io.github.kurumi33.eropic.core.media.SortOrder
import io.github.kurumi33.eropic.core.settings.AppSettings
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.EropicTopBar
import io.github.kurumi33.eropic.ui.common.formatBytes
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
 * v0.8：顶栏「N 张 · X」改用数据库真实统计（分页的 itemCount 只算已加载的页，大文件夹会停在 800）；
 * 顶部的「包含子文件夹」开关条与子文件夹横条随上滑收起、回到顶部再滑回。
 *
 * v1.0：补上和图库一样的列数缩放 —— 顶栏「- / +」按钮 + 网格双指捏合，列数写回同一份设置（2..8）。
 *
 * @param initialIncludeSubfolders 外层网格页「包含子文件夹」选择的状态，作为本页初始值；
 *   进入后本页自己记住，不再跟随外层。
 * @param initialSort 外层选择的排序（null = 沿用图库排序），同样只作为初始值。
 * @param initialSelectionActive 从弹层「选择」进入时直接处于多选模式。
 * @param subfolders 本目录的直接子目录（由列表页从目录树直接传过来，不再查库），
 *   用于在图片网格上方渲染「子文件夹」横条；没有子目录时整条不渲染。
 * @param onOpenSubfolder 点横条里的子目录：外层负责把这一层压栈，返回键逐层回退。
 * @param refreshToken 索引刷新标记，变化时子文件夹封面与顶栏的真实张数会重新取
 *   （列表页传索引完成时间）。
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

    // 列数（v1.0）：默认跟随设置，与图库、文件夹网格页共用同一份，改了就持久化。
    // 捏合过程中只改这个临时值（避免一秒钟几十次写 Preferences），松手才写回设置；
    // 写回后 settings.gridColumns 一变，下面这行 LaunchedEffect 就把临时值清掉，两个来源不会互相打架。
    var columnOverride by remember { mutableStateOf<Int?>(null) }
    val columns = (columnOverride ?: settings.gridColumns)
        .coerceIn(AppSettings.MIN_GRID_COLUMNS, AppSettings.MAX_GRID_COLUMNS)
    LaunchedEffect(settings.gridColumns) { columnOverride = null }

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

    // 顶栏的「N 张 · X」用数据库里的真实统计。
    // 不能用 items.itemCount：那是分页已经加载进内存的条数（默认 800 张封顶），
    // 上千张的文件夹会永远停在 800。切「包含子文件夹」时 key 变了会自动重算。
    // 统计还没回来时先显示「… 张」，避免先闪一个错的数再跳变。
    val realCount by produceState<Long?>(
        null,
        folderPath,
        effectiveIncludeSubfolders,
        refreshToken,
    ) {
        value = runCatching {
            viewModel.folderCount(folderPath, effectiveIncludeSubfolders)
        }.getOrNull()
    }
    val realBytes by produceState<Long?>(
        null,
        folderPath,
        effectiveIncludeSubfolders,
        refreshToken,
    ) {
        value = runCatching {
            viewModel.folderTotalBytes(folderPath, effectiveIncludeSubfolders)
        }.getOrNull()
    }

    // 顶部两条（「包含子文件夹」开关条 + 子文件夹横条）随滚动收起、回顶滑回。
    // MediaGrid 内部自建 LazyVerticalGrid 且没有暴露 LazyGridState，拿不到
    // firstVisibleItemIndex，所以这里用嵌套滚动累计「离顶部滚了多远」：
    // 回顶时累计值归零，两条就重新滑回来。刻意不看 isScrollInProgress ——
    // 那样滑到中间停住时两条会莫名其妙又冒出来。
    val hideThresholdPx = with(LocalDensity.current) { 40.dp.toPx() }
    var scrolledPx by remember { mutableFloatStateOf(0f) }
    val barsVisible by remember(hideThresholdPx) {
        derivedStateOf { scrolledPx < hideThresholdPx }
    }
    val barsScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // consumed.y < 0：内容往上走（正在往下翻）；> 0：正在回顶。
                scrolledPx = (scrolledPx - consumed.y).coerceAtLeast(0f)
                return Offset.Zero
            }
        }
    }

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
                    folderSummaryLabel(realCount, realBytes),
                onBack = onBack,
                actions = {
                    // 列数缩放（v1.0）：与图库同一份设置，点了立即写回并重排；到边界按钮自动置灰。
                    // 基准用 settings 而不是本页 columns：捏合中途的临时值不该被按钮当成起点。
                    IconButton(
                        onClick = { viewModel.setGridColumns(settings.gridColumns - 1) },
                        enabled = settings.gridColumns > AppSettings.MIN_GRID_COLUMNS,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Remove,
                            contentDescription = "减少每行列数",
                        )
                    }
                    IconButton(
                        onClick = { viewModel.setGridColumns(settings.gridColumns + 1) },
                        enabled = settings.gridColumns < AppSettings.MAX_GRID_COLUMNS,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "增加每行列数",
                        )
                    }
                    FolderSortMenu(
                        activeSort = activeSort,
                        onSelect = { order -> sortName = order.name },
                    )
                },
            )
            // 两条一起收起 / 一起滑回；展开与收缩动画同时做大小动画，布局不会突然跳一格。
            AnimatedVisibility(
                visible = barsVisible,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn() +
                    expandVertically(expandFrom = Alignment.Top),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut() +
                    shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
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
            }
        }

        // 网格外面挂嵌套滚动（收起顶部两条）+ 双指捏合（调列数）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .nestedScroll(barsScrollConnection)
                .pinchToChangeColumns(
                    columns = columns,
                    onColumnsChange = { columnOverride = it },
                    onGestureEnd = { finalColumns ->
                        // 松手才写盘：手势过程中只改本页临时列数。
                        if (finalColumns != settings.gridColumns) {
                            viewModel.setGridColumns(finalColumns)
                        }
                    },
                ),
        ) {
            when {
                items.itemCount > 0 -> MediaGrid(
                    items = items,
                    columns = columns,
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

/**
 * 顶栏的「N 张 · X」：数据库里的真实张数 · 总大小。
 * 统计还没回来时显示省略号，不给一个刚到第一页的分页数糊弄用户。
 */
private fun folderSummaryLabel(count: Long?, bytes: Long?): String {
    val countText = if (count != null) formatCount(count) + " 张" else "… 张"
    val bytesText = if (bytes != null) formatBytes(bytes) else "…"
    return countText + " · " + bytesText
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

/**
 * 双指捏合调列数（v1.0），写法照搬 ui/gallery/GalleryDisplayGrid.kt。
 *
 * 只观察、不消费触摸事件 —— 单指滑动照旧交给下面的网格自己滚。
 * 手势过程中只用 [onColumnsChange] 改本页临时列数（不写盘），
 * 所有手指抬起后才通过 [onGestureEnd] 把最终值交给调用方持久化。
 */
@Composable
private fun Modifier.pinchToChangeColumns(
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    onGestureEnd: (Int) -> Unit,
): Modifier {
    val latestColumns = rememberUpdatedState(columns)
    val latestOnChange = rememberUpdatedState(onColumnsChange)
    val latestOnEnd = rememberUpdatedState(onGestureEnd)
    return pointerInput(Unit) {
        var accumulated = 1f
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            // 每次手势开始都从当前列数出发，避免上一轮手势的局部值盖掉顶栏「- / +」的结果。
            var current = latestColumns.value
                .coerceIn(AppSettings.MIN_GRID_COLUMNS, AppSettings.MAX_GRID_COLUMNS)
            // 只有真的改过列数才在抬手时写盘；单指滚动、点击都会走到这里，不能每次都写。
            var changed = false
            do {
                val event = awaitPointerEvent()
                val pressedCount = event.changes.count { it.pressed }
                if (pressedCount >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom.isFinite() && zoom > 0f && zoom != 1f) {
                        accumulated *= zoom
                        // 放大 1.3 倍算少一列，缩小到 0.7 倍算多一列，到头就不再回调。
                        if (accumulated > 1.3f && current > AppSettings.MIN_GRID_COLUMNS) {
                            current -= 1
                            changed = true
                            latestOnChange.value(current)
                            accumulated = 1f
                        } else if (accumulated < 0.7f && current < AppSettings.MAX_GRID_COLUMNS) {
                            current += 1
                            changed = true
                            latestOnChange.value(current)
                            accumulated = 1f
                        }
                    }
                } else {
                    // 手指不足两指时清掉累计值，免得单指滑动被算成缩放。
                    accumulated = 1f
                }
            } while (event.changes.any { it.pressed })
            if (changed) latestOnEnd.value(current)
        }
    }
}
