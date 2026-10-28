package io.github.kurumi33.eropic.ui.folders

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 *   整行点击进入该目录的照片，行尾 ⋮ 打开文件夹操作弹层，行内右侧箭头展开/收起子目录。
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
    // 正在看图（详情页）的目录；null = 没在看图。列表与网格共用这一份状态。
    var viewingPath by rememberSaveable { mutableStateOf<String?>(null) }
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

    // 目录被删 / 改名后，保存下来的浏览位置可能已经不存在：退回去，避免白屏。
    LaunchedEffect(tree, gridPath) {
        if (gridPath.isNotEmpty() && tree.isNotEmpty() && findFolderNode(tree, gridPath) == null) {
            gridPath = ""
        }
    }
    LaunchedEffect(tree, viewingPath) {
        val path = viewingPath
        if (path != null && path.isNotEmpty() && tree.isNotEmpty() && findFolderNode(tree, path) == null) {
            viewingPath = null
            viewingNeedsSelection = false
        }
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
            FolderDetailScreen(
                viewModel = viewModel,
                folderPath = path,
                folderName = folderNameOf(tree, path),
                onBack = {
                    viewingPath = null
                    viewingNeedsSelection = false
                },
                onOpenViewer = onOpenViewer,
                hasWriteAccess = hasWriteAccess,
                initialIncludeSubfolders = includeSubfolders && path.isNotEmpty(),
                initialSort = sortOverride,
                initialSelectionActive = viewingNeedsSelection,
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
                viewingPath = path
                viewingNeedsSelection = false
            },
            onShowActions = { node -> actionsTarget = node },
            onSwitchToGrid = { gridLayout = true },
            activeSort = activeSort,
            onSortSelected = { order -> sortName = order.name },
        )
    }

    // ---- 文件夹操作弹层：网格长按 / 列表行尾 ⋮ 打开 ----
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
                viewingPath = node.path
                viewingNeedsSelection = false
            },
            onRename = { renameTarget = node },
            onChangeCover = { viewModel.openScreen(AppScreen.FolderCover(relativePath)) },
            onTogglePin = { viewModel.setFolderPinned(relativePath, !pinned) },
            onDelete = { deleteTarget = node },
            onSelect = {
                viewingPath = node.path
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
    activeSort: SortOrder,
    onSortSelected: (SortOrder) -> Unit,
) {
    // 把树按展开状态摊平成一行行：LazyColumn 只组合可见行，展开几千个目录也不卡。
    val visibleNodes = remember(tree, expanded, pinnedPaths) {
        flattenVisible(tree, expanded, pinnedPaths)
    }
    val totalBytes = remember(tree) { tree.sumOf { it.totalBytes } }

    Column(modifier = Modifier.fillMaxSize()) {
        EropicTopBar(
            title = "文件夹",
            subtitle = formatCount(tree.size.toLong()) +
                " 个文件夹 · 合计 " + formatBytes(totalBytes),
            actions = {
                FolderPageMenu(
                    activeSort = activeSort,
                    onSortSelected = onSortSelected,
                    // 列表模式本身就是整棵树，不需要「包含子文件夹」这个打开选项。
                    includeSubfolders = null,
                    onToggleIncludeSubfolders = {},
                )
                IconButton(onClick = onSwitchToGrid) {
                    Icon(
                        imageVector = Icons.Filled.GridView,
                        contentDescription = "切换为网格视图",
                    )
                }
            },
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = visibleNodes, key = { node -> node.path }) { node ->
                FolderRowItem(
                    node = node,
                    expanded = node.path in expanded,
                    showMeta = showMeta,
                    pinned = node.path.isNotEmpty() &&
                        node.path.trim('/').lowercase() in pinnedPaths,
                    onToggleExpand = { onToggleExpand(node.path) },
                    onClick = { onOpenFolder(node.path) },
                    onShowActions = { onShowActions(node) },
                )
            }
        }
    }
}

/** 目录树的一行：缩进按深度，左侧文件夹图标，右侧展开箭头 + 操作菜单，点击进入图片。 */
@Composable
private fun FolderRowItem(
    node: FolderNode,
    expanded: Boolean,
    showMeta: Boolean,
    pinned: Boolean,
    onToggleExpand: () -> Unit,
    onClick: () -> Unit,
    onShowActions: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (node.depth * 16).dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        // 展开/收起：有子目录才显示箭头；没有子目录也占同样宽的位置，避免行尾按钮左右跳动。
        if (node.children.isNotEmpty()) {
            IconButton(onClick = onToggleExpand) {
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                    contentDescription = if (expanded) "收起" else "展开",
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        // 根目录是合成出来的伪节点，没有改名 / 删除 / 置顶的意义，不给菜单入口。
        if (node.path.isNotEmpty()) {
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
