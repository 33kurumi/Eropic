package io.github.kurumi33.eropic.ui.report

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kurumi33.eropic.core.fileops.FileOpReport
import io.github.kurumi33.eropic.core.fileops.OpFailure
import io.github.kurumi33.eropic.ui.common.formatBytes

/**
 * 写操作结果报告页（对应规划 C-07）。
 *
 * 不再只用一句 Snackbar 说"完成 N 项"：用户需要看到成功/失败/取消的数量、
 * 处理了多少字节，以及每一个失败文件的失败原因。底部固定一个入口去操作历史，
 * 撤销刚才那一步。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    report: FileOpReport,
    onBack: () -> Unit,
    onViewHistory: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("操作结果") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                    )
                }
            },
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { OverviewCard(report) }
            item { StatisticsCard(report) }

            if (report.failures.isEmpty()) {
                item {
                    Text(
                        text = if (report.failed == 0) {
                            "全部处理完成，没有失败项。"
                        } else {
                            "没有可显示的失败明细。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    Text(
                        text = "失败明细（" + report.failures.size + "）",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(report.failures) { failure -> FailureRow(failure) }
            }
        }

        Button(
            onClick = onViewHistory,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Text("查看操作历史")
        }
    }
}

/** 概览：标题 + 一句话结果。 */
@Composable
private fun OverviewCard(report: FileOpReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = report.title, style = MaterialTheme.typography.titleMedium)
            Text(text = report.message, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "共请求 " + report.requested + " 项 · 类型 " + report.type,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 统计卡：成功 / 失败 / 取消 / 处理字节。 */
@Composable
private fun StatisticsCard(report: FileOpReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "统计", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatCell(
                    label = "成功",
                    value = report.succeeded.toString(),
                    modifier = Modifier.weight(1f),
                    valueColor = MaterialTheme.colorScheme.primary,
                )
                StatCell(
                    label = "失败",
                    value = report.failed.toString(),
                    modifier = Modifier.weight(1f),
                    valueColor = if (report.failed > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatCell(
                    label = "取消",
                    value = if (report.cancelled) "是" else "否",
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    label = "处理字节",
                    value = formatBytes(report.bytes),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
) {
    Column(
        modifier = modifier.padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一条失败记录：文件名 + 具体原因（不截断原因，用户要能照着排查）。 */
@Composable
private fun FailureRow(failure: OpFailure) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = failure.name.ifBlank { "未知文件" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = failure.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
