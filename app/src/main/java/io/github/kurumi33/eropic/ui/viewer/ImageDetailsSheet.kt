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
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.ui.common.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            label = "SHA-256",
            value = entity.sha256 ?: "未计算（精确查重扫描时才生成，用于逐字节判定是否为同一文件）",
        )
        DetailRow(label = "指纹状态", value = signatureStatusOf(entity.signatureVersion))
        ExifSection(entity = entity)

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

/**
 * 相机 EXIF 区（M4 · T3）：直接读原始文件的 EXIF，不经 MediaStore，
 * 所以能看到厂商、机型、光圈、快门、ISO、GPS 这些 MediaStore 不提供的字段。
 *
 * 用 content Uri 打开输入流而不是文件路径，避免「所有文件访问」未授权时读不到。
 */
@Composable
private fun ExifSection(entity: MediaEntity) {
    val context = LocalContext.current
    val rows by produceState<List<Pair<String, String>>>(initialValue = emptyList(), entity.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(MediaUris.of(entity.id))?.use { input ->
                    readExifRows(ExifInterface(input))
                }
            }.getOrNull() ?: emptyList()
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(text = "相机与 EXIF", style = MaterialTheme.typography.titleSmall)
        if (rows.isEmpty()) {
            Text(
                text = "没有可读的 EXIF（截图、聊天保存的图、或元数据已被清理）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            rows.forEach { (label, value) -> DetailRow(label = label, value = value) }
        }
    }
}

/** 从 ExifInterface 抽出非空字段；「拍摄参数」把光圈/快门/ISO/焦距合成一行。 */
private fun readExifRows(exif: ExifInterface): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    fun add(label: String, value: String?) {
        if (!value.isNullOrBlank()) rows += label to value
    }

    add("相机厂商", exif.getAttribute(ExifInterface.TAG_MAKE))
    add("机型", exif.getAttribute(ExifInterface.TAG_MODEL))
    add("镜头", exif.getAttribute(ExifInterface.TAG_LENS_MODEL))

    val params = listOfNotNull(
        exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/" + it },
        exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { it + " s" },
        exif.getAttribute(ExifInterface.TAG_ISO_SPEED)?.let { "ISO " + it },
        exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { it + " mm" },
    ).joinToString(" · ")
    add("拍摄参数", params)

    add("拍摄时间(EXIF)", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
    add(
        "方向",
        orientationLabel(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)),
    )
    exif.latLong?.let { latLong ->
        add("GPS", String.format(Locale.US, "%.6f, %.6f", latLong[0], latLong[1]))
    }
    add("软件", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
    return rows
}

/** EXIF 方向值转成人话，方便判断这张图会不会被旋转显示。 */
private fun orientationLabel(orientation: Int): String = when (orientation) {
    ExifInterface.ORIENTATION_NORMAL -> "正常"
    ExifInterface.ORIENTATION_ROTATE_90 -> "顺时针 90°"
    ExifInterface.ORIENTATION_ROTATE_180 -> "旋转 180°"
    ExifInterface.ORIENTATION_ROTATE_270 -> "顺时针 270°"
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "水平镜像"
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> "垂直镜像"
    ExifInterface.ORIENTATION_TRANSPOSE -> "镜像 + 90°"
    ExifInterface.ORIENTATION_TRANSVERSE -> "镜像 + 270°"
    else -> ""
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

/** 指纹计算状态：0 未计算；-1 解码失败（跳过，不再重试）；>0 已计算。 */
private fun signatureStatusOf(version: Int): String = when {
    version > 0 -> "已计算（v$version）"
    version < 0 -> "无法解码（解码失败，已跳过）"
    else -> "未计算"
}

/** 64 位感知哈希转十六进制；未计算时显示"未计算"。 */
private fun formatHash(value: Long?): String {
    if (value == null) return "未计算"
    return String.format(Locale.US, "%016X", value)
}
