package io.github.kurumi33.eropic.ui.imagefind

import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.core.search.CharacterMatchResult
import io.github.kurumi33.eropic.core.search.OnlineSearchResult
import io.github.kurumi33.eropic.core.search.ReverseImageProvider
import io.github.kurumi33.eropic.core.search.SimilarImageHit
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.EropicTopBar
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

/** 结果网格捏合列数的下限：识图结果是大图，2 列也有意义（图库网格最低 3 列）。 */
private const val MIN_FIND_COLUMNS = 2

/** 捏合列数上限，与设置里的网格列数上限（8）保持一致。 */
private const val MAX_FIND_COLUMNS = 8

/** 「同角色候选」最多展示几个角色名按钮：取前 5 个最可信的，多了卡片会被挤爆。 */
private const val MAX_CHARACTER_CHIPS = 5

/**
 * 联网识图的服务商清单直接取枚举自身的顺序（v0.7 起 7 家）：
 * GOOGLE_LENS、BAIDU、SAUCENAO、ASCII2D、IQDB、ONEGAI、LENSO。
 *
 * 不再在 UI 里写死列表：枚举顺序本身就是「界面顺序」的唯一来源（见 core/search/SearchModels.kt），
 * 以后增删服务商只改枚举，界面自动跟上，也不会出现两边顺序对不上的情况。
 * v0.6 去过 Yandex（结果页依赖服务端 302、稳定性最差），v0.7 又加了 onegai.moe 与 lenso.ai。
 */

/**
 * 页面级会话状态：EropicRoot 打开查看器时会把承载页整体移出组合，普通 remember 会丢，
 * 回到页面就只剩空引导页。识图流程几乎必然要「点结果进查看器再返回」，所以把源图与关键词
 * 放在这个进程内单例里；进程被杀时本来就会重新开始，不持久化到磁盘。
 *
 * v1.0：源图只剩一种来源（选择器给出的 content Uri），因此[ImageFindSession] 里直接存 Uri。
 */
private object ImageFindSession {
    var sourceUri: Uri? = null
    var keywordInput: String = ""
    var appliedKeyword: String = ""

    /** 最近一次联网识图的结果：点「打开」可能跳到浏览器，回来时要还看得到列表。 */
    var onlineResult: OnlineSearchResult? = null

    /** 最近一次「找同角色」的本地候选：点进查看器再返回时同样要保住。 */
    var characterResult: CharacterMatchResult? = null
}

/**
 * 识图页（v0.3 拆分版）。
 *
 * 顶部用两个 Tab 把两种能力彻底分开，两块内容各自独立滚动，不混排：
 * 1. 本地识图：选一张源图 → ViewModel 计算/读取指纹并与库里的图片比对，结果通过
 *    [EropicViewModel.similarityHits] 推给页面；关键词输入防抖 400ms 后交给 ViewModel 重跑。
 *    另外有一个「找同角色」按钮：点它才让 ViewModel 调识别服务（可能联网）去找同角色候选。
 * 2. 联网识图：把当前源图上传到 7 家服务商之一，结果（结果页 / 条目列表 / 失败原因）
 *    直接显示在页面内的「联网结果」区块，需要时再点「在浏览器打开」。
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
    var sourceUri by remember { mutableStateOf(ImageFindSession.sourceUri) }
    // keywordInput 是输入框原文，appliedKeyword 是已经交给 ViewModel 的关键词，两者比较做防抖。
    var keywordInput by remember { mutableStateOf(ImageFindSession.keywordInput) }
    var appliedKeyword by remember { mutableStateOf(ImageFindSession.appliedKeyword) }
    var onlineLoading by remember { mutableStateOf<ReverseImageProvider?>(null) }
    // 最近一次联网识图的结果；上传本身抛异常（无网等）时才用 onlineError 兜底。
    var onlineResult by remember { mutableStateOf(ImageFindSession.onlineResult) }
    var onlineError by remember { mutableStateOf<String?>(null) }
    // 「找同角色」：点按钮才触发（要调识别服务、可能联网），不能随选图自动跑。
    var characterLoading by remember { mutableStateOf(false) }
    var characterResult by remember { mutableStateOf(ImageFindSession.characterResult) }
    // 是否正在用 App 内的源图选择器挑图（v1.0 起取代系统照片选择器）。
    var showDevicePicker by remember { mutableStateOf(false) }
    // 0 = 本地识图，1 = 联网识图。
    var selectedTab by remember { mutableStateOf(0) }
    // 双指捏合临时改的列数：只影响本次浏览，不回写设置，离开页面就恢复。
    var columnOverride by remember { mutableStateOf<Int?>(null) }
    // 识图结果是大图，2 列也有意义，所以下限用 2。
    val columns = (columnOverride ?: settings.gridColumns).coerceIn(MIN_FIND_COLUMNS, MAX_FIND_COLUMNS)
    // 设置里改了列数就丢掉本页的临时覆盖，否则会看起来「设置没生效」。
    LaunchedEffect(settings.gridColumns) { columnOverride = null }

    // 同步到会话对象，保证从查看器返回（本页被销毁重组）后还能接着看结果。
    LaunchedEffect(sourceUri) { ImageFindSession.sourceUri = sourceUri }
    LaunchedEffect(keywordInput) { ImageFindSession.keywordInput = keywordInput }
    LaunchedEffect(appliedKeyword) { ImageFindSession.appliedKeyword = appliedKeyword }

    // 结果格共用缩略图缓存；hit 只有 mediaId，先取回实体再走缓存。
    // 这里刻意不套 remember：suspend lambda 放在 remember 的计算块里时，Kotlin 推不出它的
    // 期望类型（会报 "Unknown return lambda parameter type"），而这个 lambda 只捕获 viewModel，
    // 每次重组重建一个的开销可以忽略。
    val hitThumbnail: suspend (Long) -> File? = { mediaId: Long ->
        viewModel.mediaById(mediaId)?.let { entity -> viewModel.thumbnailFile(entity) }
    }

    // 清掉源图回到引导页；关键词保留，方便换一张图继续用同一个过滤词。
    fun resetSource() {
        sourceUri = null
        onlineResult = null
        onlineError = null
        characterResult = null
        ImageFindSession.onlineResult = null
        ImageFindSession.characterResult = null
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
    // v1.0 源图只有一种来源（content Uri），所以参数直接是 Uri，不再有分支。
    fun startLocalSearch(nextUri: Uri) {
        sourceUri = nextUri
        onlineResult = null
        onlineError = null
        characterResult = null
        ImageFindSession.onlineResult = null
        ImageFindSession.characterResult = null
        val trimmed = keywordInput.trim()
        val keywordChanged = trimmed != appliedKeyword
        appliedKeyword = trimmed
        // 这两个方法只是「登记源图 + 触发一次后台检索」，结果统一走 similarityHits 状态流。
        if (keywordChanged) viewModel.setSimilarityKeyword(trimmed)
        viewModel.findSimilarByUri(nextUri)
    }

    // 联网识图：上传当前源图 → 把「结果页 / 条目列表 / 失败原因」交给「联网结果」区块。
    // v0.7 起不再一成功就自动弹浏览器：SauceNAO、ascii2d、IQDB 只返回条目，
    // 得先在 App 内列表里挑一条，再点「打开」或「在浏览器打开」。
    // 同一时间只允许一个请求，避免连点重复上传。
    fun launchOnlineSearch(provider: ReverseImageProvider) {
        if (onlineLoading != null) return
        if (sourceUri == null) {
            viewModel.postMessage("请先选择一张源图")
            return
        }
        onlineLoading = provider
        onlineError = null
        onlineResult = null
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
                onlineError = "联网识图失败：请检查网络后重试"
                return@launch
            }
            onlineError = null
            onlineResult = result
            ImageFindSession.onlineResult = result
        }
    }

    // 「找同角色」：user 点按钮才触发。识别服务要上传源图、慢且可能花配额，所以不做自动检索；
    // 结果放在 characterResult 里，网格复用相似结果的实现，点击沿用 onOpenViewer。
    // [clearPrevious] = true 时先清空旧结果（按钮第一次点击）；
    // 点角色名再搜时传 false，保留旧卡片直到新结果回来，页面不会闪一下。
    fun findCharacterMatches(clearPrevious: Boolean = true) {
        val currentUri = sourceUri ?: return
        if (characterLoading) return
        characterLoading = true
        if (clearPrevious) characterResult = null
        scope.launch {
            val result: CharacterMatchResult? = try {
                viewModel.findCharacterMatchesByUri(currentUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            characterLoading = false
            if (result == null) {
                viewModel.postMessage("找同角色失败：请检查网络后重试")
                return@launch
            }
            characterResult = result
            ImageFindSession.characterResult = result
        }
    }

    // 「同角色候选」里点一个角色名：把它当关键词再搜一次。
    // 复用页面现有的两条管线——关键词交给 ViewModel（内部按上次源图自动重跑本地相似），
    // 再触发一次同角色识别让候选也按这个名字重新过滤；不另建搜索逻辑。
    fun searchByCharacterName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (characterLoading) return
        keywordInput = trimmed
        appliedKeyword = trimmed
        focusManager.clearFocus()
        // setSimilarityKeyword 是同步方法（先记关键词再触发重跑），所以下一行的同角色检索
        // 一定能读到新关键词，不需要等协程。
        viewModel.setSimilarityKeyword(trimmed)
        findCharacterMatches(clearPrevious = false)
    }

    // 统一的外部浏览器入口：打不开就提示，不让页面崩。
    fun openUrl(url: String) {
        if (!openInBrowser(context, url)) {
            viewModel.postMessage("没有可以打开该链接的浏览器")
        }
    }

    // 上传失败时的兜底：直接在浏览器打开服务商官网手动传图。
    fun openProviderHome(provider: ReverseImageProvider) {
        if (!openInBrowser(context, provider.homeUrl)) {
            viewModel.postMessage("没有可以打开该链接的浏览器")
        }
    }

    // 关键词 400ms 防抖：输入停顿后才让 ViewModel 重新比对。
    LaunchedEffect(keywordInput, sourceUri) {
        if (sourceUri == null) return@LaunchedEffect
        val trimmed = keywordInput.trim()
        if (trimmed == appliedKeyword) return@LaunchedEffect
        delay(KEYWORD_DEBOUNCE_MS)
        if (trimmed == appliedKeyword) return@LaunchedEffect
        applyKeywordNow(trimmed)
    }

    // 选源图：v1.0 用 App 内选择器整页替换本页（连带顶栏），选好一张就回来直接开始检索。
    // 这里在组合期提前 return：选择器自带返回键处理，不需要本页的 BackHandler 同时生效。
    if (showDevicePicker) {
        DevicePickerScreen(
            viewModel = viewModel,
            onPicked = { uri ->
                showDevicePicker = false
                startLocalSearch(uri)
            },
            onBack = { showDevicePicker = false },
        )
        return
    }

    // onBack 为 null 表示这是底部 Tab 的一级页面，交给系统默认返回即可。
    BackHandler(enabled = onBack != null) { onBack?.invoke() }

    Column(modifier = Modifier.fillMaxSize()) {
        EropicTopBar(
            title = "识图",
            subtitle = "本地相似图 · 联网以图搜图",
            onBack = onBack,
            compactHeight = true,
            actions = {
                if (sourceUri != null) {
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

        if (sourceUri == null) {
            // 还没选源图：两个 Tab 共用同一段引导，选好后自动切到当前 Tab 的内容。
            SourceGuidance(onPickFromDevice = { showDevicePicker = true })
        } else {
            val currentUri = sourceUri
            if (currentUri != null) {
                SourceSummary(
                    sourceUri = currentUri,
                    onReselect = { resetSource() },
                )
                when (selectedTab) {
                    0 -> LocalFindTab(
                        modifier = Modifier.weight(1f),
                        hits = hits,
                        characterLoading = characterLoading,
                        characterResult = characterResult,
                        onFindCharacter = { findCharacterMatches() },
                        onPickCharacter = { name -> searchByCharacterName(name) },
                        onClearCharacter = {
                            characterResult = null
                            ImageFindSession.characterResult = null
                        },
                        searching = searching,
                        columns = columns,
                        onColumnsChange = { columnOverride = it },
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
                        onlineResult = onlineResult,
                        errorText = onlineError,
                        onSearch = { provider -> launchOnlineSearch(provider) },
                        onOpenHome = { provider -> openProviderHome(provider) },
                        onOpenUrl = { url -> openUrl(url) },
                    )
                }
            }
        }
    }
}

/** 「本地识图」Tab：关键词过滤 + 找同角色 + 相似结果网格，内容独立滚动。 */
@Composable
private fun LocalFindTab(
    modifier: Modifier = Modifier,
    hits: List<SimilarImageHit>,
    characterLoading: Boolean,
    characterResult: CharacterMatchResult?,
    onFindCharacter: () -> Unit,
    onPickCharacter: (String) -> Unit,
    onClearCharacter: () -> Unit,
    searching: Boolean,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
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

        // v0.6 关键词语法：空格 = 同时包含、双引号 = 整段连在一起、词前减号 = 排除（与 core/search 的 KeywordSyntax 对齐）。
        Text(
            text = "语法：空格分隔多个关键词＝同时包含 · 双引号包住的部分＝整段连在一起（例：\"2024 春节\"）· 词前减号＝排除（例：-截图）· 不区分大小写，按子串匹配",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // 「找同角色」：本地相似结果之外的另一条线（按画面里的角色找同款）。
        // 会触发识别服务（可能联网），所以只做成按钮，不跟选图自动跑。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onFindCharacter, enabled = !characterLoading) {
                if (characterLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("正在识别…")
                } else {
                    Icon(
                        imageVector = Icons.Outlined.PersonSearch,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("找同角色")
                }
            }
            if (characterResult != null) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onClearCharacter) { Text("回到相似结果") }
            }
        }

        // 局部 val：delegated 属性（remember）不能智能转换，先接住再做非空判断。
        val currentCharacter = characterResult
        if (currentCharacter != null) {
            CharacterMatchCard(
                result = currentCharacter,
                columns = columns,
                onColumnsChange = onColumnsChange,
                thumbnailsEnabled = thumbnailsEnabled,
                thumbnailProvider = thumbnailProvider,
                onOpen = onOpenViewer,
                onPickCharacter = onPickCharacter,
                modifier = Modifier.weight(1f),
            )
        } else Box(modifier = Modifier.weight(1f)) {
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
                        onColumnsChange = onColumnsChange,
                        thumbnailsEnabled = thumbnailsEnabled,
                        thumbnailProvider = thumbnailProvider,
                        onOpen = onOpenViewer,
                    )
                }
            }
        }
    }
}

/** 引导态：还没选源图时看到的内容。v1.0 起只剩一个入口——App 内的源图选择器。 */
@Composable
private fun SourceGuidance(onPickFromDevice: () -> Unit) {
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
        // v1.0：选图统一走 App 内选择器（照片 / 相册 + 浏览文件），不再区分系统照片选择器与文件选择器。
        Text(
            text = "「从设备选图」打开 App 内的选择器：可以按「照片」「相册」浏览已建立索引的图片；不在图库里的图片（比如「下载」目录），用选择器右上角的「浏览文件」从系统文件管理器挑。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** 已选源图的摘要行：缩略图 + 简易来源说明 + 换图入口。 */
@Composable
private fun SourceSummary(
    sourceUri: Uri,
    onReselect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = sourceUri,
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
                text = "设备选图",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "本地比对不上传；联网识图会上传这张图",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onReselect) { Text("换一张") }
    }
}

/** 「联网识图」Tab：7 家服务商一行一条 + 联网结果区块；整块独立滚动。 */
@Composable
private fun OnlineFindTab(
    modifier: Modifier = Modifier,
    onlineLoading: ReverseImageProvider?,
    onlineResult: OnlineSearchResult?,
    errorText: String?,
    onSearch: (ReverseImageProvider) -> Unit,
    onOpenHome: (ReverseImageProvider) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    // 官网下拉菜单的展开状态；只在本 Tab 内使用。
    var homeMenuOpen by remember { mutableStateOf(false) }

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
            text = "把当前源图上传到服务商，命中的结果会直接显示在下方；需要时再点「在浏览器打开」。上传意味着这张图会离开本机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = "百度识图国内可直连；其余服务商通常需要代理（Clash 等）才能连上。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = "onegai.moe 与 lenso.ai 只能按公网图片链接搜索，App 会先把源图上传到临时图床再把链接交给它们；SauceNAO、ascii2d、IQDB 的结果直接在本页列表里展示。",
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

        // v1.0：服务商从「一排 chip」改成「一行一条」的列表：左边名称与一句说明、右边箭头，
        // 点整行就发起搜索。chip 换行后会参差不齐，而且一句话说明没地方放。
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            ReverseImageProvider.entries.forEachIndexed { index, provider ->
                OnlineProviderRow(
                    provider = provider,
                    loading = onlineLoading == provider,
                    enabled = onlineLoading == null,
                    onClick = { onSearch(provider) },
                )
                if (index != ReverseImageProvider.entries.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }

        Text(
            text = "识别不出结果时，可以打开服务商官网手动上传原图再试。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp),
        )
        Box {
            OutlinedButton(
                onClick = { homeMenuOpen = true },
                enabled = onlineLoading == null,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Language,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("打开服务商官网")
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = homeMenuOpen, onDismissRequest = { homeMenuOpen = false }) {
                ReverseImageProvider.entries.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.label) },
                        onClick = {
                            homeMenuOpen = false
                            onOpenHome(provider)
                        },
                    )
                }
            }
        }

        // 上传抛异常（无网、服务商 5xx 等）时的红色提示；正常失败走 OnlineSearchResult 里的 message。
        val currentError = errorText
        if (currentError != null) {
            ErrorCard(text = currentError)
        }

        val currentResult = onlineResult
        if (currentResult != null) {
            OnlineResultsSection(result = currentResult, onOpenUrl = onOpenUrl)
        }
    }
}

/** 服务商列表的一行：名称 + 一句说明 + 右侧箭头（正在上传时换成转圈）。 */
@Composable
private fun OnlineProviderRow(
    provider: ReverseImageProvider,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                },
            )
            Text(
                text = providerSubtitle(provider),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 服务商的一句话说明。
 *
 * core 的 [ReverseImageProvider] 只有 label 与 homeUrl，没有描述字段；这段是纯展示文案，
 * 也没有跨模块复用价值，所以放在 UI 里，不为了它去改 core 的 API。
 */
private fun providerSubtitle(provider: ReverseImageProvider): String = when (provider) {
    ReverseImageProvider.GOOGLE_LENS -> "通用识图，覆盖最广，需要代理"
    ReverseImageProvider.BAIDU -> "国内可直连，实拍与中文场景更准"
    ReverseImageProvider.SAUCENAO -> "二次元图库索引最全，需要代理"
    ReverseImageProvider.ASCII2D -> "二次元以图搜图，需要代理"
    ReverseImageProvider.IQDB -> "二次元图库检索，需要代理"
    ReverseImageProvider.ONEGAI -> "按公网图片链接搜，App 先上传临时图床"
    ReverseImageProvider.LENSO -> "AI 图像检索，App 先上传临时图床"
}

/** 联网识图异常（无网、上传失败等）的红色提示卡。 */
@Composable
private fun ErrorCard(text: String) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * 候选角色的相似度：core 给的 score 可能是 0..1 的权重，也可能是 0..100 的百分比，
 * 这里按「≤1 视为 0..1」统一换算；拿不到有效分数就返回 null（不显示），避免出现 "0%" 这种迷惑数字。
 */
private fun candidatePercent(score: Double): String? {
    if (score.isNaN() || score <= 0.0) return null
    val percent = if (score <= 1.0) score * 100.0 else score
    return String.format(Locale.US, "%.0f%%", percent.coerceIn(0.0, 100.0))
}

/**
 * 「同角色候选」卡片：顶部写清识别依据（roleHint + 来源 + 角色名候选 + 依据），下面网格展示 localMatches。
 *
 * v0.8 起联网识别会带回 [CharacterMatchResult.candidates]（角色名候选，按可信度排序）与
 * [CharacterMatchResult.basis]（依据逐条说明）。候选做成 chip：点一下就把该名字当关键词再搜，
 * 相当于「我觉得是这个人，按这个名字再看一遍」。
 *
 * 网格复用本地相似结果的实现（同样支持捏合改列数）；点任意一张就带着整个候选集合打开查看器，
 * 这样在查看器里可以一直翻到下一个同角色候选，而不是只翻当前屏幕。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterMatchCard(
    result: CharacterMatchResult,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    thumbnailsEnabled: Boolean,
    thumbnailProvider: suspend (Long) -> File?,
    onOpen: (List<Long>, Int) -> Unit,
    onPickCharacter: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    text = result.roleHint?.takeIf { it.isNotBlank() } ?: "按画面特征找到的相近图片",
                    style = MaterialTheme.typography.titleSmall,
                )
                // 只显示参考图的文件名：result.source 是 SimilarImageHit?，直接拼进文案会变成 data class 的 toString，读起来很糟。
                result.source?.displayName?.takeIf { it.isNotBlank() }?.let { sourceName ->
                    Text(
                        text = "参考图：$sourceName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                // 角色名候选：前 5 个按可信度排序，点一下就把名字填进关键词再搜（本地相似 + 同角色都会重跑）。
                if (result.candidates.isNotEmpty()) {
                    Text(
                        text = "识别到的角色（点一下按这个名字再搜）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        result.candidates.take(MAX_CHARACTER_CHIPS).forEach { candidate ->
                            AssistChip(
                                onClick = { onPickCharacter(candidate.name) },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = candidate.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        candidatePercent(candidate.score)?.let { percent ->
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = percent,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                // 识别依据：一行一条，解释「为什么觉得是同一个人」，让用户自己能判断可信度。
                if (result.basis.isNotEmpty()) {
                    Text(
                        text = "识别依据",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    result.basis.forEach { line ->
                        if (line.isNotBlank()) {
                            Text(
                                text = "• " + line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }

                // 算法层的说明（有没有联网、拿没拿到角色名）——有才显示，避免空行。
                val message = result.message?.takeIf { it.isNotBlank() }
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                Text(
                    text = "共 " + result.localMatches.size + " 张候选 · 点开可在查看器里左右翻看",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (result.localMatches.isEmpty()) {
            Text(
                text = "没有找到足够相近的角色图片，可以换一张源图再试。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            )
        } else {
            SimilarHitsGrid(
                hits = result.localMatches,
                columns = columns,
                onColumnsChange = onColumnsChange,
                thumbnailsEnabled = thumbnailsEnabled,
                thumbnailProvider = thumbnailProvider,
                onOpen = onOpen,
            )
        }
    }
}

/**
 * 结果网格：点击任意结果打开查看器，并带上整个结果集的 id，方便左右翻看。
 * 支持双指捏合临时改列数（2..8），只影响本次浏览，不同步到设置。
 */
@Composable
private fun SimilarHitsGrid(
    hits: List<SimilarImageHit>,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    thumbnailsEnabled: Boolean,
    thumbnailProvider: suspend (Long) -> File?,
    onOpen: (List<Long>, Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier
            .fillMaxSize()
            .pinchToChangeColumns(columns, MIN_FIND_COLUMNS, MAX_FIND_COLUMNS, onColumnsChange),
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

/** 用外部浏览器打开联网识图的结果页；没有可用浏览器时返回 false。 */
private fun openInBrowser(context: Context, url: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}.isSuccess
