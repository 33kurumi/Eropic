package io.github.kurumi33.eropic.ui.folders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kurumi33.eropic.core.fileops.EmptyFolderEntry
import io.github.kurumi33.eropic.core.fileops.FileOpReport
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.components.ConfirmDialog
import kotlinx.coroutines.launch

/**
 * 空文件夹管理（对应规划 C-04）。
 *
 * 只删"确认没有任何文件"的目录：扫描范围限定 Pictures / DCIM / Download / Documents，
 * 隐藏目录与 Android/ 一律视为非空；真正删除前 FolderOps 还会再检查一遍，目录里只要
 * 多出一个文件就跳过，避免把照片一起删掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderManagerScreen(
    viewModel: EropicViewModel,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    hasWriteAccess: Boolean = false,
) {
    val scope = rememberCoroutineScope()

    // 系统返回键回设置页，不然会直接退出 App。
    androidx.activity.compose.BackHandler(onBack = onBack)

    // null = 扫描中，空列表 = 确认没有空文件夹。refreshKey 变化时重新扫描。
    var refreshKey by remember { mutableIntStateOf(0) }
    val folders by produceState<List<EmptyFolderEntry>?>(null, refreshKey) {
        value = runCatching { viewModel.emptyFolders() }.getOrDefault(emptyList())
    }
    val loaded = folders

    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var newPath by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 重新扫描后把已经不存在的目录从选中集合里剔掉，避免"删除选中"删到空气。
    LaunchedEffect(loaded) {
        val alive = loaded?.map { it.absolutePath }?.toSet() ?: return@LaunchedEffect
        if (!alive.containsAll(selectedPaths)) selectedPaths = selectedPaths intersect alive
    }

    fun createFolder() {
        val path = newPath.trim()
        if (path.isEmpty() || busy) return
        busy = true
        scope.launch {
            try {
                val report = viewModel.createFolder(path)
                onMessage(report.describe())
                if (report.succeeded > 0) {
                    newPath = ""
                    refreshKey++
                }
            } catch (t: Throwable) {
                onMessage("新建文件夹失败：" + (t.message ?: t::class.simpleName))
            } finally {
                busy = false
            }
        }
    }

    fun deleteSelected() {
        if (busy) return
        val targets = loaded.orEmpty().filter { it.absolutePath in selectedPaths }
        if (targets.isEmpty()) return
        busy = true
        scope.launch {
            try {
                val report = viewModel.deleteEmptyFolders(targets.map { it.absolutePath })
                onMessage(report.describe())
                selectedPaths = selectedPaths - targets.map { it.absolutePath }.toSet()
                refreshKey++
            } catch (t: Throwable) {
                onMessage("删除空文件夹失败：" + (t.message ?: t::class.simpleName))
            } finally {
                busy = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("文件夹管理") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                    )
                }
            },
            actions = {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 16.dp).size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            },
        )

        Text(
            text = "只删除确认没有任何文件的目录：删除前会再扫描一遍，目录里只要有文件就跳过，" +
                "隐藏目录与 Android/ 一律视为非空。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (!hasWriteAccess) {
            // 整块可点：用户反馈过"别处需要授权时点那一处文字应能跳到该设置项"。
            Card(
                onClick = { viewModel.openScreen(io.github.kurumi33.eropic.ui.AppScreen.Permission) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(
                    text = "未授予「所有文件访问」权限：新建与删除目录已禁用。点这里去授权。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = "新建文件夹", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = newPath,
                    onValueChange = { newPath = it },
                    label = { Text("相对路径") },
                    placeholder = { Text("例如 Pictures/New") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "相对于内部存储根目录；目录已存在时不会覆盖。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { createFolder() },
                        enabled = newPath.isNotBlank() && !busy && hasWriteAccess,
                    ) { Text("新建") }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val count = loaded?.size ?: 0
            Text(
                text = if (selectedPaths.isEmpty()) {
                    "共 " + count + " 个空文件夹"
                } else {
                    "已选 " + selectedPaths.size + " / " + count + " 个"
                },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { selectedPaths = loaded.orEmpty().map { it.absolutePath }.toSet() },
                enabled = !loaded.isNullOrEmpty() && !busy,
            ) { Text("全选") }
            TextButton(
                onClick = { selectedPaths = emptySet() },
                enabled = selectedPaths.isNotEmpty() && !busy,
            ) { Text("取消选择") }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = { confirmDelete = true },
                enabled = selectedPaths.isNotEmpty() && !busy && hasWriteAccess,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("删除选中空文件夹") }
        }

        HorizontalDivider()

        when {
            loaded == null -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            loaded.isEmpty() -> EmptyFoldersState(modifier = Modifier.weight(1f))

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(loaded, key = { it.absolutePath }) { entry ->
                    FolderRow(
                        entry = entry,
                        selected = entry.absolutePath in selectedPaths,
                        enabled = !busy,
                        onToggle = {
                            selectedPaths = if (entry.absolutePath in selectedPaths) {
                                selectedPaths - entry.absolutePath
                            } else {
                                selectedPaths + entry.absolutePath
                            }
                        },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "删除空文件夹？",
            text = "将删除选中的 " + selectedPaths.size + " 个空文件夹。删除前会再检查一遍：" +
                "目录里只要还有文件就会跳过，不会删除照片。",
            confirmText = "删除",
            destructive = true,
            onConfirm = { deleteSelected() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun FolderRow(
    entry: EmptyFolderEntry,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Card(onClick = onToggle, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 整行可点，勾选框本身也响应；两者不会互相触发两次（勾选框会消费点击）。
            Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = enabled)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            ) {
                Text(
                    text = entry.relativePath.trimEnd('/'),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyFoldersState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = "没有发现空文件夹",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "Pictures、DCIM、Download、Documents 下的每个目录都至少有一个文件。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 报告里通常只给"失败 N 项"，这里补上第一条具体原因，方便用户处理。 */
private fun FileOpReport.describe(): String {
    val reason = failures.firstOrNull()?.reason
    return if (reason.isNullOrBlank()) message else message + "：" + reason
}
