package io.github.kurumi33.eropic.ui.about

import android.os.Build
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.kurumi33.eropic.ui.EropicViewModel

/**
 * 关于页（M4 · T5）。
 *
 * 把「这个应用到底碰了你的什么数据」讲清楚：权限、本地文件位置、无网络说明、
 * 依赖许可、崩溃日志。全部只读，不会改动任何图片。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(viewModel: EropicViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var crashLog by remember { mutableStateOf(viewModel.latestCrashLog()) }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.1.0"
    }
    val cacheDir = remember { context.cacheDir.absolutePath }
    val thumbDir = remember { java.io.File(context.cacheDir, "thumbs").absolutePath }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于 Eropic") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "Eropic " + versionName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "个人图片与存储管理工具：本地优先、不上传、不自动删除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    InfoRow("包名", context.packageName)
                    InfoRow("系统", "Android " + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）")
                    InfoRow("设备", Build.MANUFACTURER + " " + Build.MODEL)
                    InfoRow("开源地址", "github.com/33kurumi/Eropic")
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "权限说明", style = MaterialTheme.typography.titleMedium)
                    Bullet("READ_MEDIA_IMAGES", "读取照片，用于建立索引和显示图库。只读。")
                    Bullet("READ_MEDIA_VISUAL_USER_SELECTED", "Android 14+ 的「仅选择部分照片」访问。")
                    Bullet("MANAGE_EXTERNAL_STORAGE", "「所有文件访问」：真实移动、重命名、复制、移入回收站时才需要；未授权时这些按钮置灰。")
                    Bullet("POST_NOTIFICATIONS", "回收站到期提醒。可拒绝。")
                    Text(
                        text = "后台补充权限（WAKE_LOCK、FOREGROUND_SERVICE、RECEIVE_BOOT_COMPLETED）由 WorkManager 依赖合并进来，" +
                            "用于后台补齐缩略图与自动同步。Eropic 没有申请 INTERNET 权限，所以物理上无法上传任何图片。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "本地数据都放在哪", style = MaterialTheme.typography.titleMedium)
                    InfoRow("索引数据库", "应用私有目录（Room / SQLite），可随时重建")
                    InfoRow("缩略图缓存", thumbDir)
                    InfoRow("缓存根目录", cacheDir)
                    InfoRow("回收站", "存储卷根目录 /.EropicTrash（带 .nomedia，不会被相册扫描到）")
                    Text(
                        text = "卸载应用会一并删除数据库与缩略图缓存；但 /.EropicTrash 里的文件在卸载前请先恢复或清空，否则会成为普通隐藏文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "第三方组件与许可", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Kotlin、Jetpack Compose、Room、Paging、Coil、WorkManager、ExifInterface —— 均为 Apache 2.0。" +
                            "本项目的设计参考了 Immich、PhotoPrism、Aves、Fossify Gallery 等开源项目的公开交互与思路，" +
                            "但未复制其源码（其中部分项目为 AGPL/GPL 许可）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "崩溃日志", style = MaterialTheme.typography.titleMedium)
                    if (crashLog.isNullOrBlank()) {
                        Text(
                            text = "没有记录到崩溃。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(text = crashLog.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            viewModel.clearCrashLogs()
                            crashLog = null
                        }) { Text("清空日志") }
                    }
                    Text(
                        text = "日志只写在本机应用私有目录，不含图片内容，不会上传。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "设计原则", style = MaterialTheme.typography.titleMedium)
                    Bullet("只检索，不决策", "重复/相似/大文件只做检索和分组，删不删、留哪张永远由你确认。")
                    Bullet("可恢复", "删除 = 移入回收站，默认 30 天可恢复；移动/重命名/复制都能在操作历史里撤销。")
                    Bullet("本地", "没有网络权限，不上传、不联网比对。")
                    Bullet("可迁移", "索引数据库是缓存，删掉重建即可，你的照片目录结构不会被改写。")
                }
            }

            Text(
                text = "Eropic · 用 Kotlin + Jetpack Compose 写的本地工具。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Bullet(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = "· " + title, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
