package io.github.kurumi33.eropic.ui.update

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kurumi33.eropic.ui.EropicViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 设置页「应用更新」卡片（v1.0 重做安装链路）。
 *
 * 旧版的问题是「点安装没反应、不知道安装包在哪、不知道失败原因」：v1.0 起卡片按
 * 「当前版本 → 新版本信息 → 下载进度 → 安装」四段排列，并把三件事讲清楚：
 * 1. 安装包存在哪（应用缓存目录，可点路径复制，可在本卡删除）；
 * 2. 为什么点安装没反应（没授予「安装未知应用」时给醒目提示 + 「去授权」按钮）；
 * 3. 失败原因直接写在卡片里（下载失败 / 安装失败都不静默）。
 *
 * 只做展示与转发：检查、下载、取消、安装、删包、跳权限页全部由 ViewModel 负责
 * （UpdateUiState 一条状态驱动），这样旋屏、切页回来都还能接着看到下载进度。
 * 这里不自己写 Intent / FileProvider —— 安装器由 ViewModel 统一拉起，避免两份实现不一致。
 *
 * 为什么「是否已是最新」看 ViewModel 的 updateAvailable，而不在这里比 versionCode：
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
    val notes = info?.notes?.trim().orEmpty()
    val notesExpandable = notes.isNotBlank() && isNotesLong(notes)

    val downloadedPath = state.downloadedPath
    val message = state.message
    val messageIsFailure = message != null && isFailureMessage(message)

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

            // ---- 第一段：当前版本 + 检查更新 ----
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
                        // 检查中按钮禁用并转圈，避免连点发多次请求。
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

                // ---- 第二段：新版本信息（版本号 / 更新时间 / 更新说明） ----
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
                // 更新时间来自 release 的 published_at；0 表示解析不到，宁可不显示也不写错。
                val publishedText = formatPublishedAt(info.publishedAtMs)
                if (publishedText != null) {
                    Text(
                        text = "更新时间：$publishedText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

                if (!upToDate) {
                    HorizontalDivider()

                    // ---- 第三段：下载进度（进度条 + 百分比 + 取消） ----
                    if (state.downloading) {
                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = downloadingLabel(state.progress),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            // 取消只停当前下载，已下完的安装包不删（要删请用「删除安装包」）。
                            TextButton(onClick = viewModel::cancelDownload) {
                                Text(text = "取消下载")
                            }
                        }
                    } else if (downloadedPath.isNullOrBlank()) {
                        // 还没下载：主入口是「下载并安装」，旁边留发布页作为手动兜底。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = viewModel::downloadUpdate,
                                enabled = !info.downloadUrl.isNullOrBlank(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(text = "下载并安装")
                            }
                            if (!info.pageUrl.isNullOrBlank()) {
                                OutlinedButton(onClick = { openUrl(context, info.pageUrl) }) {
                                    Text(text = "打开发布页")
                                }
                            }
                        }
                        if (info.downloadUrl.isNullOrBlank()) {
                            Text(
                                text = "这个 release 没有附 APK，请打开发布页手动下载。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // ---- 第四段：下载完成后（安装 / 路径 / 删除 / 权限提示） ----
                    if (!downloadedPath.isNullOrBlank()) {
                        // 未授予「安装未知应用」时先说明原因，再给「去授权」，避免用户点了安装没反应。
                        if (state.needsInstallPermission) {
                            InstallPermissionHint(onGrant = viewModel::openInstallPermissionSettings)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = viewModel::installUpdate,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(text = "安装更新")
                            }
                            OutlinedButton(onClick = viewModel::deleteDownloadedUpdate) {
                                Text(text = "删除安装包")
                            }
                        }
                        InstallerPathRow(
                            path = downloadedPath,
                            onCopy = {
                                val copied = copyToClipboard(context, "Eropic 安装包路径", downloadedPath)
                                viewModel.postMessage(
                                    if (copied) "已复制安装包路径" else "复制失败，可以换「打开发布页」手动下载",
                                )
                            },
                        )
                        Text(
                            text = "安装包保存在应用缓存目录，安装成功后保留，可在本卡删除；清空缓存会被系统清掉。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!info.pageUrl.isNullOrBlank()) {
                            TextButton(onClick = { openUrl(context, info.pageUrl) }) {
                                Text(text = "打开发布页")
                            }
                        }
                    }
                }
            }

            // ---- 一次性提示（下载失败 / 取消 / 安装失败等）：卡片内可回看，不静默 ----
            if (!message.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (messageIsFailure) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
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

/** 「需要允许安装未知应用」的醒目提示：说清系统限制 + 直接给一条去授权的路。 */
@Composable
private fun InstallPermissionHint(onGrant: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "需要允许 Eropic 安装应用",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text = "系统限制：从应用内安装 APK，必须先在系统设置里为 Eropic 打开「安装未知应用」。",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onGrant) {
                Text(text = "去授权")
            }
        }
    }
}

/** 「安装包：<路径>」一行，整行可点复制；路径可能很长，最多两行后省略。 */
@Composable
private fun InstallerPathRow(path: String, onCopy: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onCopy)
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = "安装包：" + path,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCopy) {
            Text(text = "复制路径")
        }
    }
}

/** 下载中文案：拿不到 Content-Length（progress 一直是 0）时只显示「正在下载…」，不冒充 0%。 */
private fun downloadingLabel(progress: Float): String {
    val safe = progress.coerceIn(0f, 1f)
    return if (safe <= 0f) "正在下载…" else "下载中 " + (safe * 100f).roundToInt() + "%"
}

/** 失败的提示用红色：下载失败/安装失败/包不存在这些要让用户一眼看到，不能混在灰字里。 */
private fun isFailureMessage(text: String): Boolean =
    text.contains("失败") || text.contains("无法") || text.contains("不存在") || text.contains("错误")

/** 说明是否够长到值得折叠：按字数或行数粗略判断，纯 UI 取舍，不去精确测量布局。 */
private fun isNotesLong(notes: String): Boolean =
    notes.length > 90 || notes.count { it == '\n' } >= 3

/** 版本发布时间（本地时区 yyyy-MM-dd HH:mm）；0 或格式化失败返回 null，卡片就不显示这一行。 */
private fun formatPublishedAt(ms: Long): String? {
    if (ms <= 0L) return null
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }.getOrNull()
}

/** 复制文本：用系统剪贴板，失败返回 false 由调用方转成提示（不抛给 UI）。 */
private fun copyToClipboard(context: Context, label: String, text: String): Boolean {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return false
    return runCatching {
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
        true
    }.getOrDefault(false)
}

/** 打开发布页；页面地址来自 release 数据，为空或系统没有浏览器时静默失败（不打扰用户）。 */
private fun openUrl(context: Context, url: String?) {
    if (url.isNullOrBlank()) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
