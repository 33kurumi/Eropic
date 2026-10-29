package io.github.kurumi33.eropic.ui.search

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.database.entity.SavedFilterEntity
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.core.query.MediaFilter
import io.github.kurumi33.eropic.core.query.SavedFilterCodec
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.EropicTopBar
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File

/** 输入停止多久后才真正查询；太短会频繁打数据库，太长会显得卡顿。 */
private const val SEARCH_DEBOUNCE_MS = 300L

/** 搜索结果捏合列数的下限：3 列还能看清缩略图，再少就不像搜索网格了。 */
private const val MIN_SEARCH_COLUMNS = 3

/** 捏合列数上限，与设置里的网格列数上限（8）保持一致。 */
private const val MAX_SEARCH_COLUMNS = 8

/** 搜索历史最多展示多少条：再多就把快捷入口挤没了，超出的仍然留在 ViewModel 里。 */
private const val MAX_HISTORY_CHIPS = 20

/**
 * 语法提示里要展示的半角双引号。
 *
 * 直接写进 Kotlin 字符串需要转义，抽成常量既不容易写错，也顺便说明真正生效的语法字符是半角双引号。
 */
private const val QUOTE_MARK = "\""

/** 搜索命中的总张数与总大小（分页只给当前页，不能拿 itemCount 当总数）。 */
private data class SearchStats(val count: Long, val bytes: Long)

/**
 * 图片搜索页（M4 A-11 + B-17）。
 *
 * 搜索复用组合筛选的 nameLike（文件名与目录模糊匹配，不区分大小写），输入 300ms 防抖后才订阅
 * 分页数据，避免每敲一个字符就重新查询。搜索框为空时展示「搜索历史」「保存的筛选」「常用目录」三类快捷入口，
 * 搜索框下方常驻一块语法提示（规则 + 例子），因为只写符号说明用户看不懂。
 *
 * 点击图片时用 searchIds 取整个结果集的 id 列表再打开查看器，保证查看器里能左右翻完全部搜索结果；
 * 而不是只翻当前已加载的分页。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    viewModel: EropicViewModel,
    onBack: () -> Unit,
    onOpenViewer: (List<Long>, Int) -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val savedFilters by viewModel.savedFilters.collectAsStateWithLifecycle()
    // 搜索历史由 ViewModel 持久化（最多 50 条，最新在前），这里只负责展示与增删。
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // input 是输入框里的原始文本，query 是防抖后真正用于查询的关键词。
    var input by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var stats by remember { mutableStateOf<SearchStats?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    // 双指捏合临时改的列数：只影响本次浏览，不回写设置，离开页面就恢复。
    var columnOverride by remember { mutableStateOf<Int?>(null) }
    // 3 列是可用下限，8 列与设置里的上限一致。
    val columns = (columnOverride ?: settings.gridColumns).coerceIn(MIN_SEARCH_COLUMNS, MAX_SEARCH_COLUMNS)
    // 设置里改了列数就丢掉本页的临时覆盖，否则会看起来「设置没生效」。
    LaunchedEffect(settings.gridColumns) { columnOverride = null }

    // 常用目录只在进页面时取一次：图库变化后重进页面会刷新。
    val commonFolders by produceState<List<String>>(initialValue = emptyList()) {
        value = runCatching { viewModel.distinctFolders() }
            .getOrDefault(emptyList())
            .take(10)
    }

    // 300ms 防抖：输入变化就取消上一个等待中的查询。
    LaunchedEffect(input) {
        val trimmed = input.trim()
        if (trimmed == query) return@LaunchedEffect
        if (trimmed.isEmpty()) {
            // 清空输入立即回到快捷入口，不用等防抖。
            query = ""
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        query = trimmed
    }

    // 命中数量与总大小单独查（分页数量不等于总数）。
    LaunchedEffect(query) {
        // 记搜索历史放在这里而不是 onValueChange：query 只在防抖结束后（或键盘点搜索后）才变，
        // 所以每敲一个字不会产生一条历史。
        if (query.isNotBlank()) {
            runCatching { viewModel.recordSearch(query) }
        }
        stats = if (query.isBlank()) {
            null
        } else {
            runCatching {
                val filter = MediaFilter(nameLike = query)
                SearchStats(
                    count = viewModel.filterCount(filter),
                    bytes = viewModel.filterSumSize(filter),
                )
            }.getOrNull()
        }
    }

    // 进入页面自动聚焦搜索框；LaunchedEffect 保证节点已挂载，不会触发 FocusRequester 未初始化异常。
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    val items = remember(query) {
        if (query.isBlank()) {
            // 空搜索不订阅全库分页，省一次全表查询。
            flowOf(PagingData.empty<MediaEntity>())
        } else {
            viewModel.searchMedia(query)
        }
    }.collectAsLazyPagingItems()

    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize()) {
        // 统一顶栏：标题/副标题纯文本，搜索框放在顶栏下方一行，而不是塞进 TopAppBar 的标题里。
        EropicTopBar(
            title = "搜索",
            subtitle = null,
            onBack = onBack,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .focusRequester(focusRequester),
            placeholder = { Text("搜索文件名或目录") },
            singleLine = true,
            trailingIcon = {
                if (input.isNotEmpty()) {
                    IconButton(onClick = { input = "" }) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = "清除搜索")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    // 点键盘上的搜索直接生效，不必再等 300ms。
                    focusManager.clearFocus()
                    query = input.trim()
                },
            ),
        )

        // 语法提示常驻在搜索框下方：用户反馈原来顶栏那行符号说明看不懂，这里改成「规则 + 例子」两行小字。
        SearchSyntaxHint(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        )

        if (query.isBlank()) {
            SearchSuggestions(
                // 历史只在搜索框真的为空时给，避免输入过程中历史块一闪一闪。
                history = if (input.isEmpty()) searchHistory else emptyList(),
                onApplyHistory = { entry ->
                    input = entry
                    query = entry
                    focusManager.clearFocus()
                },
                onDeleteHistory = { entry -> viewModel.deleteSearchHistory(entry) },
                onRequestClearHistory = { showClearHistoryDialog = true },
                savedFilters = savedFilters,
                folders = commonFolders,
                onApplySavedFilter = { entity ->
                    val filter = runCatching { SavedFilterCodec.decode(entity.filterJson) }.getOrNull()
                    val keyword = filter?.nameLike?.takeIf { it.isNotBlank() }
                    if (keyword == null) {
                        // 其他页面保存的筛选可能只有大小/格式条件，搜索页无法还原成关键词。
                        viewModel.postMessage("「" + entity.name + "」没有关键词条件，无法在搜索页复用")
                    } else {
                        input = keyword
                        query = keyword
                        focusManager.clearFocus()
                    }
                },
                onDeleteSavedFilter = { entity ->
                    scope.launch {
                        runCatching { viewModel.deleteSavedFilter(entity.id) }
                            .onSuccess { viewModel.postMessage("已删除保存的筛选「" + entity.name + "」") }
                            .onFailure {
                                viewModel.postMessage("删除保存的筛选失败：" + (it.message ?: it::class.simpleName))
                            }
                    }
                },
                onApplyFolder = { path ->
                    val keyword = path.trimEnd('/')
                    if (keyword.isNotBlank()) {
                        input = keyword
                        query = keyword
                        focusManager.clearFocus()
                    }
                },
            )
        } else {
            SearchResults(
                query = query,
                stats = stats,
                items = items,
                columns = columns,
                onColumnsChange = { columnOverride = it },
                thumbnailsEnabled = settings.thumbnailCacheEnabled,
                thumbnailProvider = { item -> viewModel.thumbnailFile(item) },
                onSaveFilter = { showSaveDialog = true },
                onOpenItem = { item ->
                    scope.launch {
                        val ids = runCatching { viewModel.searchIds(query) }
                            .getOrDefault(emptyList<Long>())
                        if (ids.isEmpty()) {
                            viewModel.postMessage("这张图片已不在当前搜索结果里")
                        } else {
                            onOpenViewer(ids, ids.indexOf(item.id).coerceAtLeast(0))
                        }
                    }
                },
            )
        }
    }

    if (showSaveDialog) {
        SaveFilterDialog(
            defaultName = query,
            onDismiss = { showSaveDialog = false },
            onConfirm = { name ->
                showSaveDialog = false
                val filter = MediaFilter(nameLike = query.trim())
                scope.launch {
                    runCatching { viewModel.saveFilter(name, filter) }
                        .onSuccess { viewModel.postMessage("已保存筛选「" + name + "」") }
                        .onFailure {
                            viewModel.postMessage("保存筛选失败：" + (it.message ?: it::class.simpleName))
                        }
                }
            },
        )
    }

    // 清空历史前二次确认：历史不进回收站，误点就找不回来了。
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text(text = "清空搜索历史") },
            text = {
                Text(
                    text = "将删除全部 " + searchHistory.size +
                        " 条搜索历史。只清空历史记录，不影响图片本身。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearHistoryDialog = false
                        viewModel.clearSearchHistory()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(text = "清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text(text = "取消")
                }
            },
        )
    }
}

/**
 * 搜索框下方的语法提示。
 *
 * 原来的说明只是把符号罗列在顶栏副标题里，用户反馈看不懂，所以这里先给三条文字规则，再给三个例子。
 * 引号必须是半角双引号才生效，而 Kotlin 字符串里写半角双引号要转义，统一用 QUOTE_MARK 拼出来。
 */
@Composable
private fun SearchSyntaxHint(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = "输入规则：空格分开 = 同时包含 · 用双引号包住 = 整段连在一起 · 词前加减号 = 排除" +
                    "（旧的 | = 或者写法仍然可用）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "例：猫 2024 = 同时含「猫」和「2024」；" +
                    QUOTE_MARK + "我的 照片" + QUOTE_MARK + " = 连在一起的这段文字；" +
                    "猫 -截图 = 含「猫」但不含「截图」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 一条搜索历史：点气泡直接搜，点尾部 X 删除这一条。 */
@Composable
private fun SearchHistoryChip(
    entry: String,
    onApply: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    AssistChip(
        onClick = { onApply(entry) },
        label = {
            Text(text = entry, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        trailingIcon = {
            // 与保存筛选 chip 的删除图标一致：点击区域靠 clickable 扩大，图标本身保持 18dp。
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "删除这条搜索历史",
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onDelete(entry) },
            )
        },
    )
}

/** 搜索框为空时的快捷入口：搜索历史 + 保存的筛选 + 常用目录。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchSuggestions(
    history: List<String>,
    onApplyHistory: (String) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onRequestClearHistory: () -> Unit,
    savedFilters: List<SavedFilterEntity>,
    folders: List<String>,
    onApplySavedFilter: (SavedFilterEntity) -> Unit,
    onDeleteSavedFilter: (SavedFilterEntity) -> Unit,
    onApplyFolder: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 搜索历史放最上面：最近搜过什么通常比保存的筛选更常用；为空时整块不渲染。
        if (history.isNotEmpty()) {
            item {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "搜索历史",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRequestClearHistory) {
                            Text(text = "清空")
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        history.take(MAX_HISTORY_CHIPS).forEach { entry ->
                            SearchHistoryChip(
                                entry = entry,
                                onApply = onApplyHistory,
                                onDelete = onDeleteHistory,
                            )
                        }
                    }
                }
            }
        }

        item {
            Column {
                Text(text = "保存的筛选", style = MaterialTheme.typography.labelLarge)
                if (savedFilters.isEmpty()) {
                    Text(
                        text = "还没有保存的筛选：输入关键词后点「保存筛选」，下次一键复用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        savedFilters.forEach { entity ->
                            SavedFilterChip(
                                entity = entity,
                                onApply = onApplySavedFilter,
                                onDelete = onDeleteSavedFilter,
                            )
                        }
                    }
                }
            }
        }

        if (folders.isNotEmpty()) {
            item {
                Column {
                    Text(text = "常用目录", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = "点目录名直接按该路径搜索。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        folders.forEach { path ->
                            AssistChip(
                                onClick = { onApplyFolder(path) },
                                label = {
                                    Text(
                                        text = path.trimEnd('/'),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单条「保存的筛选」：点击套用，右侧叉号删除。 */
@Composable
private fun SavedFilterChip(
    entity: SavedFilterEntity,
    onApply: (SavedFilterEntity) -> Unit,
    onDelete: (SavedFilterEntity) -> Unit,
) {
    AssistChip(
        onClick = { onApply(entity) },
        label = {
            Text(text = entity.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Bookmark,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "删除保存的筛选「" + entity.name + "」",
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onDelete(entity) },
            )
        },
    )
}

/** 搜索结果区：统计条 + 网格 / 空态 / 加载态 / 错误态。 */
@Composable
private fun SearchResults(
    query: String,
    stats: SearchStats?,
    items: LazyPagingItems<MediaEntity>,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    thumbnailsEnabled: Boolean,
    thumbnailProvider: suspend (MediaEntity) -> File?,
    onSaveFilter: () -> Unit,
    onOpenItem: (MediaEntity) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "搜索「" + query + "」",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stats?.let { "命中 " + formatCount(it.count) + " 张 · " + formatBytes(it.bytes) }
                        ?: "正在统计…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onSaveFilter) { Text("保存筛选") }
        }

        val refresh = items.loadState.refresh
        when {
            items.itemCount == 0 && refresh is LoadState.Loading -> SearchLoadingState()
            items.itemCount == 0 && refresh is LoadState.Error -> SearchErrorState(
                message = refresh.error.message,
                onRetry = { items.retry() },
            )
            items.itemCount == 0 -> SearchEmptyState()
            else -> SearchResultGrid(
                items = items,
                columns = columns,
                onColumnsChange = onColumnsChange,
                thumbnailsEnabled = thumbnailsEnabled,
                thumbnailProvider = thumbnailProvider,
                onOpenItem = onOpenItem,
            )
        }
    }
}

/**
 * 搜索结果网格：单元格与图库一致，优先缩略图缓存、回退系统图片 URI。
 * 支持双指捏合临时改列数（3..8），只影响本次浏览，不同步到设置。
 */
@Composable
private fun SearchResultGrid(
    items: LazyPagingItems<MediaEntity>,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    thumbnailsEnabled: Boolean,
    thumbnailProvider: suspend (MediaEntity) -> File?,
    onOpenItem: (MediaEntity) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier
            .fillMaxSize()
            .pinchToChangeColumns(columns, MIN_SEARCH_COLUMNS, MAX_SEARCH_COLUMNS, onColumnsChange),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(count = items.itemCount) { index ->
            val item = items[index]
            if (item == null) {
                // 占位格：分页还没取到这一格。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                )
            } else {
                SearchResultCell(
                    item = item,
                    thumbnailsEnabled = thumbnailsEnabled,
                    thumbnailProvider = thumbnailProvider,
                    onClick = { onOpenItem(item) },
                )
            }
        }
    }
}

@Composable
private fun SearchResultCell(
    item: MediaEntity,
    thumbnailsEnabled: Boolean,
    thumbnailProvider: suspend (MediaEntity) -> File?,
    onClick: () -> Unit,
) {
    val model by produceState<Any>(
        MediaUris.of(item.id),
        item.id,
        thumbnailsEnabled,
    ) {
        value = if (thumbnailsEnabled) {
            runCatching { thumbnailProvider(item) }
                .getOrNull()
                ?.let { Uri.fromFile(it) }
                ?: MediaUris.of(item.id)
        } else {
            // 关闭缩略图缓存时直接用系统图片 URI，避免额外占磁盘。
            MediaUris.of(item.id)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = model,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SearchLoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun SearchEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.SearchOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = "没有找到匹配的图片",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "试试更短的关键词，或者直接搜目录名（例如 Camera）。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun SearchErrorState(message: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "搜索失败：" + (message ?: "未知错误"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) { Text("重试") }
    }
}

/**
 * 双指捏合切换网格列数（复制自 ui/components/MediaGrid.kt，只把上下限改成参数）。
 *
 * 只观察、不消费触摸事件：单指滑动仍然交给 LazyVerticalGrid 自己滚动，
 * 只有真的检测到双指缩放累积超过阈值时才回调一次，避免翻一张图就把列数跳乱。
 */
@Composable
private fun Modifier.pinchToChangeColumns(
    columns: Int,
    minColumns: Int,
    maxColumns: Int,
    onColumnsChange: (Int) -> Unit,
): Modifier {
    val latestColumns = rememberUpdatedState(columns)
    val latestOnChange = rememberUpdatedState(onColumnsChange)
    return pointerInput(Unit) {
        var accumulated = 1f
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val pressedCount = event.changes.count { it.pressed }
                if (pressedCount >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom.isFinite() && zoom > 0f && zoom != 1f) {
                        accumulated *= zoom
                        val current = latestColumns.value
                        if (accumulated > 1.3f && current > minColumns) {
                            latestOnChange.value(current - 1)
                            accumulated = 1f
                        } else if (accumulated < 0.7f && current < maxColumns) {
                            latestOnChange.value(current + 1)
                            accumulated = 1f
                        }
                    }
                } else {
                    // 手指减少时重置累积，避免一次捏合被算成连续多次。
                    accumulated = 1f
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

/** 保存当前筛选：输入名称后写入 saved_filter 表。 */
@Composable
private fun SaveFilterDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存当前筛选") },
        text = {
            Column {
                Text(
                    text = "保存后可在搜索框下方一键复用；当前只保存关键词条件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    label = { Text("筛选名称") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
