package io.github.kurumi33.eropic.ui.cleanup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kurumi33.eropic.core.analysis.DuplicateScanner
import io.github.kurumi33.eropic.core.index.IndexPhase
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount

/** 报告里的一行：类别 / 数量 / 估算可释放字节。 */
private data class ReportRow(
    val label: String,
    val count: Long,
    val bytes: Long,
    val hint: String,
)

private data class CleanupReportData(
    val totalCount: Long,
    val signatureDone: Int,
    val signatureTotal: Int,
    val skipped: Int,
    val elapsedMs: Long,
    val rows: List<ReportRow>,
    val totalReclaimableBytes: Long,
)

/**
 * 清理报告（M4 A 部分）。
 *
 * 这里只做"体检汇总"：把各检测器的命中数量与可释放空间集中展示，
 * 不提供一键删除——每一步仍然要用户自己进入分类确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupReportScreen(
    viewModel: EropicViewModel,
    onBack: () -> Unit,
) {
    val indexState by viewModel.indexState.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()

    val data by produceState<CleanupReportData?>(initialValue = null, indexState.phase, groups.size) {
        value = runCatching {
            val exact = groups.filter { it.kind == DuplicateScanner.KIND_EXACT }
            val near = groups.filter { it.kind == DuplicateScanner.KIND_NEAR }
            val large = viewModel.largeFiles()
            val old = viewModel.oldPhotos()
            val screenshots = viewModel.screenshots()
            val blurry = viewModel.blurred()
            val bursts = viewModel.bursts()
            // v0.3：清理页的「小图」已换成「大图」（按分辨率），报告同步换口径；
            // 用 summary 而不是列表，避免被 find() 的 LIMIT 截断导致报告偏小。
            val largeImages = viewModel.largeImageSummary()

            val rows = listOf(
                ReportRow(
                    label = "精确重复",
                    count = exact.sumOf { it.memberCount.toLong() },
                    bytes = exact.sumOf { it.reclaimableBytes },
                    hint = "${exact.size} 组 · 内容完全一致（SHA-256）",
                ),
                ReportRow(
                    label = "相似图片",
                    count = near.sumOf { it.memberCount.toLong() },
                    bytes = near.sumOf { it.reclaimableBytes },
                    hint = "${near.size} 组 · dHash/pHash 距离阈值内，需逐组确认",
                ),
                ReportRow(
                    label = "大文件",
                    count = large.hitCount.toLong(),
                    bytes = large.reclaimableBytes,
                    hint = "单张 ≥ 5 MB",
                ),
                ReportRow(
                    label = "久远图片",
                    count = old.size.toLong(),
                    bytes = old.sumOf { it.sizeBytes },
                    hint = "拍摄时间超过 3 年且未收藏",
                ),
                ReportRow(
                    label = "疑似截图",
                    count = screenshots.size.toLong(),
                    bytes = screenshots.sumOf { it.sizeBytes },
                    hint = "按目录/分辨率/格式启发式判断，可能误判",
                ),
                ReportRow(
                    label = "模糊图片",
                    count = blurry.size.toLong(),
                    bytes = blurry.sumOf { it.sizeBytes },
                    hint = "Laplacian 方差 < 100（已完成指纹计算）",
                ),
                ReportRow(
                    label = "连拍",
                    count = bursts.hitCount.toLong(),
                    bytes = bursts.reclaimableBytes,
                    hint = "${bursts.groups.size} 组 · 同目录 5 秒内连续 3 张以上",
                ),
                ReportRow(
                    label = "大图",
                    count = largeImages.hits.toLong(),
                    bytes = largeImages.bytes,
                    hint = "分辨率 ≥ 800 万像素（按像素，不是体积）",
                ),
            )

            CleanupReportData(
                totalCount = viewModel.count.value,
                signatureDone = indexState.signatureDone,
                signatureTotal = indexState.signatureTotal,
                skipped = indexState.skippedCount,
                elapsedMs = indexState.elapsedMs,
                rows = rows,
                totalReclaimableBytes = rows.sumOf { it.bytes },
            )
        }.getOrNull()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("清理报告") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        val current = data
        if (current == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("索引概况", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "图库共 ${formatCount(current.totalCount)} 张图片；" +
                                "已完成指纹 ${current.signatureDone}/${current.signatureTotal}" +
                                if (current.skipped > 0) "，跳过 ${current.skipped} 张（无法解码）" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = when (indexState.phase) {
                                IndexPhase.DONE -> "上次索引耗时 ${formatDuration(current.elapsedMs)}"
                                IndexPhase.ERROR -> indexState.message
                                IndexPhase.IDLE -> "尚未运行索引"
                                else -> indexState.message.ifBlank { "索引进行中…" }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("估算可释放", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = formatBytes(current.totalReclaimableBytes),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = "注意：各类别之间有重叠（例如一张大图也可能是截图），实际可释放空间会小于各项之和。",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            items(current.rows.size) { index ->
                val row = current.rows[index]
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = row.label,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatBytes(row.bytes),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            text = "${formatCount(row.count)} 张 · ${row.hint}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (row.bytes > 0L) {
                            val fraction = if (current.totalReclaimableBytes > 0L) {
                                (row.bytes.toFloat() / current.totalReclaimableBytes).coerceIn(0f, 1f)
                            } else 0f
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            item {
                HorizontalDivider()
                Text(
                    text = "Eropic 不会自动删除任何图片。上面的数字只是候选统计，" +
                        "所有删除都要你在分类里逐组确认，且会先进回收站（默认 30 天可恢复）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
    }
}

/** 毫秒转"x 分 y 秒"，用于展示索引耗时。 */
private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "—"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes <= 0 -> "${seconds} 秒"
        minutes >= 60 -> "${minutes / 60} 小时 ${minutes % 60} 分"
        else -> "${minutes} 分 ${seconds} 秒"
    }
}
