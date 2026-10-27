package io.github.kurumi33.eropic.ui.viewer

import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.ui.EropicViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏大图查看器：黑色背景 + 顶部半透明标题栏，图片区域整屏铺开。
 *
 * 交互：
 * - 左右滑动翻页（图片放大时自动禁用翻页，单指拖动改成平移画面）；
 * - 双指缩放 / 双击缩放，见 [ZoomableImage]；
 * - 点击图片切换标题栏显隐，获得沉浸式浏览；
 * - 收藏、分享、查看详情。
 *
 * 该 Composable 自带 Scaffold，由 EropicRoot 覆盖在其他界面之上显示，不放在底部导航里。
 *
 * @param mediaIds 按当前排序/筛选得到的有序图片 id 列表。
 * @param startIndex 初始显示的位置（点击网格中的第几张）。
 * @param onClose 关闭查看器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    viewModel: EropicViewModel,
    mediaIds: List<Long>,
    startIndex: Int,
    onClose: () -> Unit,
) {
    if (mediaIds.isEmpty()) {
        EmptyViewer(onClose = onClose)
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // initialPage 必须落在合法范围内，避免调用方传入越界下标时崩溃。
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, mediaIds.lastIndex),
        pageCount = { mediaIds.size },
    )
    val currentPage = pagerState.currentPage
    val currentId = mediaIds.getOrNull(currentPage)

    // 当前页的实体：驱动标题栏收藏状态、分享 MIME 与详情面板。
    val entityState = produceState<MediaEntity?>(null, currentId) {
        value = currentId?.let { id -> runCatching { viewModel.mediaById(id) }.getOrNull() }
    }
    val currentEntity = entityState.value

    // 乐观收藏结果：等数据库返回真实值后校正，翻页时清空。
    var favoriteOverride by remember { mutableStateOf<Boolean?>(null) }
    var detailsOpen by remember { mutableStateOf(false) }
    var headerVisible by remember { mutableStateOf(true) }
    var currentPageZoomed by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 幻灯片播放间隔来自设置页；菜单展开状态只属于本页。
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var slideshow by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(currentId) { favoriteOverride = null }
    LaunchedEffect(currentPage) { currentPageZoomed = false }

    // 幻灯片：停在当前页等一个间隔，然后自动滑到下一页；到末尾自动停。
    LaunchedEffect(slideshow, currentPage, mediaIds.size, settings.slideshowIntervalSec) {
        if (!slideshow) return@LaunchedEffect
        delay(settings.slideshowIntervalSec * 1000L)
        val next = currentPage + 1
        if (next > mediaIds.lastIndex) {
            slideshow = false
        } else {
            pagerState.animateScrollToPage(next)
        }
    }

    // 收藏判定 = MediaStore 收藏 或 App 内本地收藏。
    val isFavorite = favoriteOverride
        ?: currentEntity?.let { it.localFavorite || it.isFavorite }
        ?: false

    val toggleFavorite: () -> Unit = {
        val id = currentId
        if (id != null) {
            favoriteOverride = !isFavorite
            scope.launch {
                runCatching { viewModel.toggleFavorite(id) }
                    .onSuccess { favoriteOverride = it }
                    .onFailure { favoriteOverride = null }
            }
        }
    }

    val share: () -> Unit = {
        val id = currentId
        if (id != null) {
            shareImage(context = context, mediaId = id, mimeType = currentEntity?.mimeType)
        }
    }

    // 设为壁纸：读原始图片流交给系统，不复制文件、不改动图库。
    val setAsWallpaper: () -> Unit = {
        val id = currentId
        if (id != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(MediaUris.of(id))?.use { input ->
                            WallpaperManager.getInstance(context).setStream(input)
                        } != null
                    }.getOrDefault(false)
                }
                Toast.makeText(
                    context,
                    if (ok) "已设为壁纸" else "设置壁纸失败（图片可能已损坏或格式不被系统支持）",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // 交给系统相册打开，方便需要系统编辑能力时跳过去。
    val openInGallery: () -> Unit = {
        val id = currentId
        if (id != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(MediaUris.of(id), currentEntity?.mimeType ?: "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    val copyPath: () -> Unit = {
        val path = currentEntity?.path
        if (!path.isNullOrBlank()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("图片路径", path))
            Toast.makeText(context, "路径已复制", Toast.LENGTH_SHORT).show()
        }
    }

    // 详情面板打开时把返回键让给它自己关闭。
    BackHandler(enabled = !detailsOpen) { onClose() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            if (headerVisible) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "${currentPage + 1} / ${mediaIds.size}" +
                                    if (slideshow) "　▶ 播放中" else "",
                            )
                            // 外置存储（SD / U 盘）在 Android 上是只读挂载，写操作会失败，这里明确提示。
                            if (currentEntity != null && currentEntity?.volumeName != "external_primary") {
                                Text(
                                    text = "外置存储 · 只读查看",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = toggleFavorite) {
                            Icon(
                                imageVector = if (isFavorite) {
                                    Icons.Filled.Favorite
                                } else {
                                    Icons.Filled.FavoriteBorder
                                },
                                contentDescription = if (isFavorite) "取消收藏" else "收藏",
                                tint = if (isFavorite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.White
                                },
                            )
                        }
                        IconButton(onClick = { detailsOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = "图片详情",
                            )
                        }
                        IconButton(onClick = share) {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = "分享",
                            )
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "更多操作",
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (slideshow) "停止幻灯片" else "幻灯片播放") },
                                onClick = {
                                    slideshow = !slideshow
                                    menuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("设为壁纸") },
                                onClick = {
                                    menuOpen = false
                                    setAsWallpaper()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("用系统相册打开") },
                                onClick = {
                                    menuOpen = false
                                    openInGallery()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("复制文件路径") },
                                onClick = {
                                    menuOpen = false
                                    copyPath()
                                },
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.35f),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
                )
            }
        },
    ) { _ ->
        // 故意不使用 Scaffold 的 innerPadding：图片要铺满全屏，标题栏浮在上面。
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !currentPageZoomed,
            key = { page -> mediaIds[page] },
        ) { page ->
            ViewerPage(
                viewModel = viewModel,
                mediaId = mediaIds[page],
                isActive = page == currentPage,
                onZoomChanged = { zoomed ->
                    if (page == pagerState.currentPage) {
                        currentPageZoomed = zoomed
                    }
                },
                onTap = { headerVisible = !headerVisible },
            )
        }
    }

    val detailsEntity = currentEntity
    if (detailsOpen && detailsEntity != null) {
        ModalBottomSheet(
            onDismissRequest = { detailsOpen = false },
            sheetState = sheetState,
        ) {
            ImageDetailsSheet(
                entity = detailsEntity,
                onDismiss = { detailsOpen = false },
            )
        }
    }
}

/** 单页内容：按 id 懒加载实体，再交给 [ZoomableImage] 显示。 */
@Composable
private fun ViewerPage(
    viewModel: EropicViewModel,
    mediaId: Long,
    isActive: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    var loaded by remember(mediaId) { mutableStateOf(false) }
    val entity by produceState<MediaEntity?>(null, mediaId) {
        value = runCatching { viewModel.mediaById(mediaId) }.getOrNull()
        loaded = true
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val media = entity
        when {
            !loaded -> CircularProgressIndicator(color = Color.White)
            media == null -> Text(
                text = "图片已不在索引中",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
            else -> ZoomableImage(
                model = MediaUris.of(mediaId),
                contentDescription = media.displayName,
                modifier = Modifier.fillMaxSize(),
                onZoomChanged = onZoomChanged,
                onTap = onTap,
                // 当前页离开/回到前台时都复位缩放，避免翻页返回后仍停在放大状态。
                resetKey = mediaId to isActive,
            )
        }
    }
}

/** 没有任何图片可看时的兜底界面。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyViewer(onClose: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(text = "查看图片") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "没有可查看的图片",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** 用 MediaStore 的 content Uri 发起系统分享，失败时静默忽略（例如没有可用的分享目标）。 */
private fun shareImage(context: Context, mediaId: Long, mimeType: String?) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType ?: "image/*"
        putExtra(Intent.EXTRA_STREAM, MediaUris.of(mediaId))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(sendIntent, "分享图片")
    runCatching { context.startActivity(chooser) }
}
