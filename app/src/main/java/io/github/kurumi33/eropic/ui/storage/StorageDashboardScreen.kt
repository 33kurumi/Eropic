package io.github.kurumi33.eropic.ui.storage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kurumi33.eropic.core.model.StatRow
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.StorageOverview
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount
import java.util.Locale

/**
 * 存储仪表盘（M4 E-04）。
 *
 * 一次取齐「总览 / 按格式 / 按年份 / 按目录」四块聚合数据（[EropicViewModel.storageOverview]），
 * 全部按占用空间画占比条。这里只做只读展示：不做任何文件操作，也不会触发索引。
 * 目录行点击目前只给提示，后续可接上「跳到文件夹详情」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageDashboardScreen(
    viewModel: EropicViewModel,
    onBack: () -> Unit,
) {
    // 手动刷新：改 key 让 produceState 重新跑一次聚合查询。
    var refreshKey by remember { mutableIntStateOf(0) }
    val overview by produceState<StorageOverview?>(null, refreshKey) {
        value = runCatching { viewModel.storageOverview() }
            .onFailure {
                viewModel.postMessage("读取存储统计失败：" + (it.message ?: "未知错误"))
            }
            .getOrNull() ?: StorageOverview()
    }

    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                    )
                }
            },
            title = {
                Column {
                    Text("存储仪表盘")
                    Text(
                        text = "按格式 / 年份 / 目录统计占用",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                IconButton(onClick = { refreshKey++ }) {
                    Icon(imageVector = Icons.Filled.Refresh, contentDescription = "刷新统计")
                }
            },
        )

        val data = overview
        when {
            data == null -> StorageLoadingState()
            data.totalCount == 0L -> StorageEmptyState()
            else -> StorageContent(
                data = data,
                onFolderClick = { row ->
                    viewModel.postMessage(
                        "「" + row.key.trimEnd('/') + "」占用 " + formatBytes(row.totalBytes) +
                            "，后续版本支持从这里直接跳转到该目录",
                    )
                },
            )
        }
    }
}

/** 有数据时的主体：总览卡 + 三个聚合分区。 */
@Composable
private fun StorageContent(
    data: StorageOverview,
    onFolderClick: (StatRow) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OverviewCard(totalCount = data.totalCount, totalBytes = data.totalBytes)
        }
        item {
            StatSectionCard(
                title = "按格式",
                subtitle = "不同图片格式占用的空间",
                rows = data.formats,
                totalBytes = data.totalBytes,
                labelOf = { mimeType -> formatLabel(mimeType) },
            )
        }
        item {
            StatSectionCard(
                title = "按年份",
                subtitle = "按拍摄年份统计，缺失时用修改时间兜底",
                rows = data.years,
                totalBytes = data.totalBytes,
                labelOf = { year -> year + " 年" },
            )
        }
        item {
            StatSectionCard(
                title = "占用最大的目录",
                subtitle = "点击目录可查看占用提示",
                rows = data.folders,
                totalBytes = data.totalBytes,
                labelOf = { path -> path.trimEnd('/') },
                onRowClick = { row -> onFolderClick(row) },
            )
        }
    }
}

@Composable
private fun OverviewCard(totalCount: Long, totalBytes: Long) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "图片总览",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(text = formatCount(totalCount), style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = " 张",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                text = "共占用 " + formatBytes(totalBytes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            val average = if (totalCount > 0L) totalBytes / totalCount else 0L
            Text(
                text = "平均每张 " + formatBytes(average),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 一个聚合分区（按格式/年份/目录）：标题 + 若干占比条。 */
@Composable
private fun StatSectionCard(
    title: String,
    subtitle: String,
    rows: List<StatRow>,
    totalBytes: Long,
    labelOf: (String) -> String,
    onRowClick: ((StatRow) -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            if (rows.isEmpty()) {
                Text(
                    text = "暂无数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                rows.forEach { row ->
                    StatBarRow(
                        label = labelOf(row.key),
                        itemCount = row.itemCount,
                        totalBytes = row.totalBytes,
                        fraction = fractionOf(row.totalBytes, totalBytes),
                        onClick = onRowClick?.let { callback -> { callback(row) } },
                    )
                }
            }
        }
    }
}

/** 单行：名称 + 占用大小 + "N 张 · 百分比" + 占比条。 */
@Composable
private fun StatBarRow(
    label: String,
    itemCount: Long,
    totalBytes: Long,
    fraction: Float,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatBytes(totalBytes),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            text = formatCount(itemCount) + " 张 · " + percentText(fraction),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}

@Composable
private fun StorageLoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = "正在统计存储占用…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun StorageEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.PhotoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = "还没有可统计的图片",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "先去「图库」下拉刷新建立索引，再回来查看存储占用。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 占比：分母为 0 时给 0，避免 NaN。 */
private fun fractionOf(part: Long, total: Long): Float {
    if (total <= 0L) return 0f
    return (part.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
}

/** 百分比文案，例如 "12.3%"。 */
private fun percentText(fraction: Float): String =
    String.format(Locale.US, "%.1f%%", fraction * 100f)

/** MIME 转短名；未知格式退回到子类型大写（image/x-foo → X-FOO）。 */
private fun formatLabel(mimeType: String): String {
    val normalized = mimeType.lowercase(Locale.US)
    return when (normalized) {
        "image/jpeg", "image/jpg" -> "JPG"
        "image/png" -> "PNG"
        "image/webp" -> "WEBP"
        "image/gif" -> "GIF"
        "image/heic", "image/heif" -> "HEIC"
        "image/bmp", "image/x-ms-bmp" -> "BMP"
        "image/svg+xml" -> "SVG"
        "image/avif" -> "AVIF"
        else -> normalized.substringAfter('/', normalized).uppercase(Locale.US)
    }
}
