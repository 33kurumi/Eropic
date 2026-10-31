package io.github.kurumi33.eropic.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kurumi33.eropic.core.permissions.StorageAccess

/**
 * 「缺少所有文件访问权限」提示的统一实现（v0.8）。
 *
 * 用户反馈「个别需要授权的地方显示未授权但点不了」：所以全项目所有未授权提示都必须是可点的，
 * 点了之后进 App 内的权限说明页（[io.github.kurumi33.eropic.ui.AppScreen.Permission]）或系统设置。
 * 文案也统一放在这里，避免各处口径不一致。
 */

/** 统一文案的前半句：缺什么权限、哪些操作不可用。 */
const val WriteAccessHintText: String = "未授予「所有文件访问」权限 —— 移动、删除、重命名等写操作不可用。"

/** 统一文案里的动作词。 */
const val WriteAccessHintAction: String = "去授权"

/** 完整一句话（含动作词），给菜单项、单行提示这类不能拆开排版的地方用。 */
const val WriteAccessHintFullText: String = WriteAccessHintText + " " + WriteAccessHintAction

/**
 * 直接跳系统的「所有文件访问」授权页。
 *
 * 底部弹层、下拉菜单这类拿不到 viewModel 的地方用它兜底，保证提示永远点得动；
 * 个别定制系统没有这个页面，跳转失败时不要让应用崩掉。
 */
fun openSystemStorageAccessSettings(context: Context) {
    runCatching { context.startActivity(StorageAccess.requestIntent(context)) }
}

/**
 * 整条可点的提示条：errorContainer 底色，右侧「去授权」。
 *
 * 整条都能点（不只是右侧三个字），避免用户点了空白处没反应。
 */
@Composable
fun WriteAccessHintBar(
    onOpenPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onOpenPermission)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = WriteAccessHintText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = WriteAccessHintAction,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/**
 * 一行式可点提示：图标 + 完整文案（含「去授权」）。
 *
 * 用在底部弹层、卡片内部这类没有底色条的位置；整行可点。
 */
@Composable
fun WriteAccessHintInline(
    onOpenPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenPermission)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = WriteAccessHintFullText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
    }
}
