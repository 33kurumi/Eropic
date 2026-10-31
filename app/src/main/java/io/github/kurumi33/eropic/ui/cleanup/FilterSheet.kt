package io.github.kurumi33.eropic.ui.cleanup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kurumi33.eropic.core.query.MediaFilter

/** 日期快捷筛选。 */
enum class DatePreset(val label: String) {
    ANY("不限"),
    LAST_7_DAYS("近 7 天"),
    LAST_30_DAYS("近 30 天"),
    LAST_YEAR("近一年"),
    OLDER_THAN_3Y("3 年前"),
}

/** 文件格式快捷筛选。 */
enum class FormatFilter(val label: String, val mimes: List<String>) {
    JPG("JPG", listOf("image/jpeg")),
    PNG("PNG", listOf("image/png")),
    WEBP("WEBP", listOf("image/webp")),
    HEIC("HEIC", listOf("image/heic", "image/heif")),
    GIF("GIF", listOf("image/gif")),
}

/**
 * 组合筛选的 UI 状态。
 *
 * 只放"用户能理解"的维度（大小/格式/时间/收藏）；分辨率暂未放进 UI，
 * 因为「长边 ≥ 1080」这类条件需要 max(width,height)，当前 SQL 构造器只支持 widthMin/heightMin。
 */
data class FilterUiState(
    val sizeMinMb: Int? = null,
    val formats: Set<FormatFilter> = emptySet(),
    val datePreset: DatePreset = DatePreset.ANY,
    val favoritesOnly: Boolean = false,
) {
    val isEmpty: Boolean
        get() = sizeMinMb == null && formats.isEmpty() &&
            datePreset == DatePreset.ANY && !favoritesOnly

    fun toFilter(nowMs: Long = System.currentTimeMillis()): MediaFilter {
        val day = 24L * 60L * 60L * 1000L
        return MediaFilter(
            sizeMinBytes = sizeMinMb?.let { it.toLong() * 1024L * 1024L },
            mimeTypes = formats.flatMap { it.mimes }.toSet(),
            dateFromMs = when (datePreset) {
                DatePreset.LAST_7_DAYS -> nowMs - 7 * day
                DatePreset.LAST_30_DAYS -> nowMs - 30 * day
                DatePreset.LAST_YEAR -> nowMs - 365 * day
                else -> null
            },
            dateToMs = when (datePreset) {
                DatePreset.OLDER_THAN_3Y -> nowMs - 3 * 365 * day
                else -> null
            },
            favoritesOnly = favoritesOnly,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    initial: FilterUiState,
    onDismiss: () -> Unit,
    onApply: (FilterUiState) -> Unit,
) {
    // FilterUiState 不是 Parcelable/Saveable，用普通 remember，避免保存时崩溃。
    var state by remember { mutableStateOf(initial) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "组合筛选", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "筛选只影响显示，不会改动任何文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(text = "文件大小", style = MaterialTheme.typography.labelLarge)
            FilterChipsRow(
                options = listOf(
                    null to "不限",
                    5 to "≥ 5 MB",
                    10 to "≥ 10 MB",
                    50 to "≥ 50 MB",
                    100 to "≥ 100 MB",
                ),
                selected = state.sizeMinMb,
                onSelect = { state = state.copy(sizeMinMb = it) },
            )

            Text(text = "格式", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FormatFilter.entries.forEach { format ->
                    FilterChip(
                        selected = format in state.formats,
                        onClick = {
                            state = state.copy(
                                formats = if (format in state.formats) {
                                    state.formats - format
                                } else {
                                    state.formats + format
                                },
                            )
                        },
                        label = { Text(format.label) },
                    )
                }
            }

            Text(text = "时间", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DatePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = state.datePreset == preset,
                        onClick = { state = state.copy(datePreset = preset) },
                        label = { Text(preset.label) },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(text = "只看收藏", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "包含系统相册收藏与 Eropic 内收藏。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.favoritesOnly,
                    onCheckedChange = { state = state.copy(favoritesOnly = it) },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { state = FilterUiState() }) { Text("重置") }
                Button(
                    onClick = { onApply(state) },
                    modifier = Modifier.weight(1f),
                ) { Text("应用筛选") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChipsRow(
    options: List<Pair<Int?, String>>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}
