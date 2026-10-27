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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kurumi33.eropic.ui.cleanup.CleanupScreen
import io.github.kurumi33.eropic.ui.folders.FoldersSection
import io.github.kurumi33.eropic.ui.gallery.GalleryScreen
import io.github.kurumi33.eropic.ui.viewer.ImageViewerScreen
import io.github.kurumi33.eropic.ui.permission.MediaAccess
import io.github.kurumi33.eropic.ui.permission.MediaPermissions
import io.github.kurumi33.eropic.ui.permission.PermissionScreen
import io.github.kurumi33.eropic.ui.settings.SettingsScreen

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun EropicRoot(viewModel: EropicViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var access by remember { mutableStateOf(MediaPermissions.evaluate(context)) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        access = MediaPermissions.evaluate(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                access = MediaPermissions.evaluate(context)
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

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember {
        listOf(
            Tab("图库", Icons.Filled.PhotoLibrary),
            Tab("文件夹", Icons.Filled.Folder),
            Tab("清理", Icons.Filled.CleaningServices),
            Tab("设置", Icons.Filled.Settings),
        )
    }

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
            when (tabIndex) {
                0 -> GalleryScreen(
                    viewModel = viewModel,
                    access = access,
                    onRequestFullAccess = { launcher.launch(MediaPermissions.required()) },
                    onOpenViewer = viewModel::openViewer,
                )
                1 -> FoldersSection(
                    viewModel = viewModel,
                    onOpenViewer = viewModel::openViewer,
                )
                2 -> CleanupScreen(viewModel)
                else -> SettingsScreen(viewModel)
            }
        }
    }
}
