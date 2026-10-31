package io.github.kurumi33.eropic.ui.history

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
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kurumi33.eropic.core.fileops.OperationRecord
import io.github.kurumi33.eropic.ui.AppScreen
import io.github.kurumi33.eropic.ui.EropicViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 操作历史（对应规划 C-08）。
 *
 * 只读日志，能做的是"撤销"：把移动/重命名搬回原位置、把复制出来的新文件移入回收站、
 * 把移入回收站的文件恢复。永久删除、清空回收站没有撤销的可能，这里会说明原因。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationHistoryScreen(
    viewModel: EropicViewModel,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val records by viewModel.operationRecords.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var undoBusyId by remember { mutableStateOf<Long?>(null) }

    /** 撤销一条：结果统一走报告页，失败明细与成功数都看得到。 */
    fun undo(record: OperationRecord) {
        if (undoBusyId != null) return
        undoBusyId = record.id
        scope.launch {
            try {
                val report = viewModel.undoOperation(record.id)
                // 只走屏幕路由：同时写 lastReport 会让关掉结果页后它又被顶回来。
                viewModel.openScreen(AppScreen.Report(report))
            } catch (t: Throwable) {
                onMessage("撤销失败：" + (t.message ?: t::class.simpleName))
            } finally {
                undoBusyId = null
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("操作历史") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                    )
                }
            },
        )

        Text(
            text = "移动、重命名、复制、移入回收站都可以撤销；永久删除与清空回收站不可撤销。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (records.isEmpty()) {
            EmptyHistoryState(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(records, key = { it.id }) { record ->
                    RecordCard(
                        record = record,
                        busy = undoBusyId == record.id,
                        enabled = undoBusyId == null,
                        onUndo = { undo(record) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordCard(
    record: OperationRecord,
    busy: Boolean,
    enabled: Boolean,
    onUndo: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatRecordTime(record.createdAtMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = record.summary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "涉及 " + record.itemCount + " 项",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    record.undone -> {
                        Text(
                            text = "已撤销",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    record.canUndo -> {
                        Text(
                            text = "可撤销",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onUndo, enabled = enabled) {
                            Text(if (busy) "撤销中…" else "撤销")
                        }
                    }

                    else -> {
                        // 不可撤销时把原因写清楚（旧版本记录 / 永久删除等），而不是给个灰按钮。
                        Text(
                            text = record.undoHint.ifBlank { "无法撤销" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = "还没有操作记录",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "移动、复制、重命名、移入回收站之后，可以在这里撤销。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun formatRecordTime(ms: Long): String {
    if (ms <= 0L) return "时间未知"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}
