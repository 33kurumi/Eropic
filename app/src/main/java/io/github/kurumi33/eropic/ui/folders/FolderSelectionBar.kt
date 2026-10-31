package io.github.kurumi33.eropic.ui.folders

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable

/**
 * 文件夹多选操作条（v0.8）。
 *
 * 长按列表行 / 网格卡片进入多选后，用它替换页面原本的 [io.github.kurumi33.eropic.ui.common.EropicTopBar]：
 * 左边退出、中间显示选中数量、右边是「全选 / 置顶 / 取消置顶 / 移入回收站」。
 *
 * 为什么不用 EropicTopBar：那个组件已经有页面自己的标题和动作，多选时整条替换更清楚，
 * 也和图库多选（[io.github.kurumi33.eropic.ui.components.SelectionTopBar]）保持一致的交互位置。
 *
 * [hasWriteAccess] 只约束「移入回收站」：置顶只是本地偏好，不碰文件系统，没有「所有文件访问」权限也能用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onTrash: () -> Unit,
    hasWriteAccess: Boolean = true,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "退出多选")
            }
        },
        title = {
            // 五个动作按钮占了大半宽度，标题用 titleMedium 才能在窄屏上完整显示。
            Text(
                text = "已选 $count 个文件夹",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(imageVector = Icons.Filled.CheckCircle, contentDescription = "全选当前文件夹")
            }
            IconButton(onClick = onPin) {
                Icon(imageVector = Icons.Filled.Star, contentDescription = "置顶所选文件夹")
            }
            IconButton(onClick = onUnpin) {
                Icon(imageVector = Icons.Filled.StarBorder, contentDescription = "取消置顶所选文件夹")
            }
            IconButton(onClick = onTrash, enabled = hasWriteAccess) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = if (hasWriteAccess) {
                        "移入回收站"
                    } else {
                        "移入回收站（需要「所有文件访问」权限）"
                    },
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}
