package io.github.kurumi33.eropic.ui.cleanup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.kurumi33.eropic.core.cleanup.KeepCandidate
import io.github.kurumi33.eropic.core.cleanup.KeepStrategy
import io.github.kurumi33.eropic.core.cleanup.KeepSuggester
import io.github.kurumi33.eropic.core.cleanup.KeepSuggestion
import io.github.kurumi33.eropic.ui.common.formatBytes

/**
 * 选择保留策略的确认对话框。
 *
 * 只生成「保留 1 张、其余移入回收站」的建议，不会直接删除任何文件；
 * 真正的移动由调用方在用户确认后执行。
 */
@Composable
fun KeepStrategyDialog(
    candidates: List<KeepCandidate>,
    defaultStrategy: KeepStrategy = KeepStrategy.LARGEST,
    onDismiss: () -> Unit,
    onApply: (KeepSuggestion) -> Unit,
) {
    // 策略不是 Parcelable 状态，用普通 remember；切换策略时立即重算预览。
    var strategy by remember { mutableStateOf(defaultStrategy) }
    val suggestion = remember(candidates, strategy) {
        KeepSuggester.suggest(candidates, strategy)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择保留哪一张") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Eropic 不会自动删除：确认后只会移入回收站，30 天内可恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 单选策略列表：整行可点，配合 selectableGroup 暴露给无障碍服务。
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    KeepStrategy.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = strategy == option,
                                    onClick = { strategy = option },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = strategy == option, onClick = null)
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                // 实时预览：按当前策略算出的保留 / 移入回收站 / 可释放空间。
                Text(
                    text = "将保留 ${suggestion.keepIds.size} 张 · " +
                        "移入回收站 ${suggestion.trashIds.size} 张 · " +
                        "可释放 ${formatBytes(suggestion.reclaimableBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (strategy == KeepStrategy.NONE) {
                    Text(
                        text = "选择一种策略后才能生成建议。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(suggestion) },
                enabled = suggestion.keepIds.isNotEmpty(),
            ) { Text("按此建议移入回收站") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
