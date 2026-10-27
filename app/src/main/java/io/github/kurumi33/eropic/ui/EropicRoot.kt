package io.github.kurumi33.eropic.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kurumi33.eropic.core.notify.TrashNotifications
import io.github.kurumi33.eropic.ui.about.AboutScreen
import io.github.kurumi33.eropic.ui.cleanup.CleanupReportScreen
import io.github.kurumi33.eropic.ui.cleanup.CleanupScreen
import io.github.kurumi33.eropic.ui.common.BatchProgressBar
import io.github.kurumi33.eropic.ui.folders.FolderManagerScreen
import io.github.kurumi33.eropic.ui.folders.FoldersSection
import io.github.kurumi33.eropic.ui.gallery.GalleryScreen
import io.github.kurumi33.eropic.ui.history.OperationHistoryScreen
import io.github.kurumi33.eropic.ui.permission.MediaPermissions
import io.github.kurumi33.eropic.ui.permission.PermissionScreen
import io.github.kurumi33.eropic.ui.report.ReportScreen
import io.github.kurumi33.eropic.ui.search.SearchScreen
import io.github.kurumi33.eropic.ui.settings.SettingsScreen
import io.github.kurumi33.eropic.ui.storage.StorageDashboardScreen
import io.github.kurumi33.eropic.ui.trash.TrashSection
import io.github.kurumi33.eropic.ui.viewer.ImageViewerScreen

private data class Tab(val label: String, val icon: ImageVector)

@Composable
fun EropicRoot(viewModel: EropicViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var access by remember { mutableStateOf(MediaPermissions.evaluate(context)) }
    // "所有文件访问"是写操作（移动/重命名/回收站）的前提，授予状态可能随时被用户在系统设置里改，
    // 因此每次回到前台都重新读一次，而不是只在启动时读。
    var hasWriteAccess by remember { mutableStateOf(viewModel.hasAllFilesAccess()) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        access = MediaPermissions.evaluate(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                access = MediaPermissions.evaluate(context)
                hasWriteAccess = viewModel.hasAllFilesAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!access.full && !access.partial) {
        PermissionScreen(
            onRequest = { launcher.launch(MediaPermissions.required()) },
            onOpenSettings = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            },
        )
        return
    }

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 通知权限：回收站"即将到期"提醒需要它。只在用户第一次进回收站时问，不在启动时打扰。
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝也不影响功能，只是收不到到期提醒 */ }
    LaunchedEffect(tabIndex) {
        if (tabIndex == 3 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 回收站到期提醒点进来时直接落到回收站 tab（extra 用完即清，避免旋转/重建反复触发）。
    LaunchedEffect(Unit) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val intent = activity.intent ?: return@LaunchedEffect
        if (intent.getBooleanExtra(TrashNotifications.EXTRA_OPEN_TRASH, false)) {
            tabIndex = 3
            intent.removeExtra(TrashNotifications.EXTRA_OPEN_TRASH)
        }
    }

    // 一次性提示统一在这里消费：写操作的结果、以及"撤销"入口。
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            val result = snackbarHostState.showSnackbar(
                message = message.text,
                actionLabel = if (message.undoTrashIds != null) "撤销" else null,
                withDismissAction = true,
                duration = if (message.undoTrashIds != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                message.undoTrashIds?.let { ids -> viewModel.undoTrash(ids) }
            }
        }
    }

    val viewerTarget by viewModel.viewerTarget.collectAsStateWithLifecycle()
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val report by viewModel.lastReport.collectAsStateWithLifecycle()
    val batchState by viewModel.batchState.collectAsStateWithLifecycle()
    val closeScreen = viewModel::closeScreen
    val tabs = remember {
        listOf(
            Tab("图库", Icons.Filled.PhotoLibrary),
            Tab("文件夹", Icons.Filled.Folder),
            Tab("清理", Icons.Filled.CleaningServices),
            Tab("回收站", Icons.Filled.Delete),
            Tab("设置", Icons.Filled.Settings),
        )
    }

    // 关键结构：主页 Scaffold 始终在底部存活，查看器/二级页/结果页只是"盖"在上面的叠加层。
    // 早返回（把整棵树换成查看器）会销毁图库的 LazyGrid，关闭查看器后滚动位置和捏合列数全会丢。
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = tabIndex == index,
                            onClick = { tabIndex = index },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                // 批量操作（移动/重命名/复制/清空回收站）进行时，顶部常驻进度条，可暂停/取消。
                BatchProgressBar(
                    state = batchState,
                    onPause = viewModel::pauseBatch,
                    onResume = viewModel::resumeBatch,
                    onCancel = viewModel::cancelBatch,
                )
                when (tabIndex) {
                    0 -> GalleryScreen(
                        viewModel = viewModel,
                        access = access,
                        onRequestFullAccess = { launcher.launch(MediaPermissions.required()) },
                        onOpenViewer = viewModel::openViewer,
                        hasWriteAccess = hasWriteAccess,
                    )
                    1 -> FoldersSection(
                        viewModel = viewModel,
                        onOpenViewer = viewModel::openViewer,
                        hasWriteAccess = hasWriteAccess,
                    )
                    2 -> CleanupScreen(
                        viewModel = viewModel,
                        onOpenViewer = viewModel::openViewer,
                        hasWriteAccess = hasWriteAccess,
                    )
                    3 -> TrashSection(
                        viewModel = viewModel,
                        hasWriteAccess = hasWriteAccess,
                        onMessage = { text -> viewModel.postMessage(text) },
                    )
                    else -> SettingsScreen(viewModel)
                }
            }
        }

        // 二级页 / 结果页：只在没有查看器时渲染，且用 BlockingOverlay 吃掉穿透点击。
        if (viewerTarget == null) {
            val current = screen
            if (current != null) {
                BackHandler(onBack = closeScreen)
                BlockingOverlay {
                    when (current) {
                        AppScreen.Search -> SearchScreen(
                            viewModel = viewModel,
                            onBack = closeScreen,
                            onOpenViewer = viewModel::openViewer,
                        )
                        AppScreen.Storage -> StorageDashboardScreen(
                            viewModel = viewModel,
                            onBack = closeScreen,
                        )
                        AppScreen.History -> OperationHistoryScreen(
                            viewModel = viewModel,
                            onBack = closeScreen,
                            onMessage = { text -> viewModel.postMessage(text) },
                        )
                        AppScreen.FolderManager -> FolderManagerScreen(
                            viewModel = viewModel,
                            onBack = closeScreen,
                            onMessage = { text -> viewModel.postMessage(text) },
                            hasWriteAccess = hasWriteAccess,
                        )
                        AppScreen.CleanupReport -> CleanupReportScreen(
                            viewModel = viewModel,
                            onBack = closeScreen,
                        )
                        AppScreen.About -> AboutScreen(viewModel = viewModel, onBack = closeScreen)
                        is AppScreen.Report -> ReportScreen(
                            report = current.report,
                            // 先清掉结果、再关页面：否则关掉后 lastReport 又把结果页顶回来，形成导航环。
                            onBack = {
                                viewModel.clearReport()
                                closeScreen()
                            },
                            onViewHistory = {
                                viewModel.clearReport()
                                viewModel.openScreen(AppScreen.History)
                            },
                        )
                    }
                }
            } else {
                report?.let { finished ->
                    BackHandler(onBack = viewModel::clearReport)
                    BlockingOverlay {
                        ReportScreen(
                            report = finished,
                            onBack = viewModel::clearReport,
                            onViewHistory = {
                                viewModel.clearReport()
                                viewModel.openScreen(AppScreen.History)
                            },
                        )
                    }
                }
            }
        }

        // 大图查看器：全屏盖在一切之上；目标状态放在 ViewModel，旋转不丢。
        viewerTarget?.let { target ->
            ImageViewerScreen(
                viewModel = viewModel,
                mediaIds = target.ids,
                startIndex = target.startIndex,
                onClose = viewModel::closeViewer,
            )
        }

        // Snackbar 宿主放在 Box 最上层：二级页/结果页也能看到提示（之前放在 Scaffold 里，
        // 二级页是全屏早返回，提示要等回到主界面才弹出来）。
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 12.dp, end = 12.dp, bottom = 88.dp),
        )
    }
}

/**
 * 叠加页外壳：铺满并吃掉落在空白处的点击，避免点击"穿透"到下面的图库网格。
 */
@Composable
private fun BlockingOverlay(content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { /* 只为拦截穿透点击 */ },
            ),
    ) {
        content()
    }
}
