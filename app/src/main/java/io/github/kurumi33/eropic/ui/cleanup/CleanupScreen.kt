package io.github.kurumi33.eropic.ui.cleanup

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.analysis.DuplicateScanner
import io.github.kurumi33.eropic.core.database.entity.DuplicateGroupEntity
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.index.IndexPhase
import io.github.kurumi33.eropic.core.index.IndexState
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupScreen(viewModel: EropicViewModel) {
    val indexState by viewModel.indexState.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("清理") })
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { IndexCard(indexState, onStart = viewModel::startIndex, onCancel = viewModel::cancelIndex) }
            item { ReadOnlyNotice() }
            if (groups.isEmpty()) {
                item {
                    Text(
                        text = if (indexState.phase == IndexPhase.DONE) {
                            "没有发现重复或相似图片。"
                        } else {
                            "索引完成后，这里会按「可释放空间」从大到小列出重复/相似分组。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            } else {
                items(groups, key = { it.groupId }) { group ->
                    GroupCard(group = group, loadMembers = viewModel::members)
                }
            }
        }
    }
}

@Composable
private fun IndexCard(indexState: IndexState, onStart: () -> Unit, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "索引与查重", style = MaterialTheme.typography.titleMedium)
            Text(
                text = when (indexState.phase) {
                    IndexPhase.IDLE -> "尚未运行。建立索引会读取系统相册元数据，并在后台计算图片指纹。"
                    IndexPhase.SYNCING -> "正在读取系统相册：${indexState.scanned} 条"
                    IndexPhase.HASHING -> "正在计算指纹：${indexState.signatureDone}/${indexState.signatureTotal}"
                    IndexPhase.DEDUPING -> indexState.message
                    IndexPhase.DONE -> "完成：精确重复 ${indexState.exactGroups} 组，相似 ${indexState.nearGroups} 组，可释放约 ${formatBytes(indexState.reclaimableBytes)}"
                    IndexPhase.ERROR -> indexState.message
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (indexState.isRunning) {
                val total = indexState.signatureTotal
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = {
                        if (indexState.phase == IndexPhase.HASHING && total > 0) {
                            (indexState.signatureDone.toFloat() / total).coerceIn(0f, 1f)
                        } else 0f
                    },
                )
                OutlinedButton(onClick = onCancel) { Text("取消") }
            } else {
                Button(onClick = onStart) { Text(if (indexState.phase == IndexPhase.DONE) "重新扫描" else "开始扫描") }
            }
        }
    }
}

@Composable
private fun ReadOnlyNotice() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = "M0 只读", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Eropic 只负责找出候选，不会自动删除或移动任何文件。" +
                    "删除、移动、回收站会在后续里程碑里，由你逐组确认后执行。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun GroupCard(group: DuplicateGroupEntity, loadMembers: suspend (Long) -> List<MediaEntity>) {
    val members by produceState(initialValue = emptyList<MediaEntity>(), group.groupId) {
        value = loadMembers(group.groupId)
    }
    val isExact = group.kind == DuplicateScanner.KIND_EXACT
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isExact) "精确重复" else "相似图片",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isExact) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = " · ${group.memberCount} 张 · 可释放 ${formatBytes(group.reclaimableBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!isExact) {
                Text(
                    text = "相似度参考：dHash 最大距离 ${group.maxDistance}/64",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(members, key = { it.id }) { media ->
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = MediaUris.of(media.id),
                            contentDescription = media.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {}, enabled = false) { Text("对比预览（M2）") }
                TextButton(onClick = {}, enabled = false) { Text("选择保留（M2）") }
            }
        }
    }
}
