package io.github.kurumi33.eropic.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kurumi33.eropic.core.fileops.BatchState

/**
 * 批量写任务的进度条（对应规划 C-06）。
 *
 * 批量移动/复制/重命名/删除共用同一个 [BatchState]：任务结束后 active 变 false，
 * 这里直接返回、不占任何布局空间。暂停是协作式的——当前文件会写完，下一个文件才停下，
 * 不会产生半截文件。
 */
@Composable
fun BatchProgressBar(
    state: BatchState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.active) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.summary,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (state.paused) {
                    Text(
                        text = "已暂停",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            LinearProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier.fillMaxWidth(),
            )

            // 当前正在处理的文件名；做了一行截断，避免长文件名把卡片撑高。
            Text(
                text = when {
                    state.cancelled -> "正在停止…"
                    state.paused -> "已暂停，点「继续」接着处理"
                    state.currentName.isNotBlank() -> state.currentName
                    else -> "准备中…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (state.paused) {
                    TextButton(onClick = onResume, enabled = !state.cancelled) { Text("继续") }
                } else {
                    TextButton(onClick = onPause, enabled = !state.cancelled) { Text("暂停") }
                }
                TextButton(onClick = onCancel, enabled = !state.cancelled) { Text("取消") }
            }
        }
    }
}
