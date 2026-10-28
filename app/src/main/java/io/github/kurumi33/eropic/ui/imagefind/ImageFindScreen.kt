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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
 * 以图搜图页（M4 识图）。
 *
 * 两条独立能力：
 * 1. 本地识图：选一张源图 → ViewModel 计算/读取指纹并与库里的图片比对，结果通过
 *    [EropicViewModel.similarityHits] 推给页面；关键词输入防抖 400ms 后交给 ViewModel 重跑。
 * 2. 联网识图：把当前源图上传到所选服务商，成功后用浏览器打开结果页；失败用 Snackbar 提示。
 *
 * 本地比对完全在本机完成，不会上传任何图片；只有点联网服务商的 chip 才会触发上传。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImageFindScreen(
    viewModel: EropicViewModel,
    onBack: () -> Unit,
    onOpenViewer: (List<Long>, Int) -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hits by viewModel.similarityHits.collectAsStateWithLifecycle()
    val searching by viewModel.similaritySearching.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // 当前选中的源图；null 表示还在引导页。
    var source by remember { mutableStateOf(ImageFindSession.source) }
    // keywordInput 是输入框原文，appliedKeyword 是已经交给 ViewModel 的关键词，两者比较做防抖。
    var keywordInput by remember { mutableStateOf(ImageFindSession.keywordInput) }
    var appliedKeyword by remember { mutableStateOf(ImageFindSession.appliedKeyword) }
    var onlineLoading by remember { mutableStateOf<ReverseImageProvider?>(null) }
    var showCandidates by remember { mutableStateOf(false) }

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

    // 选好源图后触发一次本地检索；失败不崩溃，只提示。
    fun startLocalSearch(next: FindSource) {
        source = next
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
        onlineLoading = provider
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
                viewModel.postMessage("联网识图失败：请检查网络后重试")
                return@launch
            }
            val resultUrl = result.resultUrl
            if (result.ok && !resultUrl.isNullOrBlank()) {
                if (!openInBrowser(context, resultUrl)) {
                    viewModel.postMessage("没有可以打开该链接的浏览器")
                }
            } else {
                viewModel.postMessage(result.message ?: "该服务暂时不可用")
            }
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

    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            title = {
                Column {
                    Text(text = "以图搜图", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "本地相似图 + 联网识图",
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

        if (source == null) {
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
                Column(modifier = Modifier.fillMaxSize()) {
                    SourceSummary(
                        source = current,
                        onReselect = { resetSource() },
                    )

                    OutlinedTextField(
                        value = keywordInput,
                        onValueChange = { keywordInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        placeholder = { Text("按文件名/目录过滤（可选）") },
                        singleLine = true,
                        trailingIcon = {
                            if (keywordInput.isNotEmpty()) {
                                IconButton(onClick = { keywordInput = "" }) {
                                    Icon(imageVector = Icons.Filled.Close, contentDescription = "清除关键词")
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                focusManager.clearFocus()
                                applyKeywordNow(keywordInput)
                            },
                        ),
                    )

                    OnlineSearchSection(
                        onlineLoading = onlineLoading,
                        searching = searching,
                        onSearch = { provider -> launchOnlineSearch(provider) },
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        val loading = searching
                        when {
                            hits.isEmpty() && loading -> FindingState()
                            hits.isEmpty() -> EmptyResultState(onReselect = { resetSource() })
                            else -> Column(modifier = Modifier.fillMaxSize()) {
                                Text(
                                    text = "共 " + hits.size + " 张 · 阈值 dHash≤" + DHASH_MAX_DISTANCE,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                                )
                                if (loading) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                SimilarHitsGrid(
                                    hits = hits,
                                    columns = settings.gridColumns,
                                    thumbnailsEnabled = settings.thumbnailCacheEnabled,
                                    thumbnailProvider = hitThumbnail,
                                    onOpen = onOpenViewer,
                                )
                            }
                        }
                    }
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
            text = "以图搜图",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "选一张图片，找出图库里与它相似的本地照片；也可以把这张图上传到联网识图服务搜同款。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "本地比对完全在手机上完成，不会上传图片；只有点下方联网服务商才会把这张图发给对方。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** 联网识图服务商：一排 chip，同一时间只允许一个请求。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnlineSearchSection(
    onlineLoading: ReverseImageProvider?,
    searching: Boolean,
    onSearch: (ReverseImageProvider) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "联网识图",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Text(
            text = "把这张图上传到服务商，在浏览器里看结果。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ReverseImageProvider.entries.forEach { provider ->
                val isLoading = onlineLoading == provider
                FilterChip(
                    selected = isLoading,
                    enabled = onlineLoading == null && !searching,
                    onClick = { onSearch(provider) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(text = provider.label)
                        }
                    },
                )
            }
        }
    }
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
