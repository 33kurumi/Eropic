package io.github.kurumi33.eropic.ui.folders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kurumi33.eropic.core.folders.FolderNode
import io.github.kurumi33.eropic.core.folders.FolderTreeBuilder
import io.github.kurumi33.eropic.core.folders.findFolderNode
import io.github.kurumi33.eropic.core.folders.parentFolderPath
import io.github.kurumi33.eropic.core.folders.splitFolderPath
import io.github.kurumi33.eropic.core.media.SortOrder
import io.github.kurumi33.eropic.core.model.FolderRow
import io.github.kurumi33.eropic.ui.AppScreen
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.EropicTopBar
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount
import io.github.kurumi33.eropic.ui.components.ConfirmDialog
import kotlinx.coroutines.launch

/**
 * 「文件夹」页（v0.5）：列表（目录树）与网格（文件夹卡片）两种浏览方式。
 *
 * - 列表模式：把树按展开状态摊平成 List<FolderNode> 交给 LazyColumn，长列表也能懒加载；
 *   整行点击进入该目录的照片，行尾 ⋮ 打开文件夹操作弹层，图标左侧的箭头展开/收起子目录。
 * - 网格模式：只展示当前这一层的子文件夹卡片（封面 + 名字 + 张数 + 大小），点卡片下钻，
 *   长按卡片打开同一个操作弹层；看图走列表模式或弹层里的「打开 / 选择」。
 *
 * 两种模式的浏览位置都用 rememberSaveable 保存，转屏、切 Tab 都不会丢。
 */
@Composable
fun FoldersSection(
    viewModel: EropicViewModel,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
    hasWriteAccess: Boolean = false,
    // v0.8：文件夹树末尾追加两个「虚拟文件夹」入口（视频 / GIF），计数由 SpecialFoldersSection 自己查。
    onOpenVideos: () -> Unit = {},
    onOpenGifs: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    // ---- 浏览位置（全部可保存）----
    var gridLayout by rememberSaveable { mutableStateOf(false) }
    // 列表模式展开的目录，按 node.path 存；默认全部收起，点箭头才展开。
    var expandedPaths by rememberSaveable { mutableStateOf(listOf<String>()) }
    val expanded = remember(expandedPaths) { expandedPaths.toSet() }
    // 网格模式当前层级（"" = 根层级）
    var gridPath by rememberSaveable { mutableStateOf("") }
    // 打开文件夹时是否包含子文件夹：网格页顶栏菜单里切换，进入详情页时作为初始值。
    var includeSubfolders by rememberSaveable { mutableStateOf(false) }
    // 详情页的浏览栈：最后一项是当前看的目录，空表示没在看图。
    // 用栈而不是单个路径：详情页顶部还能点「子文件夹」继续往下走，返回键才能一级级退回。
    var detailStack by rememberSaveable { mutableStateOf(listOf<String>()) }
    val viewingPath = detailStack.lastOrNull()
    // 从弹层「选择」进入时，详情页直接处于多选模式。
    var viewingNeedsSelection by rememberSaveable { mutableStateOf(false) }

    // 改名 / 删除这类写操作不会推进索引状态，本地留一个令牌让目录聚合重新查一次。
    var refreshTick by remember { mutableStateOf(0) }

    val indexState by viewModel.indexState.collectAsStateWithLifecycle()
    // 索引完成、或本页做过改名/删除后重新聚合一次；键打包成一个，避免依赖具体的重载数量。
    val rows by produceState(
        emptyList<FolderRow>(),
        listOf(indexState.phase, indexState.finishedAtMs, refreshTick),
    ) {
        value = viewModel.loadFolderRows()
    }
    val tree = remember(rows) { FolderTreeBuilder.build(rows) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pinnedPaths = remember(settings.pinnedFolders) { pinnedPathSet(settings.pinnedFolders) }

    // 排序：默认跟随图库当前排序（sortOverride = null），在本页选过之后就固定下来。
    // 枚举不是 Saveable，按枚举名存盘；列表页与网格页共用这一份。
    var sortName by rememberSaveable { mutableStateOf<String?>(null) }
    val sortOverride = remember(sortName) { sortOrderFromName(sortName) }
    val gallerySort by viewModel.sortOrder.collectAsStateWithLifecycle()
    val activeSort = sortOverride ?: gallerySort

    // 操作弹层 / 对话框的目标（同一时间只会有一个）
    var actionsTarget by remember { mutableStateOf<FolderNode?>(null) }
    var renameTarget by remember { mutableStateOf<FolderNode?>(null) }
    var deleteTarget by remember { mutableStateOf<FolderNode?>(null) }

    // ---- v0.8：列表 / 网格各自独立的多选（长按进入，返回键先退出）----
    // 两份选择状态互不影响：在网格里选了文件夹，切回列表时列表依然是自己的选择。
    var listSelecting by rememberSaveable { mutableStateOf(false) }
    var listSelected by rememberSaveable(saver = folderSelectionSaver) {
        mutableStateOf(emptyList<String>())
    }
    var gridSelecting by rememberSaveable { mutableStateOf(false) }
    var gridSelected by rememberSaveable(saver = folderSelectionSaver) {
        mutableStateOf(emptyList<String>())
    }
    // 批量「移入回收站」的目标：非空时弹二次确认；同时记录是哪一侧发起的，确认后只退出那一侧的选择。
    var batchTrashPaths by remember { mutableStateOf<List<String>?>(null) }
    var batchTrashFromGrid by remember { mutableStateOf(false) }

    // 目录被删 / 改名后，保存下来的浏览位置可能已经不存在：退回去，避免白屏。
    LaunchedEffect(tree, gridPath) {
        if (gridPath.isNotEmpty() && tree.isNotEmpty() && findFolderNode(tree, gridPath) == null) {
            gridPath = ""
        }
    }
    LaunchedEffect(tree, detailStack) {
        if (tree.isEmpty()) return@LaunchedEffect
        // 目录被删 / 改名后，栈里已经不存在的层级要退掉，避免详情页指着不存在的目录。
        val valid = detailStack.filter { path -> path.isEmpty() || findFolderNode(tree, path) != null }
        if (valid != detailStack) {
            detailStack = valid
            if (valid.isEmpty()) viewingNeedsSelection = false
        }
    }

    // 目录被删 / 改名后，选中的路径可能已经不在树里：剔掉它们，选择条上的数量才不会虚高。
    // 树还在加载（空列表）时跳过，避免转屏恢复出来的选择被清空。
    LaunchedEffect(tree) {
        if (tree.isEmpty()) return@LaunchedEffect
        val validList = listSelected.filter { path -> findFolderNode(tree, path) != null }
        if (validList != listSelected) {
            listSelected = validList
            if (validList.isEmpty() && listSelecting) listSelecting = false
        }
        val validGrid = gridSelected.filter { path -> findFolderNode(tree, path) != null }
        if (validGrid != gridSelected) {
            gridSelected = validGrid
            if (validGrid.isEmpty() && gridSelecting) gridSelecting = false
        }
    }

    /** 选中的路径 → 交给 ViewModel 的相对路径（去掉结尾斜杠、去掉根目录、去掉已经失效的）。 */
    fun selectedRelativePaths(paths: List<String>): List<String> = paths
        .filter { path -> path.isNotEmpty() && findFolderNode(tree, path) != null }
        .map { path -> path.trim('/') }
        .filter { path -> path.isNotEmpty() }

    fun exitListSelection() {
        listSelecting = false
        listSelected = emptyList()
    }

    fun exitGridSelection() {
        gridSelecting = false
        gridSelected = emptyList()
    }

    fun togglePath(path: String, current: List<String>): List<String> =
        if (path in current) current - path else current + path

    /** 批量置顶 / 取消置顶：纯偏好设置，不碰文件系统，提示由 ViewModel 统一发。 */
    fun applyPin(fromGrid: Boolean, pinned: Boolean) {
        val paths = selectedRelativePaths(if (fromGrid) gridSelected else listSelected)
        if (paths.isEmpty()) {
            viewModel.postMessage("选中的文件夹已经不存在了")
        } else {
            viewModel.setFoldersPinned(paths, pinned)
        }
        if (fromGrid) exitGridSelection() else exitListSelection()
    }

    /** 批量移入回收站：先弹二次确认，确认后才真正删除。 */
    fun requestTrash(fromGrid: Boolean) {
        val paths = selectedRelativePaths(if (fromGrid) gridSelected else listSelected)
        if (paths.isEmpty()) {
            viewModel.postMessage("选中的文件夹已经不存在了")
            if (fromGrid) exitGridSelection() else exitListSelection()
        } else {
            batchTrashFromGrid = fromGrid
            batchTrashPaths = paths
        }
    }

    // 多选时系统返回键先退出选择，而不是直接退出页面。
    BackHandler(enabled = listSelecting || gridSelecting) {
        exitListSelection()
        exitGridSelection()
    }

    when {
        // 还没有索引：给提示，同时保留布局切换入口，避免空库里什么都点不了。
        tree.isEmpty() -> EmptyFoldersScaffold(
            gridLayout = gridLayout,
            onToggleLayout = { gridLayout = !gridLayout },
        )

        // 正在看图：两种布局共用同一个详情页。
        viewingPath != null -> {
            val path = viewingPath.orEmpty()
            // 详情页顶部的「子文件夹」区域直接用树里的直接子目录，不再查库。
            val subfolders = remember(tree, path) {
                if (path.isEmpty()) tree else findFolderNode(tree, path)?.children.orEmpty()
            }
            FolderDetailScreen(
                viewModel = viewModel,
                folderPath = path,
                folderName = folderNameOf(tree, path),
                onBack = {
                    // 在详情页里下钻过就退一层，否则回到列表 / 网格。
                    if (detailStack.size > 1) {
                        detailStack = detailStack.dropLast(1)
                    } else {
                        detailStack = emptyList()
                        viewingNeedsSelection = false
                    }
                },
                onOpenViewer = onOpenViewer,
                hasWriteAccess = hasWriteAccess,
                initialIncludeSubfolders = includeSubfolders && path.isNotEmpty(),
                initialSort = sortOverride,
                initialSelectionActive = viewingNeedsSelection,
                subfolders = subfolders,
                onOpenSubfolder = { subPath ->
                    detailStack = detailStack + subPath
                    viewingNeedsSelection = false
                },
                refreshToken = indexState.finishedAtMs + refreshTick,
            )
        }

        gridLayout -> FolderGridScreen(
            viewModel = viewModel,
            tree = tree,
            path = gridPath,
            includeSubfolders = includeSubfolders,
            refreshToken = indexState.finishedAtMs + refreshTick,
            activeSort = activeSort,
            onSortSelected = { order -> sortName = order.name },
            onToggleIncludeSubfolders = { checked -> includeSubfolders = checked },
            onOpenFolder = { path -> gridPath = path },
            onShowActions = { node -> actionsTarget = node },
            onUp = { parentFolderPath(gridPath)?.let { parent -> gridPath = parent } },
            onSwitchToList = { gridLayout = false },
            // v0.8 多选：网格页自己一份状态，与列表页互不影响。
            selecting = gridSelecting,
            selectedPaths = gridSelected,
            hasWriteAccess = hasWriteAccess,
            onEnterSelection = { path ->
                gridSelecting = true
                gridSelected = listOf(path)
            },
            onToggleSelect = { path -> gridSelected = togglePath(path, gridSelected) },
            onExitSelection = { exitGridSelection() },
            onSelectAll = { paths -> gridSelected = paths },
            onPinSelected = { applyPin(fromGrid = true, pinned = true) },
            onUnpinSelected = { applyPin(fromGrid = true, pinned = false) },
            onTrashSelected = { requestTrash(fromGrid = true) },
        )

        else -> FolderTreeContent(
            tree = tree,
            showMeta = settings.showMeta,
            pinnedPaths = pinnedPaths,
            expanded = expanded,
            onToggleExpand = { path ->
                expandedPaths = if (path in expandedPaths) expandedPaths - path else expandedPaths + path
            },
            onOpenFolder = { path ->
                detailStack = listOf(path)
                viewingNeedsSelection = false
            },
            onShowActions = { node -> actionsTarget = node },
            onSwitchToGrid = { gridLayout = true },
            onOpenSearch = { viewModel.openScreen(AppScreen.Search) },
            activeSort = activeSort,
            onSortSelected = { order -> sortName = order.name },
            // v0.8 多选：列表页自己的选择状态。
            selecting = listSelecting,
            selectedPaths = listSelected,
            hasWriteAccess = hasWriteAccess,
            onEnterSelection = { path ->
                listSelecting = true
                listSelected = listOf(path)
            },
            onToggleSelect = { path -> listSelected = togglePath(path, listSelected) },
            onExitSelection = { exitListSelection() },
            onSelectAll = { paths -> listSelected = paths },
            onPinSelected = { applyPin(fromGrid = false, pinned = true) },
            onUnpinSelected = { applyPin(fromGrid = false, pinned = false) },
            onTrashSelected = { requestTrash(fromGrid = false) },
            // v0.8：目录树之后跟两个「虚拟文件夹」（视频 / GIF），随列表一起滚动。
            footer = {
                SpecialFoldersSection(
                    viewModel = viewModel,
                    onOpenVideos = onOpenVideos,
                    onOpenGifs = onOpenGifs,
                )
            },
        )
    }

    // ---- 文件夹操作弹层：网格卡片右下角 ⋮ / 列表行尾 ⋮ 打开 ----
    actionsTarget?.let { node ->
        val relativePath = node.path.trim('/')
        val pinned = relativePath.lowercase() in pinnedPaths
        FolderActionsSheet(
            relativePath = relativePath,
            displayName = node.name,
            pinned = pinned,
            hasWriteAccess = hasWriteAccess,
            onDismiss = { actionsTarget = null },
            onOpen = {
                detailStack = listOf(node.path)
                viewingNeedsSelection = false
            },
            onRename = { renameTarget = node },
            onChangeCover = { viewModel.openScreen(AppScreen.FolderCover(relativePath)) },
            onTogglePin = { viewModel.setFolderPinned(relativePath, !pinned) },
            onDelete = { deleteTarget = node },
            onSelect = {
                detailStack = listOf(node.path)
                viewingNeedsSelection = true
            },
        )
    }

    renameTarget?.let { node ->
        RenameFolderDialog(
            viewModel = viewModel,
            relativePath = node.path.trim('/'),
            currentName = node.name,
            onDismiss = { renameTarget = null },
            onMessage = { text ->
                viewModel.postMessage(text)
                // 名字变了，目录路径也变了：重新聚合一次，顺便让失效的浏览位置自动退回。
                refreshTick++
            },
        )
    }

    deleteTarget?.let { node ->
        val relativePath = node.path.trim('/')
        ConfirmDialog(
            title = "删除文件夹",
            text = "「" + node.name + "」里的 " + formatCount(node.totalCount) +
                " 张图片会移入回收站，可在回收站保留 30 天后恢复。\n\n" +
                "目录里的视频等未索引文件不会被删除。",
            confirmText = "确定",
            destructive = true,
            onConfirm = {
                deleteTarget = null
                if (relativePath.isEmpty()) {
                    viewModel.postMessage("根目录不能删除")
                } else {
                    scope.launch {
                        val result = viewModel.trashFolder(relativePath)
                        viewModel.postMessage(result.message)
                        refreshTick++
                    }
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }

    // ---- v0.8：批量移入回收站（二次确认）----
    batchTrashPaths?.let { paths ->
        ConfirmDialog(
            title = "移入回收站",
            text = "将把 " + paths.size + " 个文件夹（及其中的图片）移入回收站，30 天内可恢复。" +
                "\n\n目录里的视频等未索引文件不会被删除。",
            confirmText = "移入回收站",
            destructive = true,
            onConfirm = {
                batchTrashPaths = null
                if (batchTrashFromGrid) exitGridSelection() else exitListSelection()
                scope.launch {
                    val result = viewModel.trashFolders(paths)
                    viewModel.postMessage(result.message)
                    // 目录集合变了，重新聚合一次；失效的浏览位置会被上面的 LaunchedEffect 退回。
                    refreshTick++
                }
            },
            onDismiss = { batchTrashPaths = null },
        )
    }
}

/** 列表模式：目录树 + 顶栏的布局切换按钮。 */
@Composable
private fun FolderTreeContent(
    tree: List<FolderNode>,
    showMeta: Boolean,
    pinnedPaths: Set<String>,
    expanded: Set<String>,
    onToggleExpand: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onShowActions: (FolderNode) -> Unit,
    onSwitchToGrid: () -> Unit,
    onOpenSearch: () -> Unit,
    activeSort: SortOrder,
    onSortSelected: (SortOrder) -> Unit,
    // v0.8 多选：选择状态由 FoldersSection 持有，这里只负责画选择条和行上的选中态。
    selecting: Boolean,
    selectedPaths: List<String>,
    hasWriteAccess: Boolean,
    onEnterSelection: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onExitSelection: () -> Unit,
    onSelectAll: (List<String>) -> Unit,
    onPinSelected: () -> Unit,
    onUnpinSelected: () -> Unit,
    onTrashSelected: () -> Unit,
    // 列表末尾追加的内容：由 FoldersSection 传入「虚拟文件夹」入口，这里只管插进 LazyColumn。
    footer: @Composable () -> Unit = {},
) {
    // 把树按展开状态摊平成一行行：LazyColumn 只组合可见行，展开几千个目录也不卡。
    val visibleNodes = remember(tree, expanded, pinnedPaths) {
        flattenVisible(tree, expanded, pinnedPaths)
    }
    val selectedSet = remember(selectedPaths) { selectedPaths.toSet() }
    val totalBytes = remember(tree) { tree.sumOf { it.totalBytes } }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selecting) {
            FolderSelectionBar(
                count = selectedPaths.size,
                onClose = onExitSelection,
                onSelectAll = {
                    // 「全选」只选当前列表里看得到的文件夹：收起的子目录不在可见行里，不算在内。
                    onSelectAll(
                        visibleNodes
                            .map { node -> node.path }
                            .filter { path -> path.isNotEmpty() },
                    )
                },
                onPin = onPinSelected,
                onUnpin = onUnpinSelected,
                onTrash = onTrashSelected,
                hasWriteAccess = hasWriteAccess,
            )
        } else {
            EropicTopBar(
                title = "文件夹",
                subtitle = formatCount(tree.size.toLong()) +
                    " 个文件夹 · 合计 " + formatBytes(totalBytes),
                // v1.0：五个主页统一用紧凑顶栏（整体上移 16dp），二级页保持标准高度。
                compactHeight = true,
                actions = {
                    FolderPageMenu(
                        activeSort = activeSort,
                        onSortSelected = onSortSelected,
                        // 列表模式本身就是整棵树，不需要「包含子文件夹」这个打开选项。
                        includeSubfolders = null,
                        onToggleIncludeSubfolders = {},
                    )
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "搜索图片",
                        )
                    }
                    IconButton(onClick = onSwitchToGrid) {
                        Icon(
                            imageVector = Icons.Filled.GridView,
                            contentDescription = "切换为网格视图",
                        )
                    }
                },
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = visibleNodes, key = { node -> node.path }) { node ->
                FolderRowItem(
                    node = node,
                    expanded = node.path in expanded,
                    showMeta = showMeta,
                    pinned = node.path.isNotEmpty() &&
                        node.path.trim('/').lowercase() in pinnedPaths,
                    selecting = selecting,
                    selected = node.path in selectedSet,
                    onToggleExpand = { onToggleExpand(node.path) },
                    onClick = { onOpenFolder(node.path) },
                    onToggleSelect = { onToggleSelect(node.path) },
                    onEnterSelection = { onEnterSelection(node.path) },
                    onShowActions = { onShowActions(node) },
                )
            }
            // 多选模式下隐藏：这两个是虚拟入口，不参与文件夹选择。
            if (!selecting) {
                item(key = "special-folders") { footer() }
            }
        }
    }
}

/**
 * 目录树的一行：缩进按深度，文件夹图标左侧是展开箭头，行尾是操作菜单，点击进入图片。
 *
 * v0.8 多选：长按任意一行进入多选（根目录是合成节点，不参与），进入后点按只切换选中；
 * 左侧的展开箭头换成选中圆点，行尾菜单收起，避免和批量操作混在一起。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRowItem(
    node: FolderNode,
    expanded: Boolean,
    showMeta: Boolean,
    pinned: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onToggleExpand: () -> Unit,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onEnterSelection: () -> Unit,
    onShowActions: () -> Unit,
) {
    // 根目录没有对应的真实目录，不能批量置顶 / 删除，选择模式下也不给选中态。
    val selectable = node.path.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = {
                    if (selecting) {
                        if (selectable) onToggleSelect()
                    } else {
                        onClick()
                    }
                },
                onLongClick = {
                    if (selectable) {
                        if (selecting) onToggleSelect() else onEnterSelection()
                    }
                },
            )
            .padding(start = (node.depth * 16).dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 选择模式：左侧固定位置显示选中圆点，避免行尾再挤一个勾选框。
        // 普通模式：展开箭头放在文件夹图标左边：有子目录才可点，没有子目录时留同宽占位，
        // 这样所有行的文件夹图标左边界都在同一列上，缩进只由深度决定。
        if (selecting && selectable) {
            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "已选中",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        } else if (node.children.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggleExpand),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                    contentDescription = if (expanded) "收起子文件夹" else "展开子文件夹",
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            Spacer(Modifier.width(32.dp))
        }
        Icon(
            imageVector = if (node.children.isEmpty()) {
                Icons.Filled.PhotoLibrary
            } else {
                Icons.Filled.Folder
            },
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (pinned) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "已置顶",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (showMeta) {
                Text(
                    text = formatCount(node.totalCount) + " 张 · " + formatBytes(node.totalBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 根目录是合成出来的伪节点，没有改名 / 删除 / 置顶的意义，不给菜单入口。
        // 选择模式下收起单行菜单：此时的操作都走顶部的批量选择条。
        if (selecting) {
            Spacer(Modifier.width(12.dp))
        } else if (node.path.isNotEmpty()) {
            IconButton(onClick = onShowActions) {
                Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "文件夹操作")
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

/** 完全没有索引时的页面：保留布局切换入口，内容只给建立索引的提示。 */
@Composable
private fun EmptyFoldersScaffold(
    gridLayout: Boolean,
    onToggleLayout: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        EropicTopBar(
            title = "文件夹",
            // 主页空态：高度与有索引时保持一致，避免建立索引前后顶栏跳 16dp。
            compactHeight = true,
            actions = {
                IconButton(onClick = onToggleLayout) {
                    Icon(
                        imageVector = if (gridLayout) {
                            Icons.AutoMirrored.Filled.List
                        } else {
                            Icons.Filled.GridView
                        },
                        contentDescription = if (gridLayout) "切换为列表视图" else "切换为网格视图",
                    )
                }
            },
        )
        EmptyFolders()
    }
}

@Composable
private fun EmptyFolders() {
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
                text = "还没有索引",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = "请先到图库页建立索引。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 从树里取某个路径的显示名；根层级或节点已消失时用路径末段兜底。 */
internal fun folderNameOf(tree: List<FolderNode>, path: String): String {
    if (path.isEmpty()) return "根目录"
    findFolderNode(tree, path)?.let { return it.name }
    return splitFolderPath(path).lastOrNull() ?: path
}

/**
 * 目录树摊平：按展开状态递归收集可见节点，并按置顶规则调整同层顺序。
 *
 * 展开状态读的是快照值，展开 / 收起会自动触发重组；LazyColumn 只组合可见行。
 */
internal fun flattenVisible(
    nodes: List<FolderNode>,
    expanded: Set<String>,
    pinnedPaths: Set<String>,
): List<FolderNode> {
    val visible = ArrayList<FolderNode>(nodes.size)
    fun append(current: List<FolderNode>) {
        for (node in orderPinnedFolders(current, pinnedPaths)) {
            visible += node
            if (node.path in expanded) append(node.children)
        }
    }
    append(nodes)
    return visible
}

/**
 * 排序菜单：与「图库」页一致的 [Icons.AutoMirrored.Filled.Sort] 图标 + 下拉项，
 * 选项就是 [SortOrder.entries]，文案直接取 [SortOrder.label]。
 *
 * 文件夹的三个页面（目录树 / 网格 / 详情）共用这一个组件，避免各写一份之后三处行为不一致。
 * [activeSort] 只用来打勾：用户还没在本页选过时，它就是图库当前的排序。
 */
@Composable
internal fun FolderSortMenu(
    activeSort: SortOrder,
    onSelect: (SortOrder) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(imageVector = Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            SortOrder.entries.forEach { order ->
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

/**
 * 页面级「三点菜单」：排序 + （可选）「包含子文件夹」开关。
 *
 * 为什么把开关放这里：文件夹操作弹层（FolderActionsSheet）本身已经是一个 ModalBottomSheet，
 * 不能再往里嵌第二个弹层，所以网格页把「包含子文件夹」放在自己的页面级菜单里。
 * 列表模式传 includeSubfolders = null 就不显示这一行。
 */
@Composable
internal fun FolderPageMenu(
    activeSort: SortOrder,
    onSortSelected: (SortOrder) -> Unit,
    includeSubfolders: Boolean?,
    onToggleIncludeSubfolders: (Boolean) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "更多")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Text(
                text = "排序",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
            )
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label) },
                    trailingIcon = {
                        if (order == activeSort) {
                            Icon(imageVector = Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        menuOpen = false
                        onSortSelected(order)
                    },
                )
            }
            if (includeSubfolders != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("包含子文件夹")
                            Text(
                                text = if (includeSubfolders) {
                                    "打开文件夹时显示它和所有子文件夹的照片"
                                } else {
                                    "打开文件夹时只看这一层的照片"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = {
                        Switch(checked = includeSubfolders, onCheckedChange = null)
                    },
                    // 菜单不收起，用户能立刻看到开关状态和说明的变化。
                    onClick = { onToggleIncludeSubfolders(!includeSubfolders) },
                )
            }
        }
    }
}

/** 枚举名 ↔ [SortOrder]：rememberSaveable 只能存基本类型，所以排序按枚举名存盘。 */
internal fun sortOrderFromName(name: String?): SortOrder? =
    name?.let { value -> SortOrder.entries.firstOrNull { it.name == value } }

/** 置顶目录集合：settings 里存的是不带结尾斜杠的相对路径，比较时统一小写。 */
internal fun pinnedPathSet(pinnedFolders: List<String>): Set<String> =
    pinnedFolders.mapTo(HashSet()) { it.trim('/').lowercase() }

/**
 * 置顶的目录排在同层最前面：置顶组按名字排，其余保持树原本的顺序（名字升序）。
 * 目录树摊平与网格卡片共用这一种顺序，切布局时顺序不会变。
 */
internal fun orderPinnedFolders(
    nodes: List<FolderNode>,
    pinnedPaths: Set<String>,
): List<FolderNode> {
    if (nodes.isEmpty() || pinnedPaths.isEmpty()) return nodes
    val pinned = ArrayList<FolderNode>(nodes.size)
    val rest = ArrayList<FolderNode>(nodes.size)
    for (node in nodes) {
        if (node.path.trim('/').lowercase() in pinnedPaths) pinned += node else rest += node
    }
    if (pinned.isEmpty()) return nodes
    pinned.sortBy { it.name.lowercase() }
    return pinned + rest
}

/** 用户给某个目录设置的封面图 id（settings 里的 key 不带结尾斜杠）。 */
internal fun folderCoverIdOf(folderCovers: Map<String, Long>, path: String): Long? {
    if (folderCovers.isEmpty()) return null
    val key = path.trim('/')
    folderCovers[key]?.let { return it }
    return folderCovers.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
}

/**
 * 多选路径集合的保存器（v0.8）。
 *
 * Bundle 只接受 ArrayList<String> 这类可序列化容器，默认的自动保存器存不了 List<String>，
 * 所以显式转一次：转屏、切 Tab 回来时，列表页 / 网格页的选择都还在。
 */
private val folderSelectionSaver = Saver<MutableState<List<String>>, ArrayList<String>>(
    save = { state -> ArrayList(state.value) },
    restore = { saved -> mutableStateOf(saved.toList()) },
)
