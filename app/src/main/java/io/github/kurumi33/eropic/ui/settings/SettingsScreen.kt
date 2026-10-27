package io.github.kurumi33.eropic.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import io.github.kurumi33.eropic.core.index.IndexPhase
import io.github.kurumi33.eropic.core.permissions.StorageAccess
import io.github.kurumi33.eropic.core.media.SortOrder
import io.github.kurumi33.eropic.core.settings.AppSettings
import io.github.kurumi33.eropic.core.settings.RetentionOption
import io.github.kurumi33.eropic.core.settings.SimilarityLevel
import io.github.kurumi33.eropic.core.settings.ThemeMode
import io.github.kurumi33.eropic.ui.AppScreen
import io.github.kurumi33.eropic.ui.EropicViewModel
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
        TopAppBar(title = { Text("设置") })
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppearanceCard(settings = settings, viewModel = viewModel)

            ThumbnailCacheCard(
                settings = settings,
                cacheSizeBytes = thumbnailCacheSize,
                viewModel = viewModel,
            )

            BackgroundCard(settings = settings, viewModel = viewModel)

            GalleryViewCard(settings = settings, viewModel = viewModel)

            CleanupSettingsCard(settings = settings, viewModel = viewModel)

            ScanScopeCard(settings = settings, viewModel = viewModel)

            MorePagesCard(viewModel = viewModel)

            StorageAccessCard(
                hasWriteAccess = hasWriteAccess,
                onRequestAccess = {
                    runCatching { context.startActivity(StorageAccess.requestIntent(context)) }
                },
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "本地索引", style = MaterialTheme.typography.titleMedium)
                    Text(text = "已索引 " + formatCount(count) + " 张 · " + formatBytes(totalSize))
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
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "M4 范围", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "已实现：浏览/排序/收藏/查看器/幻灯片、文件夹树与聚合、缩略图磁盘缓存、自动同步、多选、" +
                            "指纹与精确/相似重复分组、八类清理入口与组合筛选、对比预览、保留建议、清理报告、" +
                            "真实移动/复制/重命名文件、空文件夹管理、回收站（可配置保留期，可撤销/恢复/彻底删除）、" +
                            "操作历史与撤销闭环、搜索与保存筛选、存储总览、崩溃日志。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "关于", style = MaterialTheme.typography.titleMedium)
                    Text(text = "Eropic " + versionName + " · 本地优先 · 无网络权限")
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
                        Text("权限、数据与许可详情")
                    }
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

@Composable
private fun AppearanceCard(settings: AppSettings, viewModel: EropicViewModel) {
    var sortMenuOpen by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "外观与图库", style = MaterialTheme.typography.titleMedium)

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

            Text(text = "每行显示", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (3..6).forEach { columns ->
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
        }
    }
}

@Composable
private fun ThumbnailCacheCard(
    settings: AppSettings,
    cacheSizeBytes: Long,
    viewModel: EropicViewModel,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "缩略图缓存", style = MaterialTheme.typography.titleMedium)
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    viewModel.clearThumbnailCache()
                }) { Text("清空缓存") }
                OutlinedButton(onClick = viewModel::backfillThumbnails) { Text("充电时补齐") }
            }
        }
    }
}

@Composable
private fun BackgroundCard(settings: AppSettings, viewModel: EropicViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "后台与电量", style = MaterialTheme.typography.titleMedium)
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
    }
}

@Composable
private fun StorageAccessCard(
    hasWriteAccess: Boolean,
    onRequestAccess: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "文件权限", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (hasWriteAccess) {
                    "已授予「所有文件访问」：可以真实移动文件、把图片移入 Eropic 回收站并恢复。";
                } else {
                    "浏览与查重不需要这个权限。只有真实移动、移入回收站/恢复/彻底删除才需要它；" +
                        "未授权时这些入口会置灰，其他功能不受影响。";
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "状态：" + if (hasWriteAccess) "已授予" else "未授予",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!hasWriteAccess) {
                Button(onClick = onRequestAccess) { Text("去系统设置授权") }
            }
        }
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
 * 图库显示与查看器偏好（M4 · T3 的捏合列数、日期分组、幻灯片都由这里配置）。
 */
@Composable
private fun GalleryViewCard(settings: AppSettings, viewModel: EropicViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "图库与查看", style = MaterialTheme.typography.titleMedium)

            SettingSwitchRow(
                title = "按日期分组",
                subtitle = "图库按拍摄月份插入分组标题；双指捏合仍可临时改列数。",
                checked = settings.dateGrouping,
                onCheckedChange = viewModel::setDateGrouping,
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
    }
}

/**
 * 清理与回收站策略：保留期、相似判定档位。
 */
@Composable
private fun CleanupSettingsCard(settings: AppSettings, viewModel: EropicViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "清理与回收站", style = MaterialTheme.typography.titleMedium)

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
    }
}

/**
 * 扫描范围：是否包含隐藏目录、排除哪些目录。改完在下次「增量扫描」生效。
 */
@Composable
private fun ScanScopeCard(settings: AppSettings, viewModel: EropicViewModel) {
    var draft by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "扫描范围", style = MaterialTheme.typography.titleMedium)

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
            Text(
                text = "目录用相对路径（相对存储根）。改完在下次「增量扫描」生效，已索引的行不会自动移除。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 二级页面入口（底部栏放不下的页面）。
 */
@Composable
private fun MorePagesCard(viewModel: EropicViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "更多页面", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.openScreen(AppScreen.Search) },
                    modifier = Modifier.weight(1f),
                ) { Text("搜索") }
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
                OutlinedButton(
                    onClick = { viewModel.openScreen(AppScreen.About) },
                    modifier = Modifier.weight(1f),
                ) { Text("关于") }
            }
        }
    }
}
