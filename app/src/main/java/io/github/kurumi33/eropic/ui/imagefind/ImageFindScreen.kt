package io.github.kurumi33.eropic.ui.imagefind

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.core.search.OnlineSearchResult
import io.github.kurumi33.eropic.core.search.ReverseImageProvider
import io.github.kurumi33.eropic.core.search.SimilarImageHit
import io.github.kurumi33.eropic.ui.EropicViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** 关键词输入停止多久后才真正重新检索：太短会频繁重算指纹比对，太长显得卡顿。 */
private const val KEYWORD_DEBOUNCE_MS = 400L

/** 本地相似判定的 dHash 距离上限，与 SimilarityQuery 的默认值保持一致；顶部统计条直接展示给用户。 */
private const val DHASH_MAX_DISTANCE = 16

/** 顶部两个 Tab：两块功能各自独立，共用同一张源图。 */
private val FIND_TABS: List<String> = listOf("本地识图", "联网识图")

/**
 * 联网识图只保留这 4 家：Google Lens / Yandex / SauceNAO / ascii2d。
 * 这里显式列出来而不是用 ReverseImageProvider.entries，方便以后枚举里再增删而不影响界面顺序。
 */
private val ONLINE_PROVIDERS: List<ReverseImageProvider> = listOf(
    ReverseImageProvider.GOOGLE_LENS,
    ReverseImageProvider.YANDEX,
    ReverseImageProvider.SAUCENAO,
    ReverseImageProvider.ASCII2D,
)

/**
 * 本地识图的源图：要么是「从设备选图」拿到的 content Uri，要么是「从图库选」里的一张已索引图片。
 *
 * 只有展示需要的信息才会被记住；真正的指纹与比对都在 ViewModel/仓库里做。
 */
private sealed interface FindSource {
    data class DeviceUri(val uri: Uri) : FindSource

    data class LibraryMedia(val mediaId: Long, val displayName: String) : FindSource
}

/** 「从图库选」候选图的加载状态：空目录和还在加载要给出不同提示。 */
private sealed interface CandidateState {
    data object Idle : CandidateState

    data object Loading : CandidateState

    data class Loaded(val items: List<MediaEntity>) : CandidateState
}

/**
 * 页面级会话状态：EropicRoot 打开查看器时会把承载页整体移出组合，普通 remember 会丢，
 * 回到页面就只剩空引导页。识图流程几乎必然要「点结果进查看器再返回」，所以把源图与关键词
 * 放在这个进程内单例里；进程被杀时本来就会重新开始，不持久化到磁盘。
 */
private object ImageFindSession {
    var source: FindSource? = null
    var keywordInput: String = ""
    var appliedKeyword: String = ""
}

/**
 * 识图页（v0.3 拆分版）。
 *
 * 顶部用两个 Tab 把两种能力彻底分开，两块内容各自独立滚动，不混排：
 * 1. 本地识图：选一张源图 → ViewModel 计算/读取指纹并与库里的图片比对，结果通过
 *    [EropicViewModel.similarityHits] 推给页面；关键词输入防抖 400ms 后交给 ViewModel 重跑。
 * 2. 联网识图：把当前源图上传到所选服务商，成功后用浏览器打开结果页，失败就地提示。
 *
 * 两块共用同一张源图（[ImageFindSession] 保存源图与关键词，因为打开查看器会把整页移出组合）。
 * 本地比对完全在本机完成，不会上传任何图片；只有「联网识图」里点服务商才会触发上传。
 *
 * [onBack] 为 null 表示这是底部导航的一级页面（不显示返回箭头），非 null 时显示返回。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageFindScreen(
    viewModel: EropicViewModel,
    onBack: (() -> Unit)? = null,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hits by viewModel.similarityHits.collectAsStateWithLifecycle()
    val searching by viewModel.similaritySearching.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // 当前选中的源图；null 表示还在引导页。状态放在文件级会话对象里：
    // 打开查看器时 EropicRoot 会把整页移出组合，普通 remember 会丢，回到页面时源图就没了。
    var source by remember { mutableStateOf(ImageFindSession.source) }
    // keywordInput 是输入框原文，appliedKeyword 是已经交给 ViewModel 的关键词，两者比较做防抖。
    var keywordInput by remember { mutableStateOf(ImageFindSession.keywordInput) }
    var appliedKeyword by remember { mutableStateOf(ImageFindSession.appliedKeyword) }
    var onlineLoading by remember { mutableStateOf<ReverseImageProvider?>(null) }
    var onlineNotice by remember { mutableStateOf<OnlineNotice?>(null) }
    var showCandidates by remember { mutableStateOf(false) }
    var showSauceNaoDialog by remember { mutableStateOf(false) }
    // 0 = 本地识图，1 = 联网识图。
    var selectedTab by remember { mutableStateOf(0) }

    // 同步到会话对象，保证从查看器返回（本页被销毁重组）后还能接着看结果。
    LaunchedEffect(source) { ImageFindSession.source = source }
    LaunchedEffect(keywordInput) { ImageFindSession.keywordInput = keywordInput }
    LaunchedEffect(appliedKeyword) { ImageFindSession.appliedKeyword = appliedKeyword }

    // 结果格与候选格共用缩略图缓存；hit 只有 mediaId，先取回实体再走缓存。
    // 这里刻意不套 remember：suspend lambda 放在 remember 的计算块里时，Kotlin 推不出它的
    // 期望类型（会报 "Unknown return lambda parameter type"），而这两个 lambda 只捕获 viewModel，
    // 每次重组重建一个的开销可以忽略。
    val hitThumbnail: suspend (Long) -> File? = { mediaId: Long ->
        viewModel.mediaById(mediaId)?.let { entity -> viewModel.thumbnailFile(entity) }
    }
    val entityThumbnail: suspend (MediaEntity) -> File? = { entity: MediaEntity ->
        viewModel.thumbnailFile(entity)
    }

    // 候选图只在展开时才查，避免每次进页面都扫一遍最近图片。
    val candidates by produceState<CandidateState>(CandidateState.Idle, showCandidates) {
        if (!showCandidates) {
            value = CandidateState.Idle
            return@produceState
        }
        value = CandidateState.Loading
        value = CandidateState.Loaded(
            runCatching { viewModel.searchSourceCandidates() }.getOrDefault(emptyList()),
        )
    }

    // 清掉源图回到引导页；关键词保留，方便换一张图继续用同一个过滤词。
    fun resetSource() {
        source = null
        showCandidates = false
        onlineNotice = null
    }

    // 把关键词立刻交给 ViewModel（内部会按上次源图自动重跑检索）。
    fun applyKeywordNow(text: String) {
        val trimmed = text.trim()
        if (trimmed == appliedKeyword) return
        appliedKeyword = trimmed
        scope.launch {
            try {
                viewModel.setSimilarityKeyword(trimmed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel.postMessage("应用关键词失败：" + (e.message ?: e::class.simpleName))
            }
        }
    }

    // 选好源图后触发一次本地检索；失败不崩溃，只提示。联网识图也复用这张源图。
    fun startLocalSearch(next: FindSource) {
        source = next
        onlineNotice = null
        val trimmed = keywordInput.trim()
        val keywordChanged = trimmed != appliedKeyword
        appliedKeyword = trimmed
        // 这两个方法只是「登记源图 + 触发一次后台检索」，结果统一走 similarityHits 状态流。
        if (keywordChanged) viewModel.setSimilarityKeyword(trimmed)
        when (next) {
            is FindSource.DeviceUri -> viewModel.findSimilarByUri(next.uri)
            is FindSource.LibraryMedia -> viewModel.findSimilarByMediaId(next.mediaId)
        }
    }

    // 联网识图：上传当前源图 → 打开结果页。同一时间只允许一个请求，避免连点重复上传。
    fun launchOnlineSearch(provider: ReverseImageProvider) {
        if (onlineLoading != null) return
        if (source == null) {
            viewModel.postMessage("请先选择一张源图")
            return
        }
        // SauceNAO 需要 API Key：没配置时直接引导去设置/官网，而不是等上传失败再报「暂未支持」。
        if (provider == ReverseImageProvider.SAUCENAO && !viewModel.sauceNaoApiKeyConfigured()) {
            showSauceNaoDialog = true
            return
        }
        onlineLoading = provider
        onlineNotice = null
        scope.launch {
            val result: OnlineSearchResult? = try {
                viewModel.onlineImageSearch(provider)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            onlineLoading = null
            if (result == null) {
                onlineNotice = OnlineNotice(false, "联网识图失败：请检查网络后重试")
                return@launch
            }
            val resultUrl = result.resultUrl
            if (result.ok && !resultUrl.isNullOrBlank()) {
                if (openInBrowser(context, resultUrl)) {
                    onlineNotice = OnlineNotice(true, "已在浏览器打开 " + provider.label + " 的结果页")
                } else {
                    onlineNotice = OnlineNotice(false, "没有可以打开该链接的浏览器")
                }
            } else {
                onlineNotice = OnlineNotice(false, result.message ?: "该服务暂时不可用")
            }
        }
    }

    // 上传失败或没配置 Key 时的兜底：直接在浏览器打开服务商官网手动传图。
    fun openProviderHome(provider: ReverseImageProvider) {
        if (!openInBrowser(context, provider.homeUrl)) {
            viewModel.postMessage("没有可以打开该链接的浏览器")
        }
    }

    // 设备选图：系统照片选择器，只允许选图片。
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) startLocalSearch(FindSource.DeviceUri(uri))
    }

    // 关键词 400ms 防抖：输入停顿后才让 ViewModel 重新比对。
    LaunchedEffect(keywordInput, source) {
        if (source == null) return@LaunchedEffect
        val trimmed = keywordInput.trim()
        if (trimmed == appliedKeyword) return@LaunchedEffect
        delay(KEYWORD_DEBOUNCE_MS)
        if (trimmed == appliedKeyword) return@LaunchedEffect
        applyKeywordNow(trimmed)
    }

    // onBack 为 null 表示这是底部 Tab 的一级页面，交给系统默认返回即可。
    BackHandler(enabled = onBack != null) { onBack?.invoke() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            title = {
                Column {
                    Text(text = "识图", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "本地相似图 · 联网以图搜图",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                if (source != null) {
                    IconButton(onClick = { resetSource() }) {
                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = "换一张源图")
                    }
                }
            },
        )

        // 两块功能分开：顶部 Tab 切换，下面各自独立滚动，不混在同一个列表里。
        TabRow(selectedTabIndex = selectedTab) {
            FIND_TABS.forEachIndexed { index, label ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(label) },
                )
            }
        }

        if (source == null) {
            // 还没选源图：两个 Tab 共用同一段引导，选好后自动切到当前 Tab 的内容。
            SourceGuidance(
                candidates = candidates,
                showCandidates = showCandidates,
                onPickFromDevice = {
                    pickImageLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onToggleCandidates = { showCandidates = !showCandidates },
                onPickCandidate = { entity ->
                    showCandidates = false
                    startLocalSearch(FindSource.LibraryMedia(entity.id, entity.displayName))
                },
                entityThumbnail = entityThumbnail,
                thumbnailsEnabled = settings.thumbnailCacheEnabled,
            )
        } else {
            val current = source
            if (current != null) {
                SourceSummary(
                    source = current,
                    onReselect = { resetSource() },
                )
                when (selectedTab) {
                    0 -> LocalFindTab(
                        modifier = Modifier.weight(1f),
                        hits = hits,
                        searching = searching,
                        columns = settings.gridColumns,
                        thumbnailsEnabled = settings.thumbnailCacheEnabled,
                        thumbnailProvider = hitThumbnail,
                        keywordInput = keywordInput,
                        onKeywordChange = { keywordInput = it },
                        onKeywordSubmit = {
                            focusManager.clearFocus()
                            applyKeywordNow(keywordInput)
                        },
                        onOpenViewer = onOpenViewer,
                        onReselect = { resetSource() },
                    )
                    else -> OnlineFindTab(
                        modifier = Modifier.weight(1f),
                        onlineLoading = onlineLoading,
                        notice = onlineNotice,
                        sauceNaoConfigured = viewModel.sauceNaoApiKeyConfigured(),
                        onSearch = { provider -> launchOnlineSearch(provider) },
                        onOpenHome = { provider -> openProviderHome(provider) },
                    )
                }
            }
        }
    }

    // SauceNAO 没有配置 Key 时的说明框：给出注册官网入口，而不是让人去猜。
    if (showSauceNaoDialog) {
        SauceNaoKeyDialog(
            onDismiss = { showSauceNaoDialog = false },
            onOpenHome = {
                showSauceNaoDialog = false
                openInBrowser(context, ReverseImageProvider.SAUCENAO.homeUrl)
            },
        )
    }
}

/** 「本地识图」Tab：关键词过滤 + 相似结果网格，内容独立滚动。 */
@Composable
private fun LocalFindTab(
    modifier: Modifier = Modifier,
    hits: List<SimilarImageHit>,
    searching: Boolean,
    columns: Int,
    thumbnailsEnabled: Boolean,
    thumbnailProvider: suspend (Long) -> File?,
    keywordInput: String,
    onKeywordChange: (String) -> Unit,
    onKeywordSubmit: () -> Unit,
    onOpenViewer: (List<Long>, Int) -> Unit,
    onReselect: () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = keywordInput,
            onValueChange = onKeywordChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("按文件名/目录过滤（可选）") },
            singleLine = true,
            trailingIcon = {
                if (keywordInput.isNotEmpty()) {
                    IconButton(onClick = { onKeywordChange("") }) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = "清除关键词")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onKeywordSubmit() }),
        )

        Box(modifier = Modifier.weight(1f)) {
            when {
                hits.isEmpty() && searching -> FindingState()
                hits.isEmpty() -> EmptyResultState(onReselect = onReselect)
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "共 " + hits.size + " 张 · 阈值 dHash≤" + DHASH_MAX_DISTANCE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                    )
                    if (searching) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    SimilarHitsGrid(
                        hits = hits,
                        columns = columns,
                        thumbnailsEnabled = thumbnailsEnabled,
                        thumbnailProvider = thumbnailProvider,
                        onOpen = onOpenViewer,
                    )
                }
            }
        }
    }
}

/** 引导态：还没选源图时看到的内容。 */
@Composable
private fun SourceGuidance(
    candidates: CandidateState,
    showCandidates: Boolean,
    onPickFromDevice: () -> Unit,
    onToggleCandidates: () -> Unit,
    onPickCandidate: (MediaEntity) -> Unit,
    entityThumbnail: suspend (MediaEntity) -> File?,
    thumbnailsEnabled: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Icon(
            imageVector = Icons.Outlined.ImageSearch,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Text(
            text = "选择源图",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "选一张图片作为源图：「本地识图」找出图库里与它相似的照片，「联网识图」把这张图上传到服务商搜同款。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "本地比对完全在手机上完成，不会上传图片；只有「联网识图」里点服务商才会把这张图发给对方。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = "两个 Tab 共用这张源图，选好后可随时在顶部切换。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )

        Button(
            onClick = onPickFromDevice,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
        ) {
            Icon(imageVector = Icons.Outlined.AddPhotoAlternate, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("从设备选图")
        }
        OutlinedButton(
            onClick = onToggleCandidates,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Icon(imageVector = Icons.Outlined.PhotoLibrary, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (showCandidates) "收起图库图片" else "从图库选")
        }

        if (showCandidates) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                when (val state = candidates) {
                    is CandidateState.Loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                    is CandidateState.Loaded -> {
                        if (state.items.isEmpty()) {
                            Text(
                                text = "图库里还没有图片，先回到图库建立索引后再来选。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                            )
                        } else {
                            CandidateGrid(
                                items = state.items,
                                thumbnailsEnabled = thumbnailsEnabled,
                                thumbnailProvider = entityThumbnail,
                                onPick = onPickCandidate,
                            )
                        }
                    }

                    is CandidateState.Idle -> Unit
                }
            }
        }
    }
}

/** 已选源图的摘要行：缩略图 + 简易来源说明 + 换图入口。 */
@Composable
private fun SourceSummary(
    source: FindSource,
    onReselect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val preview: Any = when (source) {
            is FindSource.DeviceUri -> source.uri
            is FindSource.LibraryMedia -> MediaUris.of(source.mediaId)
        }
        AsyncImage(
            model = preview,
            contentDescription = "当前源图",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = when (source) {
                    is FindSource.DeviceUri -> "设备选图"
                    is FindSource.LibraryMedia -> source.displayName
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when (source) {
                    is FindSource.DeviceUri -> "本地比对不上传；联网识图会上传这张图"
                    is FindSource.LibraryMedia -> "来自图库的最近图片"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onReselect) { Text("换一张") }
    }
}

/** 联网识图最近一次的结果提示：成功用主色，失败用错误色。 */
private data class OnlineNotice(val ok: Boolean, val text: String)

/** 「联网识图」Tab：4 个服务商按钮 + 最近一次结果提示；整块独立滚动。 */
@Composable
private fun OnlineFindTab(
    modifier: Modifier = Modifier,
    onlineLoading: ReverseImageProvider?,
    notice: OnlineNotice?,
    sauceNaoConfigured: Boolean,
    onSearch: (ReverseImageProvider) -> Unit,
    onOpenHome: (ReverseImageProvider) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "选择服务商",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Text(
            text = "把当前源图上传到服务商，在浏览器里看结果。上传意味着这张图会离开本机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )

        if (onlineLoading != null) {
            Text(
                text = "正在上传到 " + onlineLoading.label + "…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp),
            )
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }

        val currentNotice = notice
        if (currentNotice != null) {
            Text(
                text = currentNotice.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (currentNotice.ok) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        ONLINE_PROVIDERS.forEach { provider ->
            ProviderRow(
                provider = provider,
                enabled = onlineLoading == null,
                loading = onlineLoading == provider,
                needsKey = provider == ReverseImageProvider.SAUCENAO && !sauceNaoConfigured,
                onSearch = { onSearch(provider) },
                onOpenHome = { onOpenHome(provider) },
            )
        }

        Text(
            text = "识别不出结果时，可以点「官网」手动上传原图再试；SauceNAO 需要先在「设置 → 联网识图」填 API Key。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

/** 单个服务商：名称 + 状态说明 + 「官网」兜底入口 + 识图按钮。 */
@Composable
private fun ProviderRow(
    provider: ReverseImageProvider,
    enabled: Boolean,
    loading: Boolean,
    needsKey: Boolean,
    onSearch: () -> Unit,
    onOpenHome: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = provider.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = when {
                        needsKey -> "需要 API Key（免费注册）"
                        loading -> "正在上传…"
                        else -> "上传这张图，在浏览器看结果"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onOpenHome, enabled = !loading) {
                Icon(
                    imageVector = Icons.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("官网")
            }
            Spacer(modifier = Modifier.width(4.dp))
            Button(onClick = onSearch, enabled = enabled && !loading) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (needsKey) "填 Key" else "识图")
                }
            }
        }
    }
}

/** SauceNAO 未配置 API Key 时的说明框：告诉用户去哪里填，并给出官网入口。 */
@Composable
private fun SauceNaoKeyDialog(
    onDismiss: () -> Unit,
    onOpenHome: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SauceNAO 需要 API Key") },
        text = {
            Text("SauceNAO 需要在「设置 → 联网识图」填 API Key（免费注册）。注册后在账号页复制 API Key，粘贴到设置里即可。")
        },
        confirmButton = {
            TextButton(onClick = onOpenHome) { Text("打开官网") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

/** 结果网格：点击任意结果打开查看器，并带上整个结果集的 id，方便左右翻看。 */
@Composable
private fun SimilarHitsGrid(
    hits: List<SimilarImageHit>,
    columns: Int,
    thumbnailsEnabled: Boolean,
    thumbnailProvider: suspend (Long) -> File?,
    onOpen: (List<Long>, Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(hits, key = { _, hit -> hit.mediaId }) { index, hit ->
            SimilarHitCell(
                hit = hit,
                thumbnailsEnabled = thumbnailsEnabled,
                thumbnailProvider = thumbnailProvider,
                onClick = {
                    // 传整个结果集：查看器里可以一直翻到下一张相似图，而不是只翻当前屏幕。
                    onOpen(hits.map { it.mediaId }, index)
                },
            )
        }
    }
}

/** 单张相似结果：缩略图 + 左下角相似度百分比。 */
@Composable
private fun SimilarHitCell(
    hit: SimilarImageHit,
    thumbnailsEnabled: Boolean,
    thumbnailProvider: suspend (Long) -> File?,
    onClick: () -> Unit,
) {
    val model by produceState<Any>(
        MediaUris.of(hit.mediaId),
        hit.mediaId,
        thumbnailsEnabled,
    ) {
        value = if (thumbnailsEnabled) {
            runCatching { thumbnailProvider(hit.mediaId) }
                .getOrNull()
                ?.let { Uri.fromFile(it) }
                ?: MediaUris.of(hit.mediaId)
        } else {
            // 关闭缩略图缓存时直接读系统图片 Uri，避免额外占磁盘。
            MediaUris.of(hit.mediaId)
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
            contentDescription = hit.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = String.format(Locale.US, "%.1f%%", hit.similarity),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** 「从图库选」的候选小网格：固定高度，避免和图库其余部分互相抢滚动。 */
@Composable
private fun CandidateGrid(
    items: List<MediaEntity>,
    thumbnailsEnabled: Boolean,
    thumbnailProvider: suspend (MediaEntity) -> File?,
    onPick: (MediaEntity) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
            CandidateCell(
                item = item,
                thumbnailsEnabled = thumbnailsEnabled,
                thumbnailProvider = thumbnailProvider,
                onClick = { onPick(item) },
            )
        }
    }
}

@Composable
private fun CandidateCell(
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

/** 本地比对中：说明在做什么，避免用户以为卡死。 */
@Composable
private fun FindingState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = "正在计算指纹并和本地图库比对…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/** 空结果：给出可操作的下一步，而不是只留一句"没有结果"。 */
@Composable
private fun EmptyResultState(onReselect: () -> Unit) {
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
            text = "没有找到足够相似的图片，换个关键词或放宽阈值",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "也可以换一张更清晰、主体更完整的源图再试。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        TextButton(onClick = onReselect, modifier = Modifier.padding(top = 8.dp)) {
            Text("换一张源图")
        }
    }
}

/** 用外部浏览器打开联网识图的结果页；没有可用浏览器时返回 false。 */
private fun openInBrowser(context: Context, url: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}.isSuccess
