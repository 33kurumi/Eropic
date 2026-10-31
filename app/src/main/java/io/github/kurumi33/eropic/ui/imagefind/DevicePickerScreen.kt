package io.github.kurumi33.eropic.ui.imagefind

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.core.model.FolderRow
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.EropicTopBar
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount
import kotlinx.coroutines.flow.Flow
import java.io.File

/** 选择器里两个 Tab 的下标：0 = 照片（全库），1 = 相册（按目录）。 */
private const val PICKER_TAB_PHOTOS = 0
private const val PICKER_TAB_ALBUMS = 1

/** 选择器的网格列数下限/上限：与图库一致（设置里 3..6，这里放宽到 2..8 以兼容捏合过的用户设置）。 */
private const val MIN_PICKER_COLUMNS = 2
private const val MAX_PICKER_COLUMNS = 8

private val PICKER_TAB_TITLES: List<String> = listOf("照片", "相册")

/**
 * 源图选择器（v1.0）：「从设备选图」不再直接弹系统照片选择器，而是先打开这个 App 内页面。
 *
 * 为什么要自己做一个：系统照片选择器只能看到系统媒体库里的图，选不到「下载」「U 盘」等目录，
 * 也不能用图库里已经整理好的目录结构去找图。这里给两个入口：
 * 1. 「照片」Tab：App 已索引的全部图片，按设置里的列数分页加载；
 * 2. 「相册」Tab：先列目录（复用 [EropicViewModel.loadFolderRows]），点进目录再看该目录的图。
 * 右侧「浏览文件」保留系统文件选择器（DocumentsUI），用来选不在图库里的任意文件。
 *
 * 三者的结果都收敛成一个 [Uri]，交给上层去触发识图，选择器本身不碰任何识图逻辑。
 * 选中的是系统媒体 Uri（[MediaUris.of]），识图时需要时再解码，不会额外复制图片。
 */
@Composable
fun DevicePickerScreen(
    viewModel: EropicViewModel,
    onPicked: (Uri) -> Unit,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val columns = settings.gridColumns.coerceIn(MIN_PICKER_COLUMNS, MAX_PICKER_COLUMNS)
    val context = LocalContext.current

    var selectedTab by rememberSaveable { mutableStateOf(PICKER_TAB_PHOTOS) }
    // 选中的相册目录；null 表示还停在目录列表。
    var selectedFolder by remember { mutableStateOf<FolderRow?>(null) }
    // 目录列表：null = 正在加载（或还没开始加载），空列表 = 确实一个目录都没有。
    var folderRows by remember { mutableStateOf<List<FolderRow>?>(null) }
    var folderError by remember { mutableStateOf<String?>(null) }
    // 重试计数：目录读取失败后点「重试」改这个值，重新触发 LaunchedEffect。
    var folderRetry by remember { mutableIntStateOf(0) }

    // 「浏览文件」：DocumentsUI 自带目录浏览，能进下载、U 盘等图库覆盖不到的位置。
    // OpenDocument 拿到的 Uri 在个别机型上可能读不了，所以先开一次流验证，读不到就提示而不是等识图时才报错。
    val browseLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val readable = runCatching {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        }.getOrDefault(false)
        if (readable) {
            onPicked(uri)
        } else {
            viewModel.postMessage("无法读取这张图片，请换一个文件再试")
        }
    }

    // 目录列表只在切到「相册」时加载；切回来会重新读，保证新建的目录能及时出现。
    LaunchedEffect(selectedTab, folderRetry) {
        if (selectedTab != PICKER_TAB_ALBUMS) return@LaunchedEffect
        folderRows = null
        folderError = null
        runCatching { viewModel.loadFolderRows() }
            .onSuccess { rows -> folderRows = rows }
            .onFailure { folderError = "读取相册目录失败，请稍后重试" }
    }

    // 进了某个目录时返回键先回目录列表，再按一次才退出选择器。
    BackHandler {
        if (selectedFolder != null) {
            selectedFolder = null
        } else {
            onBack()
        }
    }

    // 全库照片流：viewModel.media 每次访问都可能返回新的 Flow，remember 住避免反复重新分页。
    val photoFlow = remember(viewModel) { viewModel.media }

    Column(modifier = Modifier.fillMaxSize()) {
        EropicTopBar(
            title = "选择源图",
            subtitle = "点一张图片即开始识图，本地比对不会上传",
            onBack = onBack,
        )
        // Tab 与「浏览文件」放同一行：这个按钮属于选择界面本身，放在顶栏右侧会和返回键抢位置，
        // 单独占一行又会把网格挤下去，所以贴在「相册」右边最顺手。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.weight(1f),
            ) {
                PICKER_TAB_TITLES.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            selectedFolder = null
                        },
                        text = { Text(title) },
                    )
                }
            }
            TextButton(
                onClick = { browseLauncher.launch(arrayOf("image/*")) },
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Text("浏览文件")
            }
        }

        val folder = selectedFolder
        when {
            selectedTab == PICKER_TAB_PHOTOS -> PickerPhotoGrid(
                modifier = Modifier.weight(1f),
                flow = photoFlow,
                columns = columns,
                emptyText = EMPTY_PHOTO_HINT,
                thumbnailProvider = { entity -> viewModel.thumbnailFile(entity) },
                onPick = { entity -> onPicked(MediaUris.of(entity.id)) },
            )

            folder != null -> FolderPhotoSection(
                modifier = Modifier.weight(1f),
                viewModel = viewModel,
                folder = folder,
                columns = columns,
                thumbnailProvider = { entity -> viewModel.thumbnailFile(entity) },
                onPick = { entity -> onPicked(MediaUris.of(entity.id)) },
                onBackToList = { selectedFolder = null },
            )

            else -> FolderListSection(
                modifier = Modifier.weight(1f),
                rows = folderRows,
                errorText = folderError,
                onRetry = { folderRetry++ },
                onOpen = { row -> selectedFolder = row },
            )
        }
    }
}

/** 照片 Tab 的空态文案：告诉用户两条出路，而不是只说「没有图片」。 */
private const val EMPTY_PHOTO_HINT =
    "图库里还没有图片，先回图库建立索引后再来选；也可以点右上角「浏览文件」从系统文件管理器选图。"

/**
 * 分页图片网格：照片 Tab 与相册目录详情共用。
 *
 * 每格点一下就把该图的 content Uri 交给上层（[onPick]），不做多选、不做预览，
 * 选择器的目标就是「尽快选到一张图」。
 */
@Composable
private fun PickerPhotoGrid(
    modifier: Modifier = Modifier,
    flow: Flow<PagingData<MediaEntity>>,
    columns: Int,
    emptyText: String,
    thumbnailProvider: suspend (MediaEntity) -> File?,
    onPick: (MediaEntity) -> Unit,
) {
    val items = remember(flow) { flow }.collectAsLazyPagingItems()
    val refreshing = items.loadState.refresh is LoadState.Loading
    val appending = items.loadState.append is LoadState.Loading

    if (refreshing && items.itemCount == 0) {
        PickerLoading(text = "正在读取图片…", modifier = modifier)
        return
    }
    if (items.itemCount == 0) {
        PickerHint(text = emptyText, modifier = modifier)
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(count = items.itemCount) { index ->
            val entity = items[index]
            if (entity == null) {
                // 分页占位：先占住格子，避免图片加载完时整屏跳动。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                )
            } else {
                PickerPhotoCell(
                    entity = entity,
                    thumbnailProvider = thumbnailProvider,
                    onClick = { onPick(entity) },
                )
            }
        }
        if (appending) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/** 选择器里的一张图：缩略图缓存命中就用本地文件，否则退回系统图片 Uri。 */
@Composable
private fun PickerPhotoCell(
    entity: MediaEntity,
    thumbnailProvider: suspend (MediaEntity) -> File?,
    onClick: () -> Unit,
) {
    val model by produceState<Any>(MediaUris.of(entity.id), entity.id) {
        value = runCatching { thumbnailProvider(entity) }
            .getOrNull()
            ?.let { file -> Uri.fromFile(file) }
            ?: MediaUris.of(entity.id)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = model,
            contentDescription = entity.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 相册 Tab 的目录列表：点一行进入该目录的图片网格。 */
@Composable
private fun FolderListSection(
    modifier: Modifier = Modifier,
    rows: List<FolderRow>?,
    errorText: String?,
    onRetry: () -> Unit,
    onOpen: (FolderRow) -> Unit,
) {
    when {
        errorText != null -> Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                    Text("重试")
                }
            }
        }

        rows == null -> PickerLoading(text = "正在读取相册目录…", modifier = modifier)

        rows.isEmpty() -> PickerHint(
            text = "图库里还没有任何目录，先回图库建立索引后再来选。",
            modifier = modifier,
        )

        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(items = rows, key = { row -> row.relativePath }) { row ->
                FolderRowItem(row = row, onOpen = onOpen)
                if (row != rows.last()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

/** 目录列表的一行：图标 + 目录名 + 张数/占用；右侧箭头表示可以点进去。 */
@Composable
private fun FolderRowItem(row: FolderRow, onOpen: (FolderRow) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(row) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = folderDisplayName(row.relativePath),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatCount(row.mediaCount) + " 张 · " + formatBytes(row.totalBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 进入某个目录后的详情：顶部一行（返回目录列表 + 目录名 + 张数），下面是该目录的图片网格。 */
@Composable
private fun FolderPhotoSection(
    modifier: Modifier = Modifier,
    viewModel: EropicViewModel,
    folder: FolderRow,
    columns: Int,
    thumbnailProvider: suspend (MediaEntity) -> File?,
    onPick: (MediaEntity) -> Unit,
    onBackToList: () -> Unit,
) {
    // 同一目录的 Flow 要 remember 住：folderMedia 每次调用都新建一个 Flow，否则每次重组都会重新分页。
    val flow = remember(folder.relativePath) { viewModel.folderMedia(folder.relativePath, false) }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBackToList) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回相册目录",
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folderDisplayName(folder.relativePath),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatCount(folder.mediaCount) + " 张 · " + formatBytes(folder.totalBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        PickerPhotoGrid(
            modifier = Modifier.weight(1f),
            flow = flow,
            columns = columns,
            emptyText = "这个目录里没有图片。",
            thumbnailProvider = thumbnailProvider,
            onPick = onPick,
        )
    }
}

/** 目录名：relativePath 去掉结尾的「/」后取最后一段；根目录没有名字时显示「根目录」。 */
private fun folderDisplayName(relativePath: String): String {
    val name = relativePath.trimEnd('/').substringAfterLast('/')
    return name.ifBlank { "根目录" }
}

/** 加载态：居中转圈 + 一行说明。 */
@Composable
private fun PickerLoading(modifier: Modifier = Modifier, text: String) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** 空态/提示：居中一段说明文字。 */
@Composable
private fun PickerHint(modifier: Modifier = Modifier, text: String) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
