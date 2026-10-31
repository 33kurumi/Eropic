package io.github.kurumi33.eropic.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 全 App 统一的顶栏（v0.5）。
 *
 * 为什么单独抽一个：五个主 Tab（图库 / 文件夹 / 清理 / 设置 / 识图）此前各自拼 TopAppBar，
 * 标题字号、副标题有无、内边距都不一样，切换时高度会跳。这里固定三件事：
 * 1. 标题固定 titleLarge；有副标题时副标题 bodySmall 排在标题下面，**没有副标题时不渲染那一行**；
 * 2. 左侧返回键只在需要时显示，不用返回键时也不占位（避免标题缩进不一致）；
 * 3. 动作按钮统一放在 [actions] 里，由各页面自己决定。
 *
 * v0.6 修正：此前副标题为空也会渲染一个 16dp 的空 Text「占位」，结果没有副标题的页面
 * 标题整体被推高、与左侧返回箭头不在一条水平线上（用户反馈「没有小注却被占了位」）。
 * 现在没有副标题时只渲染标题，TopAppBar 自己会把单行标题垂直居中。
 *
 * v1.0：[compactHeight] 给五个主页用。Material3 的 TopAppBar 固定 64dp，加上状态栏留白后
 * 标题视觉上偏低（用户反馈「顶部的东西往上移一点点」）。这里不再硬改 TopAppBar 的高度
 * （它内部会自己加 windowInsets，直接压高度会把内容裁掉），而是在紧凑模式下换成
 * 「Surface + 48dp Row」：状态栏留白照常，标题行比 64dp 矮 16dp，整体上移。
 * 二级页（带返回键、有副标题）继续用标准 TopAppBar，保证返回键热区与视觉一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EropicTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    compactHeight: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val sub = subtitle?.takeIf { it.isNotBlank() }

    if (compactHeight) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(48.dp)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (sub != null) {
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                actions()
            }
        }
        return
    }

    TopAppBar(
        title = {
            if (sub == null) {
                // 单行标题：交给 TopAppBar 垂直居中，与左侧返回箭头对齐
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.height(16.dp).padding(top = 2.dp),
                    )
                }
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
