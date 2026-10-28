package io.github.kurumi33.eropic.ui.folders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kurumi33.eropic.ui.EropicViewModel
import kotlinx.coroutines.launch

/**
 * 文件夹操作菜单（v0.5）。
 *
 * 为什么用底部弹层而不是下拉菜单：文件夹卡片上的三点太小，下拉菜单在网格里定位容易跑到屏幕外；
 * 底部弹层每行高度够大，顺手也把「打开 / 选择」这些需要解释的动作放进去。
 *
 * 写权限说明：重命名 / 更换封面 / 删除都要改真实文件，没有「所有文件访问」权限时一律置灰，
 * 而不是点了再弹失败提示——置灰 + 一行说明更省一次无意义的操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderActionsSheet(
    relativePath: String,
    displayName: String,
    pinned: Boolean,
    hasWriteAccess: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onChangeCover: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = displayName.ifBlank { relativePath.trimEnd('/').substringAfterLast('/') },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = relativePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(8.dp))
            HorizontalDivider()

            // 打开 / 选择：两个都是纯界面动作，不需要写权限。
            SheetActionRow(
                icon = Icons.Filled.Folder,
                label = "打开",
                subtitle = "进入这个文件夹看图",
                onClick = {
                    onOpen()
                    onDismiss()
                },
            )
            SheetActionRow(
                icon = Icons.Filled.Check,
                label = "选择",
                subtitle = "进入并直接进入多选模式",
                onClick = {
                    onSelect()
                    onDismiss()
                },
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            SheetActionRow(
                icon = Icons.Filled.Edit,
                label = "重命名…",
                enabled = hasWriteAccess,
                onClick = {
                    onRename()
                    onDismiss()
                },
            )
            SheetActionRow(
                icon = Icons.Filled.PhotoLibrary,
                label = "更换图集封面…",
                subtitle = "只影响图集页展示，不改动图片文件",
                enabled = hasWriteAccess,
                onClick = {
                    onChangeCover()
                    onDismiss()
                },
            )
            SheetActionRow(
                icon = Icons.Filled.Star,
                label = if (pinned) "取消置顶" else "置顶",
                subtitle = if (pinned) "取消后回到默认排序" else "置顶的文件夹排在图集最前面",
                onClick = {
                    onTogglePin()
                    onDismiss()
                },
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            SheetActionRow(
                icon = Icons.Filled.Delete,
                label = "删除（图片进回收站）",
                subtitle = "图片可在回收站保留 30 天后恢复；目录里的视频等未索引文件不会被删",
                enabled = hasWriteAccess,
                danger = true,
                onClick = {
                    onDelete()
                    onDismiss()
                },
            )

            if (!hasWriteAccess) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "未授予「所有文件访问」权限，暂时不能重命名、更换封面或删除",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 弹层里的一行动作。danger 用 errorContainer 底色 + onErrorContainer 文字；
 * enabled=false 统一压到 38% 透明度（Material 的禁用规范），并且点不动。
 */
@Composable
private fun SheetActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val shape = RoundedCornerShape(10.dp)
    // 危险动作（删除）用 errorContainer 底色区分；禁用时一律降到 38% 透明度的中性色
    val containerColor = if (danger && enabled) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
    } else {
        Color.Transparent
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        danger -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 文件夹名里不能出现的字符：和 Linux/Android 的文件名限制对齐（"/" 是分隔符，其余是常见非法字符）。 */
private val ILLEGAL_FOLDER_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

/**
 * 校验文件夹名，返回 null 表示合法。
 *
 * 只校验「名字」本身：路径拼接、目标是否已存在交给 renameFolder 里的文件系统去判，
 * 这里提前拦住明显的错，减少一次 IO 往返。
 */
private fun validateFolderName(raw: String): String? {
    val name = raw.trim()
    if (name.isEmpty()) return "文件夹名不能为空"
    if (name == "." || name == "..") return "不能用「.」或「..」作为文件夹名"
    if (name.any { it in ILLEGAL_FOLDER_NAME_CHARS }) {
        return "不能包含 / \\ : * ? \" < > | 这些字符"
    }
    if (name.length > 128) return "文件夹名太长（最多 128 个字符）"
    return null
}

/**
 * 重命名单个文件夹。
 *
 * 只改目录名本身，里面的图片路径会由 MediaScanner 重新索引；
 * 失败原因（目标已存在、目录被占用等）直接显示在对话框里，方便用户改个名字重试。
 */
@Composable
fun RenameFolderDialog(
    viewModel: EropicViewModel,
    relativePath: String,
    currentName: String,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(relativePath) { mutableStateOf(currentName) }
    // 服务端（文件系统）返回的错误，优先级低于本地实时校验
    var remoteError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val trimmed = name.trim()
    val localError = validateFolderName(name)
    val unchanged = trimmed == currentName

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("重命名文件夹") },
        text = {
            Column {
                Text(
                    text = "当前：$currentName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        remoteError = null
                    },
                    label = { Text("新的文件夹名") },
                    singleLine = true,
                    enabled = !busy,
                    isError = localError != null || remoteError != null,
                    supportingText = {
                        Text(localError ?: remoteError ?: "只改这一层目录名")
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (busy) return@TextButton
                    scope.launch {
                        busy = true
                        val result = runCatching {
                            viewModel.renameFolder(relativePath, trimmed)
                        }.getOrNull()
                        busy = false
                        if (result == null) {
                            remoteError = "重命名失败，请重试"
                        } else if (result.ok) {
                            onMessage(result.message)
                            onDismiss()
                        } else {
                            // 失败留在对话框里，用户可以改个名字直接重试
                            remoteError = result.message
                        }
                    }
                },
                enabled = !busy && localError == null && !unchanged,
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("取消")
            }
        },
    )
}
