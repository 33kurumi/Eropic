package io.github.kurumi33.eropic.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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

    // 大图查看器全屏覆盖在底部导航之上；目标状态放在 ViewModel，旋转不丢。
    val viewerTarget by viewModel.viewerTarget.collectAsStateWithLifecycle()
    viewerTarget?.let { target ->
        ImageViewerScreen(
            viewModel = viewModel,
            mediaIds = target.ids,
            startIndex = target.startIndex,
            onClose = viewModel::closeViewer,
        )
        return
    }

    // 二级页面（搜索 / 存储仪表盘 / 操作历史 / 空文件夹 / 清理报告 / 关于 / 操作结果）
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val closeScreen = viewModel::closeScreen
    when (val current = screen) {
        null -> Unit
        AppScreen.Search -> {
            SearchScreen(viewModel = viewModel, onBack = closeScreen, onOpenViewer = viewModel::openViewer)
            return
        }
        AppScreen.Storage -> {
            StorageDashboardScreen(viewModel = viewModel, onBack = closeScreen)
            return
        }
        AppScreen.History -> {
            OperationHistoryScreen(
                viewModel = viewModel,
                onBack = closeScreen,
                onMessage = { text -> viewModel.postMessage(text) },
            )
            return
        }
        AppScreen.FolderManager -> {
            FolderManagerScreen(
                viewModel = viewModel,
                onBack = closeScreen,
                onMessage = { text -> viewModel.postMessage(text) },
            )
            return
        }
        AppScreen.CleanupReport -> {
            CleanupReportScreen(viewModel = viewModel, onBack = closeScreen)
            return
        }
        AppScreen.About -> {
            AboutScreen(viewModel = viewModel, onBack = closeScreen)
            return
        }
        is AppScreen.Report -> {
            ReportScreen(
                report = current.report,
                onBack = closeScreen,
                onViewHistory = {
                    viewModel.openScreen(AppScreen.History)
                },
            )
            return
        }
    }

    // 写操作结束时把结果页顶上来（结果页关闭时 clearReport）。
    val report by viewModel.lastReport.collectAsStateWithLifecycle()
    report?.let { finished ->
        ReportScreen(
            report = finished,
            onBack = viewModel::clearReport,
            onViewHistory = {
                viewModel.clearReport()
                viewModel.openScreen(AppScreen.History)
            },
        )
        return
    }

    val batchState by viewModel.batchState.collectAsStateWithLifecycle()
    val tabs = remember {
        listOf(
            Tab("图库", Icons.Filled.PhotoLibrary),
            Tab("文件夹", Icons.Filled.Folder),
            Tab("清理", Icons.Filled.CleaningServices),
            Tab("回收站", Icons.Filled.Delete),
            Tab("设置", Icons.Filled.Settings),
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
}
