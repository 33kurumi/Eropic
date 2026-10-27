package io.github.kurumi33.eropic.ui.cleanup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatDate

/**
 * 重复/相似分组的双栏对比预览。
 *
 * 只读：这里不做任何删除、保留或移动操作，M3 才接「选择保留」。
 * 两栏各自独立翻页，点图片本身切到下一张，方便把两张图对齐着看。
 */
@Composable
fun CompareScreen(
    viewModel: EropicViewModel,
    groupId: Long,
    onClose: () -> Unit,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
) {
    // loaded 用来区分「还在查」和「查完是空」：只按 members.isEmpty() 判断会让
    // 被重新索引清掉的组永远转圈。
    var loaded by remember(groupId) { mutableStateOf(false) }
    val members by produceState(initialValue = emptyList<MediaEntity>(), groupId) {
        value = viewModel.members(groupId)
        loaded = true
    }
    var leftIndex by rememberSaveable(groupId) { mutableIntStateOf(0) }
    var rightIndex by rememberSaveable(groupId) { mutableIntStateOf(1) }

    // 返回键先退出对比，再交给外层。
    BackHandler(onBack = onClose)

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
            Text(
                text = "对比预览 · ${members.size} 张",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (members.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loaded) {
                    Text(
                        text = "该重复组已不存在（可能刚重新扫描过）",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        } else {
            val last = members.lastIndex
            val safeLeft = leftIndex.coerceIn(0, last)
            val safeRight = rightIndex.coerceIn(0, last)
            val ids = members.map { it.id }

            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                ComparePane(
                    media = members[safeLeft],
                    label = "A",
                    onClick = { leftIndex = if (safeLeft >= last) 0 else safeLeft + 1 },
                    modifier = Modifier.weight(1f),
                )
                ComparePane(
                    media = members[safeRight],
                    label = "B",
                    onClick = { rightIndex = if (safeRight >= last) 0 else safeRight + 1 },
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = "点某一栏的图片可切到下一张；两栏可以选同一张做 1:1 像素对照。",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onOpenViewer(ids, safeLeft) },
                    modifier = Modifier.weight(1f),
                ) { Text("大图查看 A") }
                OutlinedButton(
                    onClick = { onOpenViewer(ids, safeRight) },
                    modifier = Modifier.weight(1f),
                ) { Text("大图查看 B") }
            }
        }
    }
}

@Composable
private fun RowScope.ComparePane(
    media: MediaEntity,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxHeight()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // 对比要看细节，这里直接用原图（Fit 不裁切），不走缩略图缓存。
            AsyncImage(
                model = MediaUris.of(media.id),
                contentDescription = media.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            )
        }
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text(
                text = media.displayName,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${formatBytes(media.sizeBytes)} · ${media.width}×${media.height} · ${formatDate(media.dateTakenMs)}",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
