package io.github.kurumi33.eropic.ui.folders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import io.github.kurumi33.eropic.core.model.FolderRow
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount

/**
 * 「文件夹」页：按真实目录树展示相册，支持逐级展开/收起，点按进入文件夹详情。
 *
 * 树形列表用扁平化实现：把树按展开状态摊平成 List<FolderNode> 再交给 LazyColumn，
 * 这样长列表也能保持懒加载。
 */
@Composable
fun FoldersSection(
    viewModel: EropicViewModel,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
    hasWriteAccess: Boolean = false,
) {
    // 展开状态以 node.path 为 key；默认全部收起，点箭头才展开。
    // 用可保存的 List<String> 而不是 mutableStateMapOf：后者旋转屏幕就全丢了。
    var expandedPaths by rememberSaveable { mutableStateOf(listOf<String>()) }
    val expanded = remember(expandedPaths) { expandedPaths.associateWith { true } }
    var selectedPath by rememberSaveable { mutableStateOf<String?>(null) }
    // 索引/后台同步完成后要重新查一次目录聚合，否则停在文件夹页时张数永远不更新。
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
    // 目录可能随索引刷新而消失：按 path 从当前树里重新查节点，查不到就回到列表。
    val selectedNode = remember(tree, selectedPath) {
        selectedPath?.let { path -> findFolderNode(tree, path) }
    }

    val node = selectedNode
    if (node != null) {
        FolderDetailScreen(
            viewModel = viewModel,
            folderPath = node.path,
            folderName = node.name,
            onBack = { selectedPath = null },
            onOpenViewer = onOpenViewer,
            hasWriteAccess = hasWriteAccess,
        )
    } else {
        FolderTreeContent(
            tree = tree,
            showMeta = settings.showMeta,
            expanded = expanded,
            onToggleExpand = { path ->
                expandedPaths = if (path in expandedPaths) expandedPaths - path else expandedPaths + path
            },
            onOpenFolder = { path -> selectedPath = path },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderTreeContent(
    tree: List<FolderNode>,
    showMeta: Boolean,
    expanded: Map<String, Boolean>,
    onToggleExpand: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    // 按展开状态把树摊平成可见列表；读到 expanded 的 snapshot 值，展开/收起会自动重组。
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
        )

        if (tree.isEmpty()) {
            EmptyFolders()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(items = visibleNodes, key = { it.path }) { node ->
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
}

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
            .padding(start = (node.depth * 16).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 叶子节点也占住箭头的位置，保证同级目录的图标纵向对齐。
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
            imageVector = if (node.path.isEmpty()) Icons.Filled.PhotoLibrary else Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
        ) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            if (showMeta) {
                Text(
                    text = buildString {
                        append(formatCount(node.totalCount))
                        append(" 张 · ")
                        append(formatBytes(node.totalBytes))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun EmptyFolders() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "还没有索引", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "还没有索引，请先到图库页建立索引。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** 在树里按 path 找节点；找不到返回 null。 */
private fun findFolderNode(nodes: List<FolderNode>, path: String): FolderNode? {
    for (node in nodes) {
        if (node.path == path) return node
        val found = findFolderNode(node.children, path)
        if (found != null) return found
    }
    return null
}
