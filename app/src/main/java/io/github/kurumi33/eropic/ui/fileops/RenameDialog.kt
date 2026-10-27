package io.github.kurumi33.eropic.ui.fileops

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kurumi33.eropic.core.fileops.FileOpReport
import io.github.kurumi33.eropic.core.fileops.RenamePreview
import io.github.kurumi33.eropic.core.fileops.RenameTemplate
import io.github.kurumi33.eropic.ui.EropicViewModel
import kotlinx.coroutines.launch

/**
 * 单张重命名对话框（对应规划 C-03）。
 *
 * 只改文件名、不换目录；输入时实时用 [RenameTemplate.validateName] 校验，
 * 目标目录已有同名文件时 Repository 会自动加序号，绝不覆盖。
 */
@Composable
fun RenameDialog(
    viewModel: EropicViewModel,
    mediaId: Long,
    currentName: String,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onReport: (FileOpReport) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember(currentName) { mutableStateOf(currentName) }
    var busy by remember { mutableStateOf(false) }

    val trimmed = name.trim()
    val error = RenameTemplate.validateName(trimmed)
    val canSubmit = error == null && !busy && trimmed != currentName

    fun submit() {
        if (!canSubmit) return
        busy = true
        scope.launch {
            try {
                val report = viewModel.renameMedia(mediaId, trimmed)
                busy = false
                onDismiss()
                if (report.succeeded > 0) {
                    onReport(report)
                } else {
                    onMessage(report.renameFailureText())
                }
            } catch (t: Throwable) {
                busy = false
                onMessage("重命名失败：" + (t.message ?: t::class.simpleName))
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("重命名") },
        text = {
            Column {
                Text(
                    text = "只改文件名，不移动所在目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("新文件名") },
                    singleLine = true,
                    isError = error != null,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                    )
                }
                Text(
                    text = "当前：" + currentName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = canSubmit) {
                Text(if (busy) "重命名中…" else "确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

/**
 * 批量重命名对话框：按模板 + 当前顺序生成新文件名，先预览再执行。
 *
 * 模板占位符由 [RenameTemplate] 定义；预览只显示前 20 条（避免一次性渲染上千行），
 * 实际执行时会对全部选中项生效。
 */
@Composable
fun BatchRenameDialog(
    viewModel: EropicViewModel,
    ids: List<Long>,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onReport: (FileOpReport) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var template by remember { mutableStateOf(RenameTemplate.DEFAULT_BATCH) }
    var previews by remember { mutableStateOf<List<RenamePreview>?>(null) }
    var busy by remember { mutableStateOf(false) }

    val templateError = RenameTemplate.validateTemplate(template)
    val canRun = templateError == null && ids.isNotEmpty() && !busy

    fun preview() {
        if (!canRun) return
        busy = true
        scope.launch {
            try {
                previews = viewModel.renamePreview(ids, template)
            } catch (t: Throwable) {
                onMessage("预览失败：" + (t.message ?: t::class.simpleName))
                previews = null
            } finally {
                busy = false
            }
        }
    }

    fun submit() {
        if (!canRun) return
        busy = true
        scope.launch {
            try {
                val report = viewModel.renameBatch(ids, template)
                busy = false
                onDismiss()
                onReport(report)
            } catch (t: Throwable) {
                busy = false
                onMessage("批量重命名失败：" + (t.message ?: t::class.simpleName))
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("批量重命名") },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp)) {
                Text(
                    text = "共 " + ids.size + " 张图片，按当前列表顺序用模板生成新文件名。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = template,
                    onValueChange = {
                        template = it
                        // 模板变了，旧预览就作废，避免用户照着过期结果执行。
                        previews = null
                    },
                    label = { Text("文件名模板") },
                    singleLine = true,
                    isError = templateError != null,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                if (templateError != null) {
                    Text(
                        text = templateError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                    )
                }
                Text(
                    text = "占位符：{name} 原名、{ext} 扩展名、{n} 三位序号、" +
                        "{date} 拍摄日期、{time} 拍摄时间",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = { preview() }, enabled = canRun) {
                        Text(if (busy) "处理中…" else "预览")
                    }
                    Text(
                        text = "  只预览前 20 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val list = previews
                if (list != null) {
                    val conflicts = list.count { it.conflict }
                    Text(
                        text = "共 " + list.size + " 个新文件名" +
                            if (conflicts > 0) "，其中 $conflicts 个与现有文件重名" else "",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .padding(top = 4.dp),
                    ) {
                        items(list.take(20)) { row -> RenamePreviewRow(row) }
                    }
                    if (list.size > 20) {
                        Text(
                            text = "以上只显示前 20 条，其余在执行时同样按模板处理。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = canRun) {
                Text(if (busy) "处理中…" else "重命名")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

/** 预览一行：旧名 → 新名；重名标红并说明执行时会自动加序号。 */
@Composable
private fun RenamePreviewRow(preview: RenamePreview) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = preview.oldName + " → " + preview.newName,
            style = MaterialTheme.typography.bodySmall,
            color = if (preview.conflict) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (preview.conflict) {
            Text(
                text = "目标已存在，执行时会自动加序号",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 重命名失败时把第一条具体原因拼进提示，避免只看到"失败 1 项"。 */
private fun FileOpReport.renameFailureText(): String {
    val reason = failures.firstOrNull()?.reason
    return if (reason.isNullOrBlank()) message else message + "：" + reason
}
