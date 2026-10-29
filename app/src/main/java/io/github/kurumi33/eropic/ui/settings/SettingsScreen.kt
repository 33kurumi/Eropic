package io.github.kurumi33.eropic.ui.settings

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kurumi33.eropic.core.media.SortOrder
import io.github.kurumi33.eropic.core.settings.AppSettings
import io.github.kurumi33.eropic.core.settings.DateGroupMode
import io.github.kurumi33.eropic.core.settings.RetentionOption
import io.github.kurumi33.eropic.core.settings.SimilarityLevel
import io.github.kurumi33.eropic.core.settings.ThemeMode
import io.github.kurumi33.eropic.ui.AppScreen
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.EropicTopBar
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: EropicViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val count by viewModel.count.collectAsStateWithLifecycle()
    val totalSize by viewModel.totalSize.collectAsStateWithLifecycle()
    val indexState by viewModel.indexState.collectAsStateWithLifecycle()
    val thumbnailCacheSize by viewModel.thumbnailCacheSize.collectAsStateWithLifecycle()
    var showRebuildDialog by remember { mutableStateOf(false) }

    // 「所有文件访问」是系统设置页授权的，回到应用时要重新读一次真实状态。
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasWriteAccess by remember { mutableStateOf(viewModel.hasAllFilesAccess()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasWriteAccess = viewModel.hasAllFilesAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 进设置页时刷新一次缓存占用（目录扫描，动作用户可见时才做）。
    LaunchedEffect(Unit) { viewModel.refreshThumbnailCacheSize() }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.1.0"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // v0.5：统一顶栏；副标题带上版本号，用户反馈问题时能直接说明用的哪一版。
        EropicTopBar(title = "设置", subtitle = "v0.6")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // v0.4：每个功能组一个外框（SettingsSection），组里的行直接排在框内，不再叠一层卡片。
            // 顺序：外观 → 图库与查看 → 清理与回收站 → 资源与缓存 → 后台与电量 → 存储权限 → 更多页面 → 关于。
            // v0.6：删掉「联网识图」（Key 已内置进代码、识图入口在底部/清理页），也删掉与关于页重复的入口。
            SettingsSection(
                title = "外观",
                subtitle = "主题与取色。",
            ) {
                AppearanceCard(settings = settings, viewModel = viewModel)
            }

            SettingsSection(
                title = "图库与查看",
                subtitle = "网格列数、标题栏信息、默认排序、日期分组与幻灯片间隔。",
            ) {
                GalleryViewCard(settings = settings, viewModel = viewModel)
            }

            SettingsSection(
                title = "清理与回收站",
                subtitle = "回收站保留时长与相似判定档位。",
            ) {
                CleanupSettingsCard(settings = settings, viewModel = viewModel)
            }

            SettingsSection(
                title = "资源与缓存",
                subtitle = "扫描范围、缩略图缓存与本地索引状态都在这里。",
            ) {
                ScanScopeCard(settings = settings, viewModel = viewModel)
                HorizontalDivider()
                ThumbnailCacheCard(
                    settings = settings,
                    cacheSizeBytes = thumbnailCacheSize,
                    viewModel = viewModel,
                )
                HorizontalDivider()
                // 本地索引：已索引数量、后台任务状态与重建入口。
                Text(
                    text = "已索引 " + formatCount(count) + " 张 · " + formatBytes(totalSize),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "任务状态：" + indexState.phase.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (indexState.isRunning) {
                    OutlinedButton(onClick = viewModel::cancelIndex) { Text("取消当前任务") }
                } else {
                    Button(onClick = viewModel::startIndex) { Text("增量扫描") }
                }
                OutlinedButton(onClick = { showRebuildDialog = true }) { Text("清空并重建索引") }
            }

            SettingsSection(
                title = "后台与电量",
                subtitle = "指纹与缩略图在什么条件下于后台补齐。",
            ) {
                BackgroundCard(settings = settings, viewModel = viewModel)
            }

            SettingsSection(
                title = "存储权限",
                subtitle = "真实移动、移入回收站与彻底删除依赖「所有文件访问」。",
            ) {
                StorageAccessCard(
                    hasWriteAccess = hasWriteAccess,
                    onOpenPermission = { viewModel.openScreen(AppScreen.Permission) },
                )
            }

            SettingsSection(
                title = "更多页面",
                subtitle = "底部栏放不下的二级页面。",
            ) {
                MorePagesCard(viewModel = viewModel)
            }

            SettingsSection(
                title = "关于",
                subtitle = "版本、隐私协议、更新日志与许可。",
            ) {
                Text(text = "Eropic " + versionName + " · 本地优先 · 仅「联网搜图」时联网")
                Text(
                    text = "开源地址：github.com/33kurumi/Eropic",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "没有删除按钮：所有会改动文件的操作都必须由你确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { viewModel.openScreen(AppScreen.About) }) {
                    Text("关于 Eropic")
                }
            }
        }
    }

    if (showRebuildDialog) {
        AlertDialog(
            onDismissRequest = { showRebuildDialog = false },
            title = { Text("清空并重建索引？") },
            text = {
                Text(
                    "会删除本地索引与查重结果并重新扫描。不会改动你手机里的任何照片。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRebuildDialog = false
                    viewModel.rebuildIndex()
                }) { Text("重建") }
            },
            dismissButton = {
                TextButton(onClick = { showRebuildDialog = false }) { Text("取消") }
            },
        )
    }
}

/**
 * v0.4：设置页的功能组外框。
 *
 * 每个功能组是一个大框：底色 surfaceContainerLow、无阴影；组标题与副标题在框内顶部，
 * 组里的内容直接是清单行，不再叠一层同色卡片，避免「卡中卡」的双层灰底。
 */
@Composable
private fun SettingsSection(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    // v0.5：分组标题从 titleSmall 提到 titleMedium——之前比组内正文只大一点，扫读时找不到分组。
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            content()
        }
    }
}

/**
 * 外观分组：主题与取色。网格列数、排序等偏浏览的开关在 GalleryViewCard 里（v0.3 按分组拆开）。
 */
@Composable
private fun AppearanceCard(settings: AppSettings, viewModel: EropicViewModel) {
    Text(text = "主题", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { mode ->
            FilterChip(
                selected = settings.themeMode == mode,
                onClick = { viewModel.setThemeMode(mode) },
                label = { Text(mode.label) },
            )
        }
    }

    SettingSwitchRow(
        title = "动态取色",
        subtitle = "用系统壁纸配色（Android 12+）。关闭后使用 Eropic 的紫色主题。",
        checked = settings.dynamicColor,
        onCheckedChange = viewModel::setDynamicColor,
    )
}

@Composable
private fun ThumbnailCacheCard(
    settings: AppSettings,
    cacheSizeBytes: Long,
    viewModel: EropicViewModel,
) {
    Text(
        text = "图库网格改用 320px 小图，滚动更省内存和电。缓存存在应用缓存目录，不会动你的照片；" +
            "清空或卸载随时可丢弃，之后会按需重建。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = "当前占用 " + formatBytes(cacheSizeBytes),
        style = MaterialTheme.typography.bodyMedium,
    )

    SettingSwitchRow(
        title = "启用缩略图缓存",
        subtitle = "关闭后网格直接读原图，老照片多时会明显变慢。",
        checked = settings.thumbnailCacheEnabled,
        onCheckedChange = viewModel::setThumbnailCacheEnabled,
    )

    Text(text = "缓存上限", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(512, 1024, 2048).forEach { mb ->
            FilterChip(
                selected = settings.thumbnailCacheMb == mb,
                onClick = { viewModel.setThumbnailCacheMb(mb) },
                label = { Text(if (mb >= 1024) (mb / 1024).toString() + " GB" else mb.toString() + " MB") },
            )
        }
    }
    Text(
        text = "超出上限时自动删除最久没用的缓存。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    var confirmClear by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { confirmClear = true }) { Text("清空缓存") }
        OutlinedButton(onClick = viewModel::backfillThumbnails) { Text("充电时补齐") }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空缩略图缓存？") },
            text = { Text("只删除 cacheDir/thumbs 里的缩略图文件，不会动任何照片；下次浏览会按需重新生成，首屏会略慢。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearThumbnailCache()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BackgroundCard(settings: AppSettings, viewModel: EropicViewModel) {
    Text(
        text = "计算指纹、查重是重活，只在你在应用内点「开始」或自动续算时跑，不会常驻后台。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SettingSwitchRow(
        title = "仅充电时补齐缩略图",
        subtitle = "关闭后未充电也会在后台补齐缩略图缓存，耗电与发热会略高。",
        checked = settings.thumbnailBackfillChargingOnly,
        onCheckedChange = viewModel::setThumbnailBackfillChargingOnly,
    )

    SettingSwitchRow(
        title = "启动时自动续算中断的索引",
        subtitle = "上次算到一半被系统杀掉时，下次打开应用自动接着算；关闭后只在你手动点「开始」时继续。",
        checked = settings.autoResumeIndex,
        onCheckedChange = viewModel::setAutoResumeIndex,
    )
}

/**
 * 「文件权限」卡：整卡可点，进应用内的权限说明页（v0.2 不再一点就直接跳系统设置）。
 *
 * 不管是否已授权都显示按钮：已授权时用户同样需要一个地方去系统设置里改或关掉这项权限，
 * 之前「已授权就隐藏按钮」会让用户找不到关闭入口。
 */
@Composable
private fun StorageAccessCard(
    hasWriteAccess: Boolean,
    onOpenPermission: () -> Unit,
) {
    // 整块可点：保留原来「点权限区任意位置都进权限说明页」的行为，但不再套一层卡片。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenPermission),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (hasWriteAccess) {
                "已授予「所有文件访问」：可以真实移动文件、把图片移入 Eropic 回收站并恢复。"
            } else {
                "浏览与查重不需要这个权限。只有真实移动、移入回收站/恢复/彻底删除才需要它；" +
                    "未授权时这些入口会置灰，其他功能不受影响。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "状态：" + if (hasWriteAccess) "已授予" else "未授予",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onOpenPermission) { Text("权限说明与设置") }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 图库与查看分组：网格列数、标题栏元信息、默认排序、日期分组、幻灯片间隔。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GalleryViewCard(settings: AppSettings, viewModel: EropicViewModel) {
    var sortMenuOpen by remember { mutableStateOf(false) }

    Text(text = "每行显示", style = MaterialTheme.typography.labelLarge)
    // v0.6 起列数范围 2..8（7 档），用 FlowRow 自动换行，窄屏也不会挤出边界。
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        (AppSettings.MIN_GRID_COLUMNS..AppSettings.MAX_GRID_COLUMNS).forEach { columns ->
            FilterChip(
                selected = settings.gridColumns == columns,
                onClick = { viewModel.setGridColumns(columns) },
                label = { Text(columns.toString() + " 列") },
            )
        }
    }

    SettingSwitchRow(
        title = "显示张数与总大小",
        subtitle = "标题栏副标题是否显示「N 张 · X GB」。",
        checked = settings.showMeta,
        onCheckedChange = viewModel::setShowMeta,
    )

    Text(text = "默认排序", style = MaterialTheme.typography.labelLarge)
    Box {
        OutlinedButton(onClick = { sortMenuOpen = true }) {
            Text(settings.defaultSort.label)
        }
        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label) },
                    onClick = {
                        viewModel.setDefaultSort(order)
                        sortMenuOpen = false
                    },
                    trailingIcon = {
                        if (settings.defaultSort == order) {
                            Icon(imageVector = Icons.Filled.Check, contentDescription = null)
                        }
                    },
                )
            }
        }
    }

    Text(text = "日期分组", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DateGroupMode.entries.forEach { mode ->
            FilterChip(
                selected = settings.dateGroupMode == mode,
                onClick = { viewModel.setDateGroupMode(mode) },
                label = { Text(mode.label) },
            )
        }
    }
    Text(
        text = "按月/按日会在图库里插入分组标题；不分组是一整块时间线。双指捏合仍可临时改列数。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text(text = "幻灯片间隔", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(2, 3, 5, 8).forEach { seconds ->
            FilterChip(
                selected = settings.slideshowIntervalSec == seconds,
                onClick = { viewModel.setSlideshowInterval(seconds) },
                label = { Text(seconds.toString() + " 秒") },
            )
        }
    }
    Text(
        text = "查看器里点「幻灯片」按此间隔自动翻页，点屏幕任意位置停止。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 清理与回收站策略：保留期、相似判定档位。
 */
@Composable
private fun CleanupSettingsCard(settings: AppSettings, viewModel: EropicViewModel) {
    Text(text = "回收站保留时长", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RetentionOption.entries.forEach { option ->
            FilterChip(
                selected = settings.retention == option,
                onClick = { viewModel.setRetention(option) },
                label = { Text(option.label) },
            )
        }
    }
    Text(
        text = "到期后会提示你清理，不会静默删除；「永久保留」则一直留着直到你手动清空。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text(text = "相似判定档位", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SimilarityLevel.entries.forEach { level ->
            FilterChip(
                selected = settings.similarity == level,
                onClick = { viewModel.setSimilarity(level) },
                label = { Text(level.label) },
            )
        }
    }
    Text(
        text = "档位越宽松，判为相似的阈值越低、误报越多；改完需重新扫描相似分组才生效。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 扫描范围：是否包含隐藏目录、排除哪些目录。改完在下次「增量扫描」生效。
 */
@Composable
private fun ScanScopeCard(settings: AppSettings, viewModel: EropicViewModel) {
    var draft by remember { mutableStateOf("") }

    // 系统目录选择器：只取一次性的树 URI 用来算相对路径，不申请、也不持久化 URI 权限。
    val browseLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val relativeDir = treeUriToRelativePath(uri)
            when {
                relativeDir == null -> viewModel.postMessage("只支持主存储（内部共享存储）里的目录，外置 SD 卡暂不支持")
                relativeDir.isEmpty() -> viewModel.postMessage("不能排除整个存储根目录，请选里面的子目录")
                else -> viewModel.addScanExcludedDir(relativeDir)
            }
        }
    }

    SettingSwitchRow(
        title = "包含隐藏目录",
        subtitle = "以 . 开头的目录（如缓存、缩略图目录）默认不索引。打开后可能会看到大量废图。",
        checked = settings.scanIncludeHidden,
        onCheckedChange = viewModel::setScanIncludeHidden,
    )

    Text(text = "排除目录", style = MaterialTheme.typography.labelLarge)
    if (settings.scanExcludedDirs.isEmpty()) {
        Text(
            text = "没有排除目录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        settings.scanExcludedDirs.forEach { dir ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = dir, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.removeScanExcludedDir(dir) }) { Text("移除") }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("如 DCIM/Screenshots") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = draft.isNotBlank(),
            onClick = {
                viewModel.addScanExcludedDir(draft)
                draft = ""
            },
        ) { Text("添加") }
    }
    // 手动输入容易敲错，给一个走系统文件选择器的入口；选完仍会存成相对路径。
    OutlinedButton(onClick = { browseLauncher.launch(null) }) {
        Text("浏览选择目录…")
    }
    Text(
        text = "目录用相对路径（相对存储根）。改完在下次「增量扫描」生效，已索引的行不会自动移除。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 二级页面入口（底部栏放不下的页面）。
 */
@Composable
private fun MorePagesCard(viewModel: EropicViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { viewModel.openScreen(AppScreen.Storage) },
            modifier = Modifier.weight(1f),
        ) { Text("存储总览") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { viewModel.openScreen(AppScreen.History) },
            modifier = Modifier.weight(1f),
        ) { Text("操作历史") }
        OutlinedButton(
            onClick = { viewModel.openScreen(AppScreen.FolderManager) },
            modifier = Modifier.weight(1f),
        ) { Text("文件夹管理") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { viewModel.openScreen(AppScreen.CleanupReport) },
            modifier = Modifier.weight(1f),
        ) { Text("清理报告") }
    }
}

/**
 * 把 SAF 选中的目录 URI 转成「相对存储根」的路径，例如 primary:Pictures/Camera → Pictures/Camera。
 *
 * 返回 null 表示选中的不是主存储（外置 SD 卡/云端等）；返回空串表示选中的就是存储根本身。
 * 只读一次性的 documentId，不保存 URI，所以不需要 takePersistableUriPermission。
 */
private fun treeUriToRelativePath(uri: Uri): String? {
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val parts = documentId.split(":", limit = 2)
    if (parts.size < 2) return null
    if (parts[0] != "primary") return null
    return parts[1].trim('/')
}
