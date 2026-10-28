package io.github.kurumi33.eropic.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 全 App 统一的顶栏（v0.5）。
 *
 * 为什么单独抽一个：五个主 Tab（图库 / 文件夹 / 清理 / 设置 / 识图）此前各自拼 TopAppBar，
 * 标题字号、副标题有无、内边距都不一样，切换时高度会跳。这里固定三件事：
 * 1. 标题固定 titleLarge，副标题固定 bodySmall 且**始终占一行**（没有副标题时写空串），保证各页等高；
 * 2. 左侧返回键只在需要时显示，不用返回键时也不占位（避免标题缩进不一致）；
 * 3. 动作按钮统一放在 [actions] 里，由各页面自己决定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EropicTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // 空串也占位：没有副标题的页面（如设置）与有副标题的页面高度一致，切 Tab 不跳动
                    text = subtitle ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(16.dp).padding(top = 2.dp),
                )
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        },
        actions = actions,
    )
}
