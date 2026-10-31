package io.github.kurumi33.eropic.ui.permission

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.kurumi33.eropic.core.permissions.StorageAccess
import io.github.kurumi33.eropic.ui.EropicViewModel

/**
 * 「所有文件访问」（MANAGE_EXTERNAL_STORAGE）说明页（v0.2）。
 *
 * 浏览、查重、缩略图都不需要这个权限；只有真实改动文件的操作（移动、重命名、移入回收站、
 * 从回收站恢复、彻底删除）才需要，所以这里要把"为什么"和"风险"都讲清楚，并把入口做成
 * 「随时可以再去系统设置改/关」，而不是一次授权后就再也找不到地方关。
 *
 * 系统设置是另一个应用页面，回到本页时不会自动重组，所以用 [DisposableEffect] 监听
 * ON_RESUME 重新读一次真实状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAccessScreen(
    viewModel: EropicViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(viewModel.hasAllFilesAccess()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = viewModel.hasAllFilesAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 跳到系统设置：个别定制系统没有这个页面，启动失败时不要崩。
    val openSystemSettings = {
        runCatching { context.startActivity(StorageAccess.requestIntent(context)) }
        Unit
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("所有文件访问") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---- 当前状态 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(text = "当前状态", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "状态：" + if (hasAccess) "已授权" else "未授权",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (hasAccess) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Text(
                        text = if (hasAccess) {
                            "可以真实移动/重命名文件，也能把图片移入回收站并恢复。"
                        } else {
                            "浏览、查重、缩略图都不受影响；只有写操作会被禁用。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 用户最关心「不给这个权限还能不能用」，所以单独一行说清楚。
                    Text(
                        text = "只读功能不受影响：浏览、搜索、查重、缩略图、存储总览都照常可用，" +
                            "不需要这个权限。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (hasAccess) {
                        OutlinedButton(onClick = openSystemSettings) {
                            Text("去系统设置修改或关闭")
                        }
                    } else {
                        Button(onClick = openSystemSettings) {
                            Text("去系统设置授权")
                        }
                    }
                    // 应用内所有「未授予」提示都跳到本页，这里说明一下，避免用户以为被绕了一圈。
                    Text(
                        text = "应用里其他位置（回收站、文件夹菜单、清理页、多选菜单）的" +
                            "「未授予」提示点一下都会回到本页，再从这里去系统设置。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- 为什么需要 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(text = "为什么需要这个权限", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Android 11 起，普通存储权限只能读到媒体库里的照片，无法在任意目录里" +
                            "移动、重命名文件，也看不到以 . 开头的目录（回收站就在其中）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Eropic 的「真实移动 / 重命名 / 移入回收站 / 恢复 / 彻底删除」都直接改存储卡上的文件，" +
                            "所以需要「所有文件访问」；没有它，这些按钮会一直置灰。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- 风险说明 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(text = "风险与边界", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = "这是一项范围很大的权限：授权后应用可以读写共享存储里的所有文件。" +
                            "Eropic 只在本地做文件操作，所有改动都要你确认后才执行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "没有任何自动删除：移入回收站也只是同卷改名，仍在原卷上，可随时恢复。" +
                            "只有你确认的「永久删除 / 清空回收站」才会真的删掉文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- 关闭后的影响 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(text = "关闭后还能做什么", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "即使关闭也能继续浏览图库、查重、看存储总览、搜索、按清理建议筛选；" +
                            "只是移动、重命名、删除、回收站恢复这些写操作会被禁用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "系统入口一般在「设置 → 应用 → 特殊应用权限 → 所有文件访问权限」，" +
                            "部分定制系统把它放在「存储」或「其他权限」里。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
