package io.github.kurumi33.eropic.ui.trash

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.database.entity.TrashItemEntity
import io.github.kurumi33.eropic.core.trash.TrashRepository
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount
import io.github.kurumi33.eropic.ui.components.ConfirmDialog
import kotlinx.coroutines.launch
import java.io.File

/** 需要二次确认的破坏性操作。 */
private enum class TrashConfirmAction { DELETE_SELECTED, EMPTY_ALL }

/**
 * 回收站页面（M3）。
 *
 * "删除"其实是把文件同卷 rename 到 /<卷根>/.EropicTrash/（瞬时、零拷贝），默认保留
 * [TrashRepository.RETENTION_DAYS] 天。本页负责查看、恢复、永久删除与清空；所有改动文件的
 * 操作都依赖"所有文件访问"权限（[hasWriteAccess]），未授权时按钮全部禁用，只让用户查看
 * 内容与到期时间，不会出现点了没反应的操作。
 *
 * @param onMessage 一次性提示（Snackbar 文案），由上层决定怎么显示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashSection(
    viewModel: EropicViewModel,
    hasWriteAccess: Boolean,
    onMessage: (String) -> Unit,
) {
    val trashItems by viewModel.trashItems.collectAsStateWithLifecycle()
    val trashCount by viewModel.trashCount.collectAsStateWithLifecycle()
    val trashTotalBytes by viewModel.trashTotalBytes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<TrashConfirmAction?>(null) }

    // 恢复/永久删除后条目会从 Flow 里消失，把已经不存在的 id 从选中集合剔掉，
    // 否则「恢复所选」会一直亮着，点了却什么也不做。
    LaunchedEffect(trashItems) {
        val alive = trashItems.map { it.id }.toSet()
        if (!alive.containsAll(selectedIds)) selectedIds = selectedIds intersect alive
    }

    /** 统一执行入口：防重复点击、把结果转成 Snackbar 文案、异常不崩界面。 */
    fun runTrashAction(label: String, action: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                onMessage(action())
                selectedIds = emptySet()
            } catch (t: Throwable) {
                onMessage(label + "失败：" + (t.message ?: t::class.simpleName))
            } finally {
                busy = false
            }
        }
    }

    val subtitle = formatCount(trashCount.toLong()) + " 项 · " + formatBytes(trashTotalBytes) +
        " · 默认保留 " + TrashRepository.RETENTION_DAYS + " 天"
    val selectionEmpty = selectedIds.isEmpty()
    val trashEmpty = trashItems.isEmpty()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("回收站") },
            actions = {
                // 操作进行中给一个小指示器：恢复大量文件可能要好几百毫秒。
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 16.dp).size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            },
        )

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        )

        // 没有"所有文件访问"时先提示，再把所有写按钮禁用——只有本页需要写盘。
        if (!hasWriteAccess) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = "未授予「所有文件访问」权限，无法操作文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // 四个操作按钮横排在顶部；窄屏可横向滚动，不会被挤成两行。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    val ids = trashItems.map { it.id }
                    runTrashAction("全部恢复") { viewModel.restoreFromTrash(ids).message }
                },
                enabled = hasWriteAccess && !busy && !trashEmpty,
            ) { Text("全部恢复") }

            OutlinedButton(
                onClick = {
                    val ids = selectedIds.toList()
                    runTrashAction("恢复所选") { viewModel.restoreFromTrash(ids).message }
                },
                enabled = hasWriteAccess && !busy && !selectionEmpty,
            ) { Text("恢复所选") }

            Button(
                onClick = { confirmAction = TrashConfirmAction.DELETE_SELECTED },
                enabled = hasWriteAccess && !busy && !selectionEmpty,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("永久删除所选") }

            OutlinedButton(
                onClick = { confirmAction = TrashConfirmAction.EMPTY_ALL },
                enabled = hasWriteAccess && !busy && !trashEmpty,
            ) { Text("清空回收站") }
        }

        if (!selectionEmpty) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已选 " + selectedIds.size + " 项",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { selectedIds = emptySet() }) { Text("取消选择") }
            }
        }

        HorizontalDivider()

        if (trashEmpty) {
            EmptyTrashState(modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(trashItems, key = { it.id }) { item ->
                    TrashRow(
                        item = item,
                        selected = item.id in selectedIds,
                        enabled = hasWriteAccess && !busy,
                        onClick = {
                            selectedIds = if (item.id in selectedIds) {
                                selectedIds - item.id
                            } else {
                                selectedIds + item.id
                            }
                        },
                    )
                }
            }
        }
    }

    // 永久删除与清空都必须二次确认：这两个操作真的不可恢复。
    confirmAction?.let { action ->
        when (action) {
            TrashConfirmAction.DELETE_SELECTED -> ConfirmDialog(
                title = "永久删除所选？",
                text = "将永久删除选中的 " + selectedIds.size + " 项回收站文件，永久删除后无法恢复。",
                confirmText = "永久删除",
                destructive = true,
                onConfirm = {
                    val ids = selectedIds.toList()
                    runTrashAction("永久删除") { viewModel.deleteFromTrashPermanently(ids).message }
                },
                onDismiss = { confirmAction = null },
            )

            TrashConfirmAction.EMPTY_ALL -> ConfirmDialog(
                title = "清空回收站？",
                text = "将永久删除回收站里的 " + formatCount(trashCount.toLong()) + " 项（" +
                    formatBytes(trashTotalBytes) + "），永久删除后无法恢复。",
                confirmText = "清空回收站",
                destructive = true,
                onConfirm = {
                    runTrashAction("清空回收站") { viewModel.emptyTrashNow().message }
                },
                onDismiss = { confirmAction = null },
            )
        }
    }
}

@Composable
private fun EmptyTrashState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.DeleteSweep,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = "回收站是空的",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "从图库选中图片删除后会先移到这里，" +
                TrashRepository.RETENTION_DAYS + " 天内可随时恢复",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * 单条回收站记录。
 *
 * 缩略图直接读回收站里的绝对路径（用 Uri.fromFile 交给 Coil），文件已被外部删除时
 * 只留一个图标占位，不显示破图。点击切换多选。
 */
@Composable
private fun TrashRow(
    item: TrashItemEntity,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // 到期文案只要一个"现在"，不必每帧读系统时钟。
    val nowMs = remember(item.id, item.expiresAtMs) { System.currentTimeMillis() }
    val exists = remember(item.trashPath) { File(item.trashPath).exists() }

    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (exists) {
                    AsyncImage(
                        model = Uri.fromFile(File(item.trashPath)),
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.originalPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatBytes(item.sizeBytes) + " · " + expiryText(item.expiresAtMs, nowMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = if (selected) "已选中" else "未选中",
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** 到期文案："还剩 N 天"（向上取整）；已过期则提示等待自动清理。 */
private fun expiryText(expiresAtMs: Long, nowMs: Long): String {
    val left = expiresAtMs - nowMs
    if (left <= 0L) return "已到期，等待自动清理"
    val days = ((left + DAY_MS - 1) / DAY_MS).toInt()
    return "还剩 " + days + " 天"
}

private const val DAY_MS = 24L * 60L * 60L * 1000L
