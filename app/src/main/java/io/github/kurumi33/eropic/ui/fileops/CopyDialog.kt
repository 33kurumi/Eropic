package io.github.kurumi33.eropic.ui.fileops

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kurumi33.eropic.core.fileops.FileOpReport
import io.github.kurumi33.eropic.core.media.StoragePaths
import io.github.kurumi33.eropic.ui.EropicViewModel
import kotlinx.coroutines.launch

/** 单个相对路径片段里不能出现的字符：它们要么是分隔符，要么在 Windows 侧是非法字符。 */
private const val ILLEGAL_PATH_CHARS = "\\:*?\"<>|"

/**
 * "复制到文件夹"对话框（对应规划 C-02）。
 *
 * 与移动不同，复制**保留原图**：目标目录里会生成新文件，两份各占一份空间，
 * 所以这里先明确提示，避免用户以为是在移动。目标目录用相对存储根的路径表示
 * （如 "Pictures/Copy"），可选已有目录，也可新建。
 *
 * @param onMessage 一次性提示，只用于异常与失败明细。
 * @param onReport  操作完成后交给上层展示结果报告（成功/部分失败都会走这里）。
 */
@Composable
fun CopyDialog(
    viewModel: EropicViewModel,
    mediaIds: List<Long>,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onReport: (FileOpReport) -> Unit,
) {
    val scope = rememberCoroutineScope()

    // 已有目录异步取：null = 加载中，空列表 = 没有可用目录（只能新建）。
    val folders by produceState<List<String>?>(initialValue = null) {
        value = runCatching { viewModel.copyTargetFolders() }
            .getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotBlank() && !isTrashRelativePath(it) }
            .distinct()
    }

    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var createNew by remember { mutableStateOf(false) }
    var customPath by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // 目标 = 选中的已有目录，或新建目录里输入的相对路径。
    val target = if (createNew) customPath.trim() else selectedFolder.orEmpty()
    val problem = targetProblem(target)
    val canSubmit = mediaIds.isNotEmpty() && target.isNotBlank() && problem == null && !busy
    val problemText = problem

    fun submit() {
        if (!canSubmit) return
        val path = target
        busy = true
        scope.launch {
            try {
                val report = viewModel.copyMedia(mediaIds, path)
                onDismiss()
                onReport(report)
            } catch (t: Throwable) {
                onMessage("复制失败：" + (t.message ?: t::class.simpleName))
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("复制到文件夹") },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp)) {
                Text(
                    text = "复制会保留原图，在目标目录生成新文件（会多占空间）：" +
                        "同一张图片会有两份，系统相册里也会出现两张。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (mediaIds.isEmpty()) {
                        "没有选中任何图片"
                    } else {
                        "共 " + mediaIds.size + " 张图片"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )

                when (val list = folders) {
                    null -> Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }

                    else -> {
                        if (list.isEmpty()) {
                            Text(
                                text = "还没有可选的已有目录，直接在下面输入新目录名。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .padding(top = 4.dp),
                            ) {
                                items(list, key = { it }) { folder ->
                                    CopyFolderRow(
                                        label = displayFolder(folder),
                                        selected = !createNew && selectedFolder == folder,
                                        onClick = {
                                            createNew = false
                                            selectedFolder = folder
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                CopyFolderRow(
                    label = "新建目录…",
                    selected = createNew,
                    onClick = { createNew = true },
                )

                if (createNew) {
                    OutlinedTextField(
                        value = customPath,
                        onValueChange = { customPath = it },
                        label = { Text("相对路径") },
                        placeholder = { Text("例如 Pictures/Copy") },
                        singleLine = true,
                        isError = problemText != null,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    // 错误原因单独一行显示，比 supportingText 更好控制、也不会把输入框撑高。
                    if (problemText != null) {
                        Text(
                            text = problemText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = canSubmit) {
                Text(if (busy) "复制中…" else "复制")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

/** 单行目录选择：整行可点，不必去点那个小圆点。 */
@Composable
private fun CopyFolderRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}

/** 展示时去掉末尾斜杠，"Pictures/" 显示成 "Pictures"。 */
private fun displayFolder(folder: String): String = folder.trimEnd('/').ifBlank { folder }

/** 回收站目录不能作为复制目标（那只是把垃圾复制一份）。 */
private fun isTrashRelativePath(path: String): Boolean {
    val head = path.trimStart('/').trimEnd('/').substringBefore('/')
    return head.equals(StoragePaths.TRASH_DIR_NAME, ignoreCase = true)
}

/**
 * 校验目标相对路径，返回给用户的错误文案；null 表示合法。
 *
 * 规则：不能是绝对路径、不能含 "." / ".." 片段、不能有非法字符，且不能指向回收站目录。
 */
private fun targetProblem(target: String): String? {
    if (target.isBlank()) return null
    if (target.startsWith("/")) return "请输入相对路径，不要以 / 开头"
    if (target.any { it in ILLEGAL_PATH_CHARS }) return "路径里不能包含这些字符：\\ : * ? \" < > |"
    val parts = target.split('/').filter { it.isNotBlank() }
    if (parts.isEmpty()) return "请输入目录名"
    if (parts.any { it == "." || it == ".." }) return "路径里不能包含 . 或 .."
    if (isTrashRelativePath(target)) return "不能复制到回收站目录"
    return null
}
