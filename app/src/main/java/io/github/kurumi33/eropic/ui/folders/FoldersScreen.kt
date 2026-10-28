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
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kurumi33.eropic.core.folders.FolderNode
import io.github.kurumi33.eropic.core.folders.FolderTreeBuilder
import io.github.kurumi33.eropic.core.folders.findFolderNode
import io.github.kurumi33.eropic.core.folders.parentFolderPath
import io.github.kurumi33.eropic.core.folders.splitFolderPath
import io.github.kurumi33.eropic.core.media.SortOrder
import io.github.kurumi33.eropic.core.model.FolderRow
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount

/**
 * 「文件夹」页：列表（目录树）与网格（文件夹卡片）两种浏览方式。
 *
 * - 列表模式：把树按展开状态摊平成 List<FolderNode> 交给 LazyColumn，长列表也能懒加载；
 * - 网格模式：只展示当前这一层的子文件夹卡片（封面 + 名字 + 张数 + 大小），可以逐级下钻，
 *   顶部有返回上一级、面包屑，以及「查看本层全部图片」和「包含子文件夹」两个入口。
 *
 * 两种模式的浏览位置都用 rememberSaveable 保存，转屏、切 Tab 都不会丢。
 */
@Composable
fun FoldersSection(
    viewModel: EropicViewModel,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
    hasWriteAccess: Boolean = false,
) {
    // 展开状态以 node.path 为 key；默认全部收起，点箭头才展开。
    // 用可保存的 List<String> 而不是 mutableStateMapOf：后者一旋转屏幕就全丢了。
    var expandedPaths by rememberSaveable { mutableStateOf(listOf<String>()) }
    val expanded = remember(expandedPaths) { expandedPaths.associateWith { true } }
    var selectedPath by rememberSaveable { mutableStateOf<String?>(null) }

    // 网格模式的状态：是否网格 / 当前层级（"" = 根层级）/ 是否聚合子目录 / 是否在看图片。
    var gridLayout by rememberSaveable { mutableStateOf(false) }
    var gridPath by rememberSaveable { mutableStateOf("") }
    var gridIncludeSubfolders by rememberSaveable { mutableStateOf(false) }
    var gridShowImages by rememberSaveable { mutableStateOf(false) }

    // 索引或后台同步结束后要重新查一次目录聚合，否则停在文件夹页时张数永远不更新。
    val indexState by viewModel.indexState.collectAsStateWithLifecycle()
    val rows by produceState(
        initialValue = emptyList<FolderRow>(),
        indexState.phase,
        indexState.finishedAtMs,
    ) {
        value = viewModel.loadFolderRows()
    }
    val tree = remember(rows) { FolderTreeBuilder.build(rows) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // 排序：默认跟随图库的当前排序（sortOverride = null），用户在本页选过之后就固定下来。
    // 枚举不是 Saveable，按枚举名存盘；列表页与网格页共用这一份，切来切去也不会丢。
    var sortName by rememberSaveable { mutableStateOf<String?>(null) }
    val sortOverride = remember(sortName) { sortOrderFromName(sortName) }
    val gallerySort by viewModel.sortOrder.collectAsStateWithLifecycle()
    val activeSort = sortOverride ?: gallerySort

    // 列表模式：目录可能随索引刷新而消失，查不到就退回根列表。
    val selectedNode = remember(tree, selectedPath) {
        selectedPath?.let { path -> findFolderNode(tree, path) }
    }
    // 网格模式同理：当前层级不存在了（目录被删、改名或索引重置）就退回根层级。
    LaunchedEffect(tree, gridPath) {
        if (gridPath.isNotEmpty() && tree.isNotEmpty() && findFolderNode(tree, gridPath) == null) {
            gridPath = ""
            gridShowImages = false
        }
    }

    when {
        // 还没有索引：给提示，同时保留布局切换入口，避免空库里什么都点不了。
        tree.isEmpty() -> EmptyFoldersScaffold(
            gridLayout = gridLayout,
            onToggleLayout = { gridLayout = !gridLayout },
        )

        !gridLayout && selectedNode != null -> FolderDetailScreen(
            viewModel = viewModel,
            folderPath = selectedNode.path,
            folderName = selectedNode.name,
            onBack = { selectedPath = null },
            onOpenViewer = onOpenViewer,
            hasWriteAccess = hasWriteAccess,
            initialSort = sortOverride,
        )

        !gridLayout -> FolderTreeContent(
            tree = tree,
            showMeta = settings.showMeta,
            expanded = expanded,
            onToggleExpand = { path ->
                expandedPaths = if (path in expandedPaths) expandedPaths - path else expandedPaths + path
            },
            onOpenFolder = { path -> selectedPath = path },
            onSwitchToGrid = {
                gridLayout = true
                gridShowImages = false
            },
            activeSort = activeSort,
            onSortSelected = { order -> sortName = order.name },
        )

        // 网格里点「查看本层全部图片」：复用详情页的大网格（含多选、移动、回收站）。
        gridShowImages -> FolderDetailScreen(
            viewModel = viewModel,
            folderPath = gridPath,
            folderName = folderNameOf(tree, gridPath),
            onBack = { gridShowImages = false },
            onOpenViewer = onOpenViewer,
            hasWriteAccess = hasWriteAccess,
            initialIncludeSubfolders = gridIncludeSubfolders,
            initialSort = sortOverride,
        )

        else -> FolderGridScreen(
            viewModel = viewModel,
            tree = tree,
            path = gridPath,
            includeSubfolders = gridIncludeSubfolders,
            refreshToken = indexState.finishedAtMs,
            sortOverride = sortOverride,
            onSortSelected = { order -> sortName = order.name },
            onToggleIncludeSubfolders = { checked -> gridIncludeSubfolders = checked },
            onOpenFolder = { path -> gridPath = path },
            onUp = { parentFolderPath(gridPath)?.let { parent -> gridPath = parent } },
            onOpenImages = { gridShowImages = true },
            onSwitchToList = {
                gridLayout = false
                gridShowImages = false
            },
            onOpenViewer = onOpenViewer,
        )
    }
}

/** 列表模式：目录树 + 顶栏的布局切换按钮。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderTreeContent(
    tree: List<FolderNode>,
    showMeta: Boolean,
    expanded: Map<String, Boolean>,
    onToggleExpand: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onSwitchToGrid: () -> Unit,
    activeSort: SortOrder,
    onSortSelected: (SortOrder) -> Unit,
) {
    // 按展开状态把树摊平成"可见节点"列表：LazyColumn 只组合可见行，展开一万个节点也不卡。
    // 这里读到 expanded 的快照值，展开/收起会自动触发重组。
    val visibleNodes = ArrayList<FolderNode>(tree.size)
    fun appendVisible(nodes: List<FolderNode>) {
        for (node in nodes) {
            visibleNodes += node
            if (expanded[node.path] == true) appendVisible(node.children)
        }
    }
    appendVisible(tree)

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(text = "文件夹")
                    Text(
                        text = buildString {
                            append(formatCount(tree.size.toLong()))
                            append(" 个文件夹 · 合计 ")
                            append(formatBytes(tree.sumOf { it.totalBytes }))
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                FolderSortMenu(activeSort = activeSort, onSelect = onSortSelected)
                IconButton(onClick = onSwitchToGrid) {
                    Icon(imageVector = Icons.Filled.GridView, contentDescription = "切换为网格视图")
                }
            },
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = visibleNodes, key = { node -> node.path }) { node ->
                FolderRowItem(
                    node = node,
                    expanded = expanded[node.path] == true,
                    showMeta = showMeta,
                    onToggleExpand = { onToggleExpand(node.path) },
                    onClick = { onOpenFolder(node.path) },
                )
            }
        }
    }
}

/** 目录树的一行：缩进按深度，左侧文件夹图标，右侧展开箭头，点击进入详情。 */
@Composable
private fun FolderRowItem(
    node: FolderNode,
    expanded: Boolean,
    showMeta: Boolean,
    onToggleExpand: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (node.depth * 16).dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
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
            }
        }
        Icon(
            imageVector = if (node.children.isEmpty()) Icons.Filled.PhotoLibrary else Icons.Filled.Folder,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = node.name, style = MaterialTheme.typography.titleSmall)
            if (showMeta) {
                Text(
                    text = formatCount(node.totalCount) + " 张 · " + formatBytes(node.totalBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 完全没有索引时的页面：保留布局切换入口，内容只给建立索引的提示。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyFoldersScaffold(
    gridLayout: Boolean,
    onToggleLayout: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(text = "文件夹") },
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
private fun folderNameOf(tree: List<FolderNode>, path: String): String {
    if (path.isEmpty()) return "根目录"
    findFolderNode(tree, path)?.let { return it.name }
    return splitFolderPath(path).lastOrNull() ?: path
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

/** 枚举名 ↔ [SortOrder]：rememberSaveable 只能存基本类型，所以排序按枚举名存盘。 */
internal fun sortOrderFromName(name: String?): SortOrder? =
    name?.let { value -> SortOrder.entries.firstOrNull { it.name == value } }
