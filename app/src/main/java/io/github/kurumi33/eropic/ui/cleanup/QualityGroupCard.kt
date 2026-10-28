package io.github.kurumi33.eropic.ui.cleanup

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.cleanup.GroupQualityAssessment
import io.github.kurumi33.eropic.core.cleanup.KeepCandidate
import io.github.kurumi33.eropic.core.cleanup.KeepStrategy
import io.github.kurumi33.eropic.core.cleanup.KeepSuggestion
import io.github.kurumi33.eropic.core.cleanup.QualityScore
import io.github.kurumi33.eropic.core.database.entity.DuplicateGroupEntity
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.ui.common.formatBytes
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
 * 真正删除由 [onApplyKeep] 的调用方执行（只会移入回收站，30 天内可恢复）。
 */
@Composable
fun QualityGroupCard(
    group: DuplicateGroupEntity,
    members: List<MediaEntity>,
    assessment: GroupQualityAssessment,
    loadThumbnail: suspend (MediaEntity) -> File?,
    onOpenViewer: (MediaEntity) -> Unit,
    onCompare: () -> Unit,
    onApplyKeep: (KeepSuggestion) -> Unit,
    hasWriteAccess: Boolean,
) {
    // 评分与成员按 id 对齐，再按总分降序：A/B 两栏取前两名，第三张起排成小方块。
    val ranked = remember(members, assessment) {
        assessment.scores
            .mapNotNull { score ->
                members.firstOrNull { it.id == score.mediaId }?.let { RankedMember(it, score) }
            }
            .sortedByDescending { it.score.total }
    }
    val kindLabel = if (group.kind.equals("EXACT", ignoreCase = true)) "精确重复" else "相似图片"
    var showStrategyDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 卡头：类型 · 张数 · 可释放空间
            Text(
                text = "$kindLabel · ${group.memberCount} 张 · 可释放 ${formatBytes(group.reclaimableBytes)}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

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
                            isTrash = item.media.id in assessment.trashIds,
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
                        text = "两张差距较小，建议先「对比预览」确认，或改用手动选择保留。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 底部操作：对比预览 / 手动选择保留；授权后才允许应用建议。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCompare) { Text("对比预览") }
                TextButton(
                    onClick = { showStrategyDialog = true },
                    enabled = members.isNotEmpty(),
                ) { Text("手动选择保留") }
            }
            Button(
                onClick = {
                    onApplyKeep(
                        KeepSuggestion(
                            keepIds = if (assessment.keepMediaId >= 0L) {
                                listOf(assessment.keepMediaId)
                            } else {
                                emptyList()
                            },
                            trashIds = assessment.trashIds,
                            reclaimableBytes = group.reclaimableBytes,
                            rationale = assessment.rationale,
                        ),
                    )
                },
                enabled = hasWriteAccess && assessment.trashIds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("按建议移入回收站（${assessment.trashIds.size} 张）")
            }
            if (!hasWriteAccess) {
                // 点这一行直接跳系统的「所有文件访问」授权页（用户反馈过：别处的授权提示要能点）。
                val context = androidx.compose.ui.platform.LocalContext.current
                Text(
                    text = "需要「所有文件访问」权限，点这里去授权",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(
                                io.github.kurumi33.eropic.core.permissions.StorageAccess.requestIntent(context),
                            )
                        }
                    },
                )
            }
        }
    }

    // 手动回退：让用户按体积/时间等规则自己挑保留哪一张，结果仍复用 onApplyKeep。
    if (showStrategyDialog) {
        KeepStrategyDialog(
            candidates = members.map {
                KeepCandidate(
                    id = it.id,
                    sizeBytes = it.sizeBytes,
                    dateTakenMs = it.dateTakenMs,
                    path = it.path,
                )
            },
            defaultStrategy = KeepStrategy.LARGEST,
            onDismiss = { showStrategyDialog = false },
            onApply = { suggestion ->
                showStrategyDialog = false
                onApplyKeep(suggestion)
            },
        )
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

/** A/B 两栏中的一栏：1:1 缩略图 + 文件名 + 参数行。 */
@Composable
private fun MemberPane(
    media: MediaEntity,
    score: QualityScore,
    label: String,
    isKeep: Boolean,
    isTrash: Boolean,
    loadThumbnail: suspend (MediaEntity) -> File?,
    onOpenViewer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val borderColor = when {
        isKeep -> MaterialTheme.colorScheme.primary
        isTrash -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val borderWidth = if (isKeep) 2.dp else 1.dp

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
            // 底部结论角标：建议保留 / 建议移入回收站。
            if (isKeep || isTrash) {
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
            // 体积不在评分因子里（不参与打分），但对「哪张更值得留」有参考价值，单独补一行。
            FactorRow(label = "大小", value = formatBytes(media.sizeBytes))
        }
    }
}

/** 第三张起的小方块成员。 */
@Composable
private fun ExtraMember(
    media: MediaEntity,
    score: QualityScore,
    isKeep: Boolean,
    loadThumbnail: suspend (MediaEntity) -> File?,
    onOpenViewer: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val borderColor = if (isKeep) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (isKeep) 2.dp else 1.dp

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
        }
        Text(
            text = formatScore(score.total),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
