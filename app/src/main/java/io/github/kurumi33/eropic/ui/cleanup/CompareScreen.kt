package io.github.kurumi33.eropic.ui.cleanup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.cleanup.GroupQualityAssessment
import io.github.kurumi33.eropic.core.cleanup.QualityFactor
import io.github.kurumi33.eropic.core.cleanup.QualityScore
import io.github.kurumi33.eropic.core.cleanup.QualityScorer
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.EropicTopBar
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatDate
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 重复 / 相似 / 差分分组的对比预览（v0.9 重写）。
 *
 * 用户反馈「只有左右两张图，遇到多张相似的不够用」，所以这里改成：
 * - 组内全部成员横向分页，左右滑动逐张看，不再固定 A/B 两栏；
 * - 图片下方一排缩略图做快捷跳转，当前页高亮；
 * - 参数区跟着当前页切换，展示这张图的体积/分辨率/格式/bpp/拍摄时间/路径，
 *   以及 QualityScorer 给出的评分因子与「本组建议保留」结论；
 * - 底部「用查看器打开」看原图，「保留本组」把这个标记交给调用方处理（不删任何文件）。
 *
 * 只读：本页不做任何删除或移动，评分与清理页共用同一套 QualityScorer。
 *
 * @param onIgnoreGroup 「保留本组」回调，为 null 时按钮禁用（旧调用方不传也能编译）。
 */
@Composable
fun CompareScreen(
    viewModel: EropicViewModel,
    groupId: Long,
    onClose: () -> Unit,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
    onIgnoreGroup: (() -> Unit)? = null,
) {
    // loaded 用来区分「还在查」和「查完是空」：被重新扫描掉的组不能一直转圈。
    var loaded by remember(groupId) { mutableStateOf(false) }
    val members by produceState(initialValue = emptyList<MediaEntity>(), groupId) {
        // 取消（离开页面）必须原样抛出，不能当成「查不到成员」吞掉。
        value = try {
            viewModel.members(groupId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        loaded = true
    }
    // 成员为 0 时 pager 暂时没有页面，加载完再显示；因此页码依赖 members.size。
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { members.size })
    val lastIndex = members.lastIndex
    val safePage = if (lastIndex >= 0) pagerState.currentPage.coerceIn(0, lastIndex) else 0

    // 评分是纯计算，成员不变就不重算；与清理页卡片口径一致。
    val assessment = remember(members) { QualityScorer.assess(members) }
    val scoreById = remember(assessment) { assessment.scores.associateBy { it.mediaId } }
    val keepName = remember(members, assessment) {
        members.firstOrNull { it.id == assessment.keepMediaId }?.displayName
    }

    // 返回键先退出对比，再交给外层。
    BackHandler(onBack = onClose)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .navigationBarsPadding(),
    ) {
        EropicTopBar(
            title = "对比预览",
            subtitle = if (members.isEmpty()) {
                null
            } else {
                "第 ${safePage + 1} / ${members.size} 张 · 左右滑动切换"
            },
            onBack = onClose,
        )

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
            return@Column
        }

        val current = members[safePage]
        val memberIds = remember(members) { members.map { it.id } }
        val scope = rememberCoroutineScope()

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().height(300.dp),
            key = { page -> members[page].id },
        ) { page ->
            ComparePage(
                media = members[page],
                isKeep = members[page].id == assessment.keepMediaId,
                loadThumbnail = { viewModel.thumbnailFile(it) },
                onClick = { onOpenViewer(memberIds, page) },
            )
        }

        // 缩略图快捷跳转：56dp 小图，当前页加 primary 描边。
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(members, key = { _, item -> item.id }) { index, item ->
                CompareThumb(
                    media = item,
                    selected = index == safePage,
                    loadThumbnail = { viewModel.thumbnailFile(it) },
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                )
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

        // 参数区：跟着当前页切换，超出屏幕时可以上下滚，避免小屏上把底部按钮顶没。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CurrentMediaDetails(
                media = current,
                score = scoreById[current.id],
                assessment = assessment,
                keepName = keepName,
            )
        }

        // 底部动作：看原图 / 保留本组（只写标记，不碰文件）。
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (onIgnoreGroup != null) {
                Text(
                    text = "「保留本组」只是把这个标记保存下来，图片不会被删除，也不会移动。",
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onOpenViewer(memberIds, safePage) },
                    modifier = Modifier.weight(1f),
                ) { Text("用查看器打开") }
                Button(
                    onClick = { onIgnoreGroup?.invoke() },
                    enabled = onIgnoreGroup != null,
                    modifier = Modifier.weight(1f),
                ) { Text("保留本组") }
            }
        }
    }
}

/** 单页大图：黑色背景按 contain 显示，点击进查看器；建议保留的那张带角标。 */
@Composable
private fun ComparePage(
    media: MediaEntity,
    isKeep: Boolean,
    loadThumbnail: suspend (MediaEntity) -> File?,
    onClick: () -> Unit,
) {
    val thumb by produceState<File?>(initialValue = null, media.id, media.path) {
        value = try {
            loadThumbnail(media)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    Box(
        modifier = Modifier.fillMaxSize().clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = thumb?.let { android.net.Uri.fromFile(it) } ?: MediaUris.of(media.id),
            contentDescription = media.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (isKeep) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            ) {
                Text(
                    text = "评分建议保留",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** 底部缩略图条里的一格。 */
@Composable
private fun CompareThumb(
    media: MediaEntity,
    selected: Boolean,
    loadThumbnail: suspend (MediaEntity) -> File?,
    onClick: () -> Unit,
) {
    val thumb by produceState<File?>(initialValue = null, media.id, media.path) {
        value = try {
            loadThumbnail(media)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(shape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.25f),
                shape = shape,
            )
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = thumb?.let { android.net.Uri.fromFile(it) } ?: MediaUris.of(media.id),
            contentDescription = media.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 当前页的参数与评分依据。 */
@Composable
private fun CurrentMediaDetails(
    media: MediaEntity,
    score: QualityScore?,
    assessment: GroupQualityAssessment,
    keepName: String?,
) {
    val dim = Color.White.copy(alpha = 0.72f)
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = media.displayName,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${media.width}×${media.height}",
            color = dim,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    Text(
        text = metaLine(media),
        color = dim,
        style = MaterialTheme.typography.labelSmall,
    )
    Text(
        text = if (media.dateTakenMs > 0L) "拍摄 ${formatDate(media.dateTakenMs)}" else "拍摄时间未知",
        color = dim,
        style = MaterialTheme.typography.labelSmall,
    )
    Text(
        text = media.relativePath.ifBlank { media.path },
        color = Color.White.copy(alpha = 0.55f),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = Color.White.copy(alpha = 0.12f),
    )

    if (score == null) {
        Text(
            text = "这张图还没有评分资料，保留哪张由你决定。",
            color = dim,
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text(text = "评分依据", color = Color.White, style = MaterialTheme.typography.titleSmall)
        score.factors.forEach { factor ->
            FactorLine(
                factor = factor,
                tone = factorTone(factor, assessment.scores),
            )
        }
        Text(
            text = "总分 ${formatTotal(score.total)}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 2.dp),
        )
    }

    if (keepName != null) {
        Text(
            text = "本组评分建议保留：$keepName",
            color = dim,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    if (assessment.keepMediaId == media.id) {
        Text(
            text = "评分建议保留这张",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** 一行评分因子：标签 + 取值（相对本组是加分还是扣分用主题色区分），note 单独一行小字。 */
@Composable
private fun FactorLine(factor: QualityFactor, tone: Color?) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = factor.label,
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = factor.value,
            color = tone ?: Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    val note = factor.note
    if (!note.isNullOrBlank()) {
        Text(
            text = note,
            color = Color.White.copy(alpha = 0.45f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * 因子的相对颜色：比本组同因子平均分高 = 加分项（primary），低 = 扣分项（error）。
 *
 * 不用绝对分（比如 60）判断，是因为不同因子的分数量纲不同（像素 vs 文件名），
 * 只有放在同组里横向比才有意义。
 */
@Composable
private fun factorTone(factor: QualityFactor, scores: List<QualityScore>): Color? {
    val peers = scores.mapNotNull { score ->
        score.factors.firstOrNull { it.label == factor.label }?.score
    }
    if (peers.size < 2) return null
    val average = peers.average()
    return when {
        factor.score > average + 0.5 -> MaterialTheme.colorScheme.primary
        factor.score < average - 0.5 -> MaterialTheme.colorScheme.error
        else -> null
    }
}

/** 「1.2 MB · JPG · bpp 1.85」。 */
private fun metaLine(media: MediaEntity): String {
    val format = media.displayName.substringAfterLast('.', "")
        .takeIf { it.isNotBlank() }
        ?: media.mimeType.substringAfter('/', "").takeIf { it.isNotBlank() }
        ?: "未知"
    val bpp = if (media.width > 0 && media.height > 0) {
        String.format(Locale.US, "%.2f", media.sizeBytes * 8.0 / (media.width.toLong() * media.height))
    } else {
        "—"
    }
    return "${formatBytes(media.sizeBytes)} · ${format.uppercase(Locale.US)} · bpp $bpp"
}

/** 评分统一保留两位小数。 */
private fun formatTotal(score: Double): String = String.format(Locale.US, "%.2f", score)
