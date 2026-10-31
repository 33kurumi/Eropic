package io.github.kurumi33.eropic.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kurumi33.eropic.core.update.UpdateInfo
import io.github.kurumi33.eropic.ui.EropicViewModel
import kotlin.math.roundToInt

/**
 * 设置页「应用更新」卡片（v0.7）。
 *
 * 只做展示与转发：检查、下载、安装全部由 ViewModel 负责（UpdateUiState 一条状态驱动），
 * 这样旋屏、切页回来都还能接着看到下载进度，而不是把状态藏在 Composable 里。
 *
 * 为什么「是否已是最新」由 ViewModel 的 updateAvailable 决定，而不在这里比 versionCode：
 * 本项目的 versionCode 是构建时间戳（保证每次装包都被系统当成新版本），本地刚构建的包
 * 一定比线上 release 的时间戳更大，用 versionCode 比会永远显示「已是最新」，把真正的更新藏掉。
 * ViewModel 里改用 VersionCompare.isNewer(版本名) 逐段比较数字，1.10.0 > 1.9.0 也成立。
 */
@Composable
fun UpdateSection(viewModel: EropicViewModel) {
    val state by viewModel.updateState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val info = state.info
    // 只有「查到了信息、且 ViewModel 明确判定远端更新」才算有新版。
    // 不能用 versionCode 比较（原因见文件头注释），也不在没查过的时候冒充「已是最新」。
    val upToDate = info != null && !state.updateAvailable

    // 更新说明默认最多 3 行，长说明给一个「展开全部」；换版本号时自动收起。
    var notesExpanded by remember(info?.versionName) { mutableStateOf(false) }
    val notesExpandable = info != null && isNotesLong(info)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "应用更新",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            // 第一行：当前版本 + 检查按钮。检查中按钮禁用并转圈，避免连点发多次请求。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "当前版本：" + viewModel.currentVersionName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = viewModel::checkForUpdates,
                    enabled = !state.checking,
                ) {
                    if (state.checking) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Text(text = "检查中…")
                        }
                    } else {
                        Text(text = "检查更新")
                    }
                }
            }

            // 检查失败：红色原因 + 一句「这是正常的」，否则仓库没发 release 时用户会以为坏了。
            val checkingError = state.checkingError
            if (!checkingError.isNullOrBlank()) {
                Text(
                    text = "检查失败：" + checkingError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "仓库还没发布 release 时这是正常的，可以稍后再试。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (info != null) {
                HorizontalDivider()

                if (upToDate) {
                    Text(
                        text = "已是最新版本",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = "发现新版本 " + info.versionName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                val notes = info.notes?.trim().orEmpty()
                if (notes.isNotBlank()) {
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (notesExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (notesExpandable) {
                        TextButton(onClick = { notesExpanded = !notesExpanded }) {
                            Text(text = if (notesExpanded) "收起" else "展开全部")
                        }
                    }
                }

                // 下载/安装入口：已下载就只剩安装；已是最新则禁用下载，只留发布页可看。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.downloadedPath != null) {
                        Button(onClick = viewModel::installUpdate) {
                            Text(text = "安装更新")
                        }
                    } else {
                        Button(
                            onClick = viewModel::downloadUpdate,
                            enabled = !upToDate && !state.downloading,
                        ) {
                            Text(text = if (state.downloading) "下载中…" else "下载并安装")
                        }
                    }
                    OutlinedButton(
                        onClick = { info.pageUrl?.let { openUrl(context, it) } },
                        enabled = !info.pageUrl.isNullOrBlank(),
                    ) {
                        Text(text = "打开发布页")
                    }
                }

                if (state.downloading) {
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "下载中 " + (state.progress.coerceIn(0f, 1f) * 100f).roundToInt() + "%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 安装包已下载但还没装：提示文件位置，方便用户自己去装（系统安装器也可能被拦截）。
                val downloadedPath = state.downloadedPath
                if (!downloadedPath.isNullOrBlank()) {
                    Text(
                        text = "安装包已下载：" + downloadedPath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 一次性提示（下载失败、安装失败等）：卡片内小字 + 手动清空，不用 toast 是因为错误要能反复看。
            val message = state.message
            if (!message.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::clearUpdateMessage) {
                        Text(text = "知道了")
                    }
                }
            }
        }
    }
}

/** 说明是否够长到值得折叠：按字数或行数粗略判断，纯 UI 取舍，不去精确测量布局。 */
private fun isNotesLong(info: UpdateInfo): Boolean {
    val notes = info.notes.orEmpty()
    return notes.length > 90 || notes.count { it == '\n' } >= 3
}

/** 打开发布页；页面地址来自 release 数据，为空或系统没有浏览器时静默失败（不打扰用户）。 */
private fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
