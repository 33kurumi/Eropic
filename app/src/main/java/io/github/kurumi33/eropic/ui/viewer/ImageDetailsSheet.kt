package io.github.kurumi33.eropic.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.ui.common.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 大图查看器的"图片信息"面板内容（由 ImageViewerScreen 用 ModalBottomSheet 包起来）。
 *
 * 展示文件名、路径、相册、大小、分辨率、格式、拍摄/修改时间、收藏状态与感知哈希。
 */
@Composable
fun ImageDetailsSheet(entity: MediaEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Text(text = "图片信息", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(6.dp))

        DetailRow(label = "文件名", value = entity.displayName)
        DetailRow(
            label = "所在相册",
            value = entity.relativePath.ifBlank { "未知" },
        )
        DetailRow(label = "大小", value = formatBytes(entity.sizeBytes))
        DetailRow(label = "分辨率", value = "${entity.width} × ${entity.height}")
        DetailRow(label = "格式", value = friendlyFormatName(entity.mimeType))
        DetailRow(label = "拍摄时间", value = formatDateTime(entity.dateTakenMs))
        DetailRow(label = "修改时间", value = formatDateTime(entity.dateModifiedMs))
        DetailRow(
            label = "收藏状态",
            value = if (entity.localFavorite || entity.isFavorite) "已收藏" else "未收藏",
        )
        DetailRow(label = "dHash", value = formatHash(entity.dhash))
        DetailRow(label = "pHash", value = formatHash(entity.phash))
        DetailRow(
            label = "完整路径",
            value = entity.path,
            action = {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("图片路径", entity.path))
                        Toast.makeText(context, "路径已复制", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text(text = "复制")
                }
            },
        )

        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(text = "关闭")
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** 一行"标签 + 值"，右侧可选一个操作按钮（例如复制路径）。 */
@Composable
private fun DetailRow(
    label: String,
    value: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(76.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (action != null) {
                action()
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** mimeType 转友好格式名：image/jpeg → JPG，其它未知类型取斜杠后半段大写。 */
private fun friendlyFormatName(mimeType: String): String {
    val lower = mimeType.lowercase(Locale.US)
    return when (lower) {
        "image/jpeg" -> "JPG"
        "image/png" -> "PNG"
        "image/webp" -> "WebP"
        "image/heic" -> "HEIC"
        else -> {
            val suffix = lower.substringAfter('/', "")
            if (suffix.isBlank()) {
                lower.uppercase(Locale.US)
            } else {
                suffix.uppercase(Locale.US)
            }
        }
    }
}

/** 毫秒时间戳转本地时间；非正值视为未知。 */
private fun formatDateTime(ms: Long): String {
    if (ms <= 0L) return "未知"
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return formatter.format(Date(ms))
}

/** 64 位感知哈希转十六进制；未计算时显示"未计算"。 */
private fun formatHash(value: Long?): String {
    if (value == null) return "未计算"
    return String.format(Locale.US, "%016X", value)
}
