package io.github.kurumi33.eropic.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kurumi33.eropic.core.index.IndexPhase
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: EropicViewModel) {
    val context = LocalContext.current
    val count by viewModel.count.collectAsStateWithLifecycle()
    val totalSize by viewModel.totalSize.collectAsStateWithLifecycle()
    val indexState by viewModel.indexState.collectAsStateWithLifecycle()
    var showRebuildDialog by remember { mutableStateOf(false) }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.1.0"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("设置") })
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "本地索引", style = MaterialTheme.typography.titleMedium)
                    Text(text = "已索引 ${formatCount(count)} 张 · ${formatBytes(totalSize)}")
                    Text(
                        text = "任务状态：${indexState.phase.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (indexState.isRunning) {
                        OutlinedButton(onClick = viewModel::cancelIndex) { Text("取消当前任务") }
                    } else {
                        Button(onClick = viewModel::startIndex) { Text("增量扫描") }
                    }
                    OutlinedButton(onClick = { showRebuildDialog = true }) { Text("清空并重建索引") }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "M0 范围", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "本版本只做只读原型：相册浏览 + 指纹 + 精确/相似重复分组。" +
                            "回收站、真实移动、批量清理将在后续里程碑实现。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "关于", style = MaterialTheme.typography.titleMedium)
                    Text(text = "Eropic $versionName · 本地优先 · 无网络权限")
                    Text(
                        text = "开源地址：github.com/33kurumi/Eropic",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "没有删除按钮：所有会改动文件的操作都必须由你确认。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showRebuildDialog) {
        AlertDialog(
            onDismissRequest = { showRebuildDialog = false },
            title = { Text("清空并重建索引？") },
            text = {
                Text(
                    "会删除本地索引与查重结果并重新扫描。不会改动你手机里的任何照片。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRebuildDialog = false
                    viewModel.rebuildIndex()
                }) { Text("重建") }
            },
            dismissButton = {
                TextButton(onClick = { showRebuildDialog = false }) { Text("取消") }
            },
        )
    }
}
