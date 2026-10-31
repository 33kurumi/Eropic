package io.github.kurumi33.eropic.ui.imagefind

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kurumi33.eropic.core.search.OnlineHit
import io.github.kurumi33.eropic.core.search.OnlineSearchResult
import java.util.Locale

/**
 * 「联网结果」区块（v0.7 新增）。
 *
 * v0.6 之前联网识图成功后只会弹一句「已在浏览器打开」，原因是有几家（SauceNAO、ascii2d、IQDB）
 * 只返回条目而不是一个可以整页复用的结果页，直接把用户甩到浏览器反而看不到命中列表。
 * v0.7 起 core/search 会把命中解析成 [OnlineHit]，这里就地把结果摆出来，分三种情况：
 *
 * 1. ok = true 且有 resultUrl：给一个「在浏览器打开」按钮（结果页只能由服务商自己渲染）；
 * 2. ok = true 且 items 非空：逐条列出标题 / 相似度 / 「打开」（extUrl 优先，其次 pageUrl）；
 * 3. ok = false：用红色提示展示失败原因。
 *
 * items 为空、resultUrl 也为空时统一显示「没有解析到结果」，不让用户对着一片空白发呆。
 */
@Composable
internal fun OnlineResultsSection(
    result: OnlineSearchResult,
    onOpenUrl: (String) -> Unit,
) {
    val resultUrl = result.resultUrl?.takeIf { it.isNotBlank() }
    val items = result.items
    val summary = when {
        items.isNotEmpty() -> items.size.toString() + " 个结果"
        resultUrl != null -> "已找到结果页"
        result.ok -> "没有解析到结果"
        else -> "识别失败"
    }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.provider.label + " · " + summary,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    // 成功也可能带一句补充说明（例如「只解析到前 N 条」），不要丢掉。
                    val note = result.message
                    if (!note.isNullOrBlank() && result.ok) {
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                if (resultUrl != null) {
                    TextButton(onClick = { onOpenUrl(resultUrl) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("在浏览器打开")
                    }
                }
            }

            when {
                !result.ok -> Text(
                    text = result.message ?: "该服务暂时不可用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                )

                items.isNotEmpty() -> {
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                    items.forEachIndexed { index, hit ->
                        OnlineHitRow(hit = hit, onOpenUrl = onOpenUrl)
                        if (index != items.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }

                resultUrl == null -> Text(
                    text = "没有解析到结果，可以试试别的服务商，或点「打开服务商官网」手动搜索。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                )
            }
        }
    }
}

/**
 * 单条联网结果：标题 + 相似度 + 打开按钮。
 *
 * extUrl 优先（作品在原站的页面），没有才退到 pageUrl（结果列表里的那条记录）；
 * 两个都没有时按钮禁用，只展示标题，避免点了没反应。
 */
@Composable
private fun OnlineHitRow(
    hit: OnlineHit,
    onOpenUrl: (String) -> Unit,
) {
    val title = hit.title?.trim()?.takeIf { it.isNotEmpty() } ?: "未命名结果"
    val target = hit.extUrl?.trim()?.takeIf { it.isNotEmpty() }
        ?: hit.pageUrl?.trim()?.takeIf { it.isNotEmpty() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val similarity = hit.similarity
            Text(
                text = if (similarity != null) {
                    "相似度 " + String.format(Locale.US, "%.1f%%", similarity)
                } else {
                    "相似度未知"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (similarity != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        TextButton(onClick = { target?.let(onOpenUrl) }, enabled = target != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("打开")
        }
    }
}
