package io.github.kurumi33.eropic.ui.cleanup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.analysis.DuplicateScanner
import io.github.kurumi33.eropic.core.cleanup.GroupQualityAssessment
import io.github.kurumi33.eropic.core.cleanup.QualityScorer
import io.github.kurumi33.eropic.core.database.entity.DuplicateGroupEntity
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.fileops.FileOpReport
import io.github.kurumi33.eropic.core.index.IndexPhase
import io.github.kurumi33.eropic.core.index.IndexState
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.core.permissions.StorageAccess
import io.github.kurumi33.eropic.ui.AppScreen
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.EropicTopBar
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount
import io.github.kurumi33.eropic.ui.common.formatMediaSubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 清理页的八个入口。前两个来自查重分组，其余来自检测器。 */
enum class CleanupCategory(val label: String) {
    DUPLICATES("重复"),
    SIMILAR("相似"),
    LARGE("大文件"),
    OLD("久远"),
    SCREENSHOT("截图"),
    BLURRY("模糊"),
    BURST("连拍"),
    LARGE_IMAGE("大图"),
}

/** 当前分类要展示的内容。 */
private sealed interface CleanupContent {
    data object Loading : CleanupContent
    data class Grouped(val groups: List<DuplicateGroupEntity>) : CleanupContent
    data class Detected(val items: List<MediaEntity>, val summary: String) : CleanupContent
}

@Composable
fun CleanupScreen(
    viewModel: EropicViewModel,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
    hasWriteAccess: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val indexState by viewModel.indexState.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()

    var category by rememberSaveable { mutableStateOf(CleanupCategory.DUPLICATES) }
    var filterState by remember { mutableStateOf(FilterUiState()) }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    // 非空表示筛选结果页正在最上层。
    var filteredState by remember { mutableStateOf<FilterUiState?>(null) }
    var compareGroupId by rememberSaveable { mutableStateOf(-1L) }
    var refreshToken by remember { mutableIntStateOf(0) }

    filteredState?.let { applied ->
        FilteredScreen(
            viewModel = viewModel,
            filterState = applied,
            onBack = { filteredState = null },
            onOpenViewer = onOpenViewer,
            // 筛选结果页同样要做写操作（移动/回收站/重命名/复制），必须把权限传下去，
            // 否则已授权用户进到这里会发现四个按钮全是灰的。
            hasWriteAccess = hasWriteAccess,
        )
        return
    }
    if (compareGroupId > 0L) {
        CompareScreen(
            viewModel = viewModel,
            groupId = compareGroupId,
            onClose = { compareGroupId = -1L },
            onOpenViewer = onOpenViewer,
        )
        return
    }
    if (showFilter) {
        FilterSheet(
            initial = filterState,
            onDismiss = { showFilter = false },
            onApply = {
                filterState = it
                showFilter = false
                if (!it.isEmpty) filteredState = it
            },
        )
    }

    val content by produceState<CleanupContent>(
        initialValue = CleanupContent.Loading,
        category,
        refreshToken,
        groups,
    ) {
        value = CleanupContent.Loading
        value = runCatching {
            when (category) {
                CleanupCategory.DUPLICATES -> CleanupContent.Grouped(
                    groups.filter { it.kind == DuplicateScanner.KIND_EXACT },
                )

                CleanupCategory.SIMILAR -> CleanupContent.Grouped(
                    groups.filter { it.kind == DuplicateScanner.KIND_NEAR },
                )

                CleanupCategory.LARGE -> {
                    val result = viewModel.largeFiles()
                    CleanupContent.Detected(
                        items = result.items,
                        summary = "共 ${formatCount(result.hitCount)} 张 ≥ 5 MB，合计 ${formatBytes(result.reclaimableBytes)}",
                    )
                }

                CleanupCategory.OLD -> CleanupContent.Detected(
                    items = viewModel.oldPhotos(),
                    summary = "拍摄时间超过 3 年、且未收藏的图片（按时间从旧到新）",
                )

                CleanupCategory.SCREENSHOT -> CleanupContent.Detected(
                    items = viewModel.screenshots(),
                    summary = "疑似截图，最多列出 2000 张候选：命中截图目录、屏幕分辨率或图标/文字特征",
                )

                CleanupCategory.BLURRY -> CleanupContent.Detected(
                    items = viewModel.blurred(),
                    summary = "清晰度低于阈值（Laplacian 方差 < 100，已完成指纹计算）",
                )

                CleanupCategory.BURST -> {
                    val result = viewModel.bursts()
                    CleanupContent.Detected(
                        items = result.groups.flatMap { it.members },
                        summary = "疑似连拍：${result.groups.size} 组 · ${formatCount(result.hitCount.toLong())} 张 · " +
                            "每组留一张可释放约 ${formatBytes(result.reclaimableBytes)}" +
                            "（同目录、5 秒内、连续 3 张以上）",
                    )
                }

                CleanupCategory.LARGE_IMAGE -> {
                    val items = viewModel.largeImages()
                    CleanupContent.Detected(
                        items = items,
                        summary = "大图：按像素从大到小排列，共 ${formatCount(items.size.toLong())} 张。" +
                            "像素大不等于占空间大，确认无用再清理。",
                    )
                }
            }
        }.getOrElse { error ->
            CleanupContent.Detected(
                items = emptyList(),
                summary = "读取失败：${error.message ?: error::class.simpleName}",
            )
        }
    }

    // 分组类的成员与评分统一在这里加载：卡片只负责渲染，勾选/汇总/全局删除都基于同一份数据。
    val groupedGroups = (content as? CleanupContent.Grouped)?.groups.orEmpty()
    val memberMap by produceState<Map<Long, List<MediaEntity>>>(
        initialValue = emptyMap(),
        groupedGroups,
        refreshToken,
    ) {
        value = groupedGroups.associate { group ->
            // 单组读取失败不该让整个列表崩掉，只是这一组暂时没有成员。
            group.groupId to runCatching { viewModel.members(group.groupId) }.getOrDefault(emptyList())
        }
    }
    val assessmentMap = remember(memberMap) {
        memberMap.mapValues { (_, members) -> QualityScorer.assess(members) }
    }
    // 默认勾选：评分器建议移入回收站的那些。用户手动点过的记录在 overrides 里覆盖默认值。
    val suggestedIds = remember(assessmentMap) {
        assessmentMap.values.flatMap { it.trashIds }.toSet()
    }
    val selectionOverrides = remember { mutableStateMapOf<Long, Boolean>() }
    // 已加载的成员 id：刷新后消失的图片不应再算进「已选」，否则计数和实际删除会不一致。
    val knownIds = remember(memberMap) {
        memberMap.values.flatten().mapTo(mutableSetOf()) { it.id }
    }
    val selectedIds: Set<Long> = buildSet {
        suggestedIds.forEach { id -> if (selectionOverrides[id] != false) add(id) }
        selectionOverrides.forEach { (id, selected) -> if (selected && id in knownIds) add(id) }
    }
    val memberSizes = remember(memberMap) {
        memberMap.values.flatten().associate { it.id to it.sizeBytes }
    }
    val selectedBytes = selectedIds.sumOf { memberSizes[it] ?: 0L }

    // 勾选只改本地状态；真正删除统一走 applyTrash，保证「报告 + 跳转 + 清空勾选 + 刷新」一致。
    fun toggleTrash(id: Long) {
        selectionOverrides[id] = id !in selectedIds
    }

    fun applyTrash(ids: List<Long>) {
        if (ids.isEmpty()) return
        scope.launch {
            val result = viewModel.moveToTrash(ids)
            val report = FileOpReport(
                type = "TRASH",
                title = "移入回收站",
                requested = ids.size,
                succeeded = result.trashed,
                failed = result.failed,
                bytes = result.bytes,
                failures = result.failures,
            )
            viewModel.publishReport(report)
            viewModel.openScreen(AppScreen.Report(report))
            // 索引行已被删除，清空勾选并重新加载成员，避免继续显示已删的图片。
            selectionOverrides.clear()
            refreshToken++
        }
    }

    // 顶栏副标题：整页「几类问题 / 一共能释放多少」的概览。
    // 统计口径与「清理报告」一致（同一批检测器），但这里只要类别数与总字节；
    // 索引还没跑完或某一类读取失败时留空，副标题空着也不影响页面使用。
    val overview by produceState<CleanupOverview?>(
        initialValue = null,
        indexState.phase,
        groups.size,
        refreshToken,
    ) {
        value = runCatching { loadCleanupOverview(viewModel, groups) }.getOrNull()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        EropicTopBar(
            title = "清理",
            subtitle = overview?.let {
                "${it.categoryCount} 类问题 · 可释放 ${formatBytes(it.bytes)}"
            },
            actions = {
                IconButton(onClick = { viewModel.openScreen(AppScreen.Trash) }) {
                    Icon(imageVector = Icons.Filled.Delete, contentDescription = "回收站")
                }
                TextButton(onClick = { viewModel.openScreen(AppScreen.CleanupReport) }) {
                    Text("清理报告")
                }
                TextButton(onClick = { showFilter = true }) {
                    Text(if (filterState.isEmpty) "组合筛选" else "筛选 · 已设置")
                }
                IconButton(onClick = { showFilter = true }) {
                    Icon(imageVector = Icons.Filled.Tune, contentDescription = "组合筛选")
                }
            },
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(CleanupCategory.entries.toList(), key = { it.name }) { item ->
                FilterChip(
                    selected = category == item,
                    onClick = { category = item },
                    label = { Text(item.label) },
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { IndexCard(indexState, onStart = viewModel::startIndex, onCancel = viewModel::cancelIndex) }

            when (val current = content) {
                CleanupContent.Loading -> item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }

                is CleanupContent.Grouped -> {
                    item {
                        ContentHeader(
                            summary = when (category) {
                                CleanupCategory.DUPLICATES ->
                                    if (current.groups.isEmpty()) "还没有精确重复分组。" else
                                        "精确重复：文件内容（SHA-256）完全一致的图片，默认按可释放空间排序。"

                                else ->
                                    if (current.groups.isEmpty()) "还没有相似图片分组。" else
                                        "相似图片：dHash 与 pHash 双重距离阈值内的图片，不一定真的相同，需逐组确认。"
                            },
                            onRefresh = { refreshToken++ },
                        )
                    }
                    if (current.groups.isEmpty()) {
                        item { EmptyHint() }
                    } else {
                        // 全局汇总条：跨分组显示总勾选数，并提供一键全选建议 / 一键删除。
                        item {
                            SelectionSummaryBar(
                                selectedCount = selectedIds.size,
                                selectedBytes = selectedBytes,
                                hasWriteAccess = hasWriteAccess,
                                onSelectAllSuggested = { selectionOverrides.clear() },
                                onApply = { applyTrash(selectedIds.toList()) },
                            )
                        }
                        items(current.groups, key = { it.groupId }) { group ->
                            GroupItem(
                                group = group,
                                members = memberMap[group.groupId].orEmpty(),
                                assessment = assessmentMap[group.groupId]
                                    ?: QualityScorer.assess(emptyList()),
                                selectedIds = selectedIds,
                                loadThumbnail = viewModel::thumbnailFile,
                                hasWriteAccess = hasWriteAccess,
                                onToggleTrash = { toggleTrash(it) },
                                onSelectSuggested = { trashIds ->
                                    trashIds.forEach { selectionOverrides[it] = true }
                                },
                                onApply = { ids -> applyTrash(ids) },
                                onOpenViewer = onOpenViewer,
                                onCompare = { compareGroupId = group.groupId },
                            )
                        }
                    }
                }

                is CleanupContent.Detected -> {
                    item {
                        ContentHeader(
                            summary = current.summary,
                            onRefresh = { refreshToken++ },
                        )
                    }
                    if (current.items.isEmpty()) {
                        item { EmptyHint() }
                    } else {
                        val ids = current.items.map { it.id }
                        itemsIndexed(current.items, key = { _, item -> item.id }) { index, item ->
                            MediaListRow(
                                media = item,
                                loadThumbnail = viewModel::thumbnailFile,
                                onClick = { onOpenViewer(ids, index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 全局汇总条：显示当前跨分组勾选了多少张、预计释放多少空间。
 * 放在列表里而不是固定悬浮，是为了不遮挡最后一张卡片，也不用处理嵌套滑动手势。
 */
@Composable
private fun SelectionSummaryBar(
    selectedCount: Int,
    selectedBytes: Long,
    hasWriteAccess: Boolean,
    onSelectAllSuggested: () -> Unit,
    onApply: () -> Unit,
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "已选 $selectedCount 张",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (selectedCount > 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        text = if (selectedCount > 0) {
                            "预计释放 ${formatBytes(selectedBytes)}（移入回收站，30 天内可恢复）"
                        } else {
                            "点缩略图右上角勾选框勾选，或点下面「全部按建议勾选」。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onSelectAllSuggested,
                    modifier = Modifier.weight(1f),
                ) { Text("全部按建议勾选", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Button(
                    onClick = onApply,
                    enabled = hasWriteAccess && selectedCount > 0,
                    modifier = Modifier.weight(1f),
                ) { Text("移入回收站", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (!hasWriteAccess) {
                Text(
                    text = "需要「所有文件访问」权限才能执行删除，点这里去授权",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable {
                        runCatching { context.startActivity(StorageAccess.requestIntent(context)) }
                    },
                )
            }
        }
    }
}

@Composable
private fun ContentHeader(summary: String, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRefresh) { Text("刷新") }
    }
}

@Composable
private fun EmptyHint() {
    Text(
        text = "没有符合条件的图片。索引完成后重新刷新再看看。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(8.dp),
    )
}

@Composable
private fun IndexCard(indexState: IndexState, onStart: () -> Unit, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "索引与查重", style = MaterialTheme.typography.titleMedium)
            Text(
                text = when (indexState.phase) {
                    IndexPhase.IDLE -> "尚未运行。建立索引会读取系统相册元数据，并在后台计算图片指纹。"
                    IndexPhase.SYNCING -> "正在读取系统相册：${indexState.scanned} 条"
                    IndexPhase.HASHING -> "正在计算指纹：${indexState.signatureDone}/${indexState.signatureTotal}"
                    IndexPhase.DEDUPING -> indexState.message
                    IndexPhase.DONE -> "完成：精确重复 ${indexState.exactGroups} 组，相似 ${indexState.nearGroups} 组，可释放约 ${formatBytes(indexState.reclaimableBytes)}"
                    IndexPhase.ERROR -> indexState.message
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 只读声明压缩成一行小注：原来独立一张卡太占首屏，但这句话必须还能看到。
            Text(
                text = "只做检索：不会自动删除；删除会先移入回收站，30 天内可恢复。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (indexState.isRunning) {
                val total = indexState.signatureTotal
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = {
                        if (indexState.phase == IndexPhase.HASHING && total > 0) {
                            (indexState.signatureDone.toFloat() / total).coerceIn(0f, 1f)
                        } else 0f
                    },
                )
                OutlinedButton(onClick = onCancel) { Text("取消") }
            } else {
                Button(onClick = onStart) {
                    Text(if (indexState.phase == IndexPhase.DONE) "重新扫描" else "开始扫描")
                }
            }
        }
    }
}

/**
 * 分组项的数据壳：成员与评分由 CleanupScreen 统一加载后传入，这里只把
 * 「本组已选集合」和「本组建议」翻译成卡片需要的回调，不嵌套任何列表。
 */
@Composable
private fun GroupItem(
    group: DuplicateGroupEntity,
    members: List<MediaEntity>,
    assessment: GroupQualityAssessment,
    selectedIds: Set<Long>,
    loadThumbnail: suspend (MediaEntity) -> File?,
    hasWriteAccess: Boolean,
    onToggleTrash: (Long) -> Unit,
    onSelectSuggested: (List<Long>) -> Unit,
    onApply: (List<Long>) -> Unit,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
    onCompare: () -> Unit,
) {
    val memberIds = remember(members) { members.map { it.id } }
    val groupSelected = remember(selectedIds, memberIds) {
        memberIds.filter { it in selectedIds }
    }
    QualityGroupCard(
        group = group,
        members = members,
        assessment = assessment,
        loadThumbnail = loadThumbnail,
        selectedTrashIds = selectedIds,
        onToggleTrash = onToggleTrash,
        onSelectSuggested = { onSelectSuggested(assessment.trashIds) },
        onApply = { onApply(groupSelected) },
        onOpenViewer = { media ->
            val index = memberIds.indexOf(media.id).coerceAtLeast(0)
            onOpenViewer(memberIds, index)
        },
        onCompare = onCompare,
        hasWriteAccess = hasWriteAccess,
    )
}

@Composable
private fun MediaListRow(
    media: MediaEntity,
    loadThumbnail: suspend (MediaEntity) -> File?,
    onClick: () -> Unit,
) {
    val thumb by produceState<File?>(initialValue = null, media.id, media.sizeBytes) {
        value = loadThumbnail(media)
    }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AsyncImage(
                model = thumb?.let { android.net.Uri.fromFile(it) } ?: MediaUris.of(media.id),
                contentDescription = media.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = media.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatMediaSubtitle(
                        sizeBytes = media.sizeBytes,
                        width = media.width,
                        height = media.height,
                        dateTakenMs = media.dateTakenMs,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(modifier = Modifier.width(4.dp))
        }
    }
}

/** 顶栏副标题用的概览：有问题的类别数、这些类别估算能释放多少字节。 */
private data class CleanupOverview(val categoryCount: Int, val bytes: Long)

/**
 * 统计顶栏副标题。
 *
 * 和「清理报告」用的是同一批检测器，但这里不展示明细，只数「有命中的类别」和它们的
 * 可释放字节。旧图/截图/模糊三类没有现成的汇总查询，只能取回候选列表再累加，所以要
 * 放到 IO 线程上跑：页面上副标题先空着，加载完再出现，不阻塞首屏。
 *
 * 任何一类读取失败都按「没有命中」处理——副标题不该影响页面本身能不能用。
 */
private suspend fun loadCleanupOverview(
    viewModel: EropicViewModel,
    groups: List<DuplicateGroupEntity>,
): CleanupOverview = withContext(Dispatchers.IO) {
    var categoryCount = 0
    var bytes = 0L

    // 重复 / 相似：分组表里已经算好了每组可释放字节。
    for (kind in listOf(DuplicateScanner.KIND_EXACT, DuplicateScanner.KIND_NEAR)) {
        val matched = groups.filter { it.kind == kind }
        if (matched.isNotEmpty()) {
            categoryCount++
            bytes += matched.sumOf { it.reclaimableBytes }
        }
    }

    // 大文件：检测器直接给命中数（不受 Top N 截断影响）。
    runCatching { viewModel.largeFiles() }.getOrNull()?.let { result ->
        if (result.hitCount > 0L) {
            categoryCount++
            bytes += result.reclaimableBytes
        }
    }

    // 旧图 / 截图 / 模糊：没有汇总接口，取回候选列表自己算。
    val listQueries = listOf<suspend () -> List<MediaEntity>>(
        { viewModel.oldPhotos() },
        { viewModel.screenshots() },
        { viewModel.blurred() },
    )
    for (query in listQueries) {
        val items = runCatching { query() }.getOrNull()
        if (!items.isNullOrEmpty()) {
            categoryCount++
            bytes += items.sumOf { it.sizeBytes }
        }
    }

    // 连拍：命中数是 Int，可释放字节按「每组留一张」估算。
    runCatching { viewModel.bursts() }.getOrNull()?.let { result ->
        if (result.hitCount > 0) {
            categoryCount++
            bytes += result.reclaimableBytes
        }
    }

    // 大图按像素统计，命中数与字节由 summary 提供（不截断）。
    runCatching { viewModel.largeImageSummary() }.getOrNull()?.let { row ->
        if (row.hits > 0L) {
            categoryCount++
            bytes += row.bytes
        }
    }

    CleanupOverview(categoryCount = categoryCount, bytes = bytes)
}
