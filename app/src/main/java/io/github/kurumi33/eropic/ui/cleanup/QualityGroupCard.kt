package io.github.kurumi33.eropic.ui.cleanup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.analysis.DuplicateScanner
import io.github.kurumi33.eropic.core.cleanup.GroupQualityAssessment
import io.github.kurumi33.eropic.core.cleanup.QualityScore
import io.github.kurumi33.eropic.core.database.entity.DuplicateGroupEntity
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.components.WriteAccessHintInline
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

/** 卡片内使用的「成员 + 一个质量评分」，按总分降序排好后取前两名做 A/B 对照。 */
private data class RankedMember(val media: MediaEntity, val score: QualityScore)

/** 缩略图加载状态：加载中 / 已取到缩略图文件 / 回退用原始 content Uri。 */
private sealed interface ThumbState {
    data object Loading : ThumbState
    class Ready(val file: File) : ThumbState
    data object Fallback : ThumbState
}

/**
 * 重复/相似分组的「双图并排 + 参数对照 + 判断依据」卡片。
 *
 * 纯展示：卡片自己不算任何分数，评分与依据全部来自 [assessment]，保证和清理页口径一致。
 * 勾选状态由调用方（CleanupScreen）统一持有，卡片只负责渲染「这张是否加入待删」并回调切换；
 * 真正删除由 [onApply] 的调用方执行（只会移入回收站，30 天内可恢复）。
 *
 * v0.9：
 * - 卡头类型标签按 [DuplicateGroupEntity.kind] 显示「重复 / 相似 / 差分」，差分组额外给一行解释；
 * - 新增「保留本组」入口（[onIgnoreGroup]）：只是记住这一组以后不再显示，不删除、不移动任何文件。
 */
@Composable
fun QualityGroupCard(
    group: DuplicateGroupEntity,
    members: List<MediaEntity>,
    assessment: GroupQualityAssessment,
    loadThumbnail: suspend (MediaEntity) -> File?,
    selectedTrashIds: Set<Long>,
    onToggleTrash: (Long) -> Unit,
    onSelectSuggested: () -> Unit,
    onApply: () -> Unit,
    onOpenViewer: (MediaEntity) -> Unit,
    onCompare: () -> Unit,
    hasWriteAccess: Boolean,
    onOpenPermission: () -> Unit,
    // v0.9：保留本组（只写忽略标记，不删文件）；为 null 时按钮禁用，旧调用方不传也能编译。
    onIgnoreGroup: (() -> Unit)? = null,
    // v0.9：一次看完整组；暂未接入也不影响卡片其余功能。
    onCompareAll: (() -> Unit)? = null,
) {
    // 评分与成员按 id 对齐，再按总分降序：A/B 两栏取前两名，第三张起排成小方块。
    val ranked = remember(members, assessment) {
        assessment.scores
            .mapNotNull { score ->
                members.firstOrNull { it.id == score.mediaId }?.let { RankedMember(it, score) }
            }
            .sortedByDescending { it.score.total }
    }
    val isDiffGroup = group.kind.equals(KIND_DIFF, ignoreCase = true)
    // 勾选集合是全局的（跨分组），这里只统计落在本组成员上的部分。
    val memberIds = remember(members) { members.map { it.id }.toSet() }
    val selectedInGroup = remember(selectedTrashIds, memberIds) {
        selectedTrashIds.count { it in memberIds }
    }
    val suggestedCount = assessment.trashIds.size

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 卡头：类型标签（重复 / 相似 / 差分）· 张数 · 可释放空间；右侧是用户当前勾选数，方便和底部按钮对账。
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GroupKindChip(kind = group.kind)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${group.memberCount} 张 · 可释放 ${formatBytes(group.reclaimableBytes)}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "已选 $selectedInGroup 张",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selectedInGroup > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (isDiffGroup) {
                // 差分图是「同一主体的不同版本」，每组通常想全部保留，所以单独说明一句。
                Text(
                    text = "差分图：同一主体的不同版本（p0/p1 之类），已从相似判定里排除，保留哪张由你决定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (ranked.isEmpty()) {
                // 成员还没加载好、或该组已被重新扫描掉：这里不能假设 scores 非空。
                Text(
                    text = "成员信息尚未加载或已失效，暂时无法对比。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ranked.take(2).forEachIndexed { index, item ->
                        MemberPane(
                            media = item.media,
                            score = item.score,
                            label = if (index == 0) "A" else "B",
                            isKeep = item.media.id == assessment.keepMediaId,
                            isSuggestedTrash = item.media.id in assessment.trashIds,
                            selected = item.media.id in selectedTrashIds,
                            onToggleTrash = { onToggleTrash(item.media.id) },
                            loadThumbnail = loadThumbnail,
                            onOpenViewer = { onOpenViewer(item.media) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 组内只有 1 张时补一个空位，保持 A/B 两栏对齐。
                    if (ranked.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                // 第三张起用小方块并排，横向可滚，避免成员多时把卡片撑坏。
                if (ranked.size > 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ranked.drop(2).forEach { item ->
                            ExtraMember(
                                media = item.media,
                                score = item.score,
                                isKeep = item.media.id == assessment.keepMediaId,
                                isSuggestedTrash = item.media.id in assessment.trashIds,
                                selected = item.media.id in selectedTrashIds,
                                onToggleTrash = { onToggleTrash(item.media.id) },
                                loadThumbnail = loadThumbnail,
                                onOpenViewer = { onOpenViewer(item.media) },
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // 判断依据：逐条展示评分器给出的中文理由，卡片不再自己编理由。
            if (assessment.rationale.isNotEmpty()) {
                Text(text = "判断依据", style = MaterialTheme.typography.titleSmall)
                assessment.rationale.forEach { reason ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (ranked.isNotEmpty()) {
                Text(
                    text = qualitySummaryText(ranked, assessment.confidence),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 置信度 = 前两名分差 / 30，差距小时提示用户别盲从建议。
                if (assessment.confidence < 0.15) {
                    Text(
                        text = "两张差距较小，建议先「对比预览」确认后再决定。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCompare) { Text("对比预览") }
                if (onCompareAll != null) {
                    TextButton(onClick = onCompareAll) { Text("全部对比") }
                }
                Spacer(modifier = Modifier.weight(1f))
                // v0.9：「保留本组」= 以后不再显示这一组，只写标记，不删除也不移动文件。
                TextButton(onClick = { onIgnoreGroup?.invoke() }, enabled = onIgnoreGroup != null) {
                    Text("保留本组")
                }
            }
            if (onIgnoreGroup != null) {
                Text(
                    text = "保留后这一组不再出现在清理列表，图片本身不动；可到清理页顶部「保留标记」里一键清除。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "点缩略图右上角勾选框可切换是否移入回收站",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 底部两个并排按钮：先把建议全部勾上，再一键应用当前勾选。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onSelectSuggested,
                    enabled = hasWriteAccess && suggestedCount > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("全选建议（$suggestedCount 张）", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    onClick = onApply,
                    enabled = hasWriteAccess && selectedInGroup > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("移入回收站（$selectedInGroup 张）", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!hasWriteAccess) {
                // 整行可点：进应用内的权限说明页（用户反馈过：别处的授权提示要能点）。
                WriteAccessHintInline(onOpenPermission = onOpenPermission)
            }
        }
    }
}

/** 汇总一行「综合评分 A 87.3 / B 61.2 · 置信度 82%」。 */
private fun qualitySummaryText(ranked: List<RankedMember>, confidence: Double): String {
    val labels = listOf("A", "B")
    val scores = ranked.take(2).mapIndexed { index, item ->
        "${labels[index]} ${formatScore(item.score.total)}"
    }
    val percent = (confidence.coerceIn(0.0, 1.0) * 100).roundToInt()
    return "综合评分 " + scores.joinToString(" / ") + " · 置信度 $percent%"
}

/** 评分统一保留 1 位小数。 */
private fun formatScore(score: Double): String = String.format(Locale.US, "%.1f", score)

/**
 * 叠在缩略图右上角的勾选框：勾上 = 加入待删（error 底 + 对勾），空框 = 不删。
 *
 * 没用 Material3 的 Checkbox，是因为它在 64dp 的小方块上最小触控区会把布局撑大；
 * 这里自绘一个方形勾选框，外观与语义都和 Checkbox 一致。
 */
@Composable
private fun SelectionBadge(
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onError
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(4.dp),
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
            .padding(6.dp)
            .size(24.dp)
            .clickable(onClick = onToggle),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "移出待删",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** A/B 两栏中的一栏：1:1 缩略图 + 建议徽标 + 勾选框 + 文件名 + 参数行。 */
@Composable
private fun MemberPane(
    media: MediaEntity,
    score: QualityScore,
    label: String,
    isKeep: Boolean,
    isSuggestedTrash: Boolean,
    selected: Boolean,
    onToggleTrash: () -> Unit,
    loadThumbnail: suspend (MediaEntity) -> File?,
    onOpenViewer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    // 边框优先表达「当前用户选择」：勾上待删就是 error 色；没勾但建议保留则是 primary 色。
    val borderColor = when {
        selected -> MaterialTheme.colorScheme.error
        isKeep -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val borderWidth = if (selected || isKeep) 2.dp else 1.dp

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .border(borderWidth, borderColor, shape)
                .clickable(onClick = onOpenViewer),
        ) {
            QualityThumbnail(
                media = media,
                loadThumbnail = loadThumbnail,
                modifier = Modifier.fillMaxSize(),
            )
            // 勾上待删时压一层半透明 error 蒙层，远看就能分清「会删 / 不会删」。
            if (selected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.16f)),
                )
            }
            // 左上角 A/B 标记，方便和判断依据里的「A / B」对上。
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            SelectionBadge(
                selected = selected,
                onToggle = onToggleTrash,
                modifier = Modifier.align(Alignment.TopEnd),
            )
            // 底部结论角标：建议保留 / 建议移入回收站。
            if (isKeep || isSuggestedTrash) {
                val container = if (isKeep) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
                val content = if (isKeep) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }
                Surface(
                    color = container,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                ) {
                    Text(
                        text = if (isKeep) "建议保留" else "建议移入回收站",
                        style = MaterialTheme.typography.labelSmall,
                        color = content,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }

        Text(
            text = media.displayName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // 参数行优先取评分因子的 label/value（像素、格式、压缩率、拍摄时间、文件名、清晰度），
        // 卡片不自己重算，避免和评分页显示不一致；体积不参与打分，单独补一行。
        if (score.factors.isEmpty()) {
            Text(
                text = formatBytes(media.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            score.factors.forEach { factor ->
                FactorRow(label = factor.label, value = factor.value)
            }
            FactorRow(label = "大小", value = formatBytes(media.sizeBytes))
        }
    }
}

/** 第三张起的小方块成员：缩略图 + 勾选框 + 一行状态。 */
@Composable
private fun ExtraMember(
    media: MediaEntity,
    score: QualityScore,
    isKeep: Boolean,
    isSuggestedTrash: Boolean,
    selected: Boolean,
    onToggleTrash: () -> Unit,
    loadThumbnail: suspend (MediaEntity) -> File?,
    onOpenViewer: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val borderColor = when {
        selected -> MaterialTheme.colorScheme.error
        isKeep -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val borderWidth = if (selected || isKeep) 2.dp else 1.dp

    Column(
        modifier = Modifier.width(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(shape)
                .border(borderWidth, borderColor, shape)
                .clickable(onClick = onOpenViewer),
        ) {
            QualityThumbnail(
                media = media,
                loadThumbnail = loadThumbnail,
                modifier = Modifier.fillMaxSize(),
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.16f)),
                )
            }
            SelectionBadge(
                selected = selected,
                onToggle = onToggleTrash,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        Text(
            text = formatScore(score.total),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        // 小方块放不下徽标，用一行文字表达「建议保留 / 待删」。
        Text(
            text = when {
                selected -> "待删"
                isKeep -> "保留"
                isSuggestedTrash -> "建议删"
                else -> "—"
            },
            style = MaterialTheme.typography.labelSmall,
            color = when {
                selected -> MaterialTheme.colorScheme.error
                isKeep -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

/** 一行参数：左侧标签、右侧取值。 */
@Composable
private fun FactorRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End,
            // 因子文案可能较长（如「1200 万像素（4000×3000）」），允许折成两行。
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** v0.9：分组类型标签，重复/相似用 secondaryContainer，差分组用 tertiaryContainer 区分。 */
@Composable
private fun GroupKindChip(kind: String) {
    val isDiff = kind.equals(KIND_DIFF, ignoreCase = true)
    Surface(
        color = if (isDiff) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (isDiff) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = groupKindLabel(kind),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * 差分组常量。DuplicateScanner 目前只有 KIND_EXACT / KIND_NEAR，
 * 差分组（KIND_DIFF）还在接入中，这里先用字符串兜住；常量落盘后替换成 DuplicateScanner.KIND_DIFF 即可。
 */
private const val KIND_DIFF = "DIFF"

/** 分组类型的中文标签；未知类型按「重复」处理，避免卡片头出现空白。 */
private fun groupKindLabel(kind: String): String = when (kind.uppercase(Locale.US)) {
    DuplicateScanner.KIND_EXACT -> "重复"
    DuplicateScanner.KIND_NEAR -> "相似"
    KIND_DIFF -> "差分"
    else -> "重复"
}

/** 优先用缩略图文件，取不到（或失败）时回退到原图 content Uri；加载中显示占位。 */
@Composable
private fun QualityThumbnail(
    media: MediaEntity,
    loadThumbnail: suspend (MediaEntity) -> File?,
    modifier: Modifier = Modifier,
) {
    val state by produceState<ThumbState>(initialValue = ThumbState.Loading, media.id, media.path) {
        value = try {
            loadThumbnail(media)?.let { ThumbState.Ready(it) } ?: ThumbState.Fallback
        } catch (cancelled: CancellationException) {
            // 页面回收时 produceState 会被取消，不能把取消当成加载失败吞掉。
            throw cancelled
        } catch (_: Exception) {
            // 缩略图只是展示，失败不该让整张卡片崩掉，直接回退原图。
            ThumbState.Fallback
        }
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (val current = state) {
            is ThumbState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )

            is ThumbState.Ready -> AsyncImage(
                model = current.file,
                contentDescription = media.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            is ThumbState.Fallback -> AsyncImage(
                model = MediaUris.of(media.id),
                contentDescription = media.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
