package io.github.kurumi33.eropic.ui.trash

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.database.entity.TrashItemEntity
import io.github.kurumi33.eropic.core.fileops.OpFailure
import io.github.kurumi33.eropic.ui.AppScreen
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount
import io.github.kurumi33.eropic.ui.components.ConfirmDialog
import io.github.kurumi33.eropic.ui.components.WriteAccessHintBar
import kotlinx.coroutines.launch
import java.io.File

/** 需要二次确认的破坏性操作。 */
private enum class TrashConfirmAction { DELETE_SELECTED, EMPTY_ALL }

/** 默认回收站目录名（与 core/trash 里的约定一致）。 */
private const val TRASH_DIR_NAME = ".EropicTrash"

/**
 * 回收站页面（v0.2：从底栏 Tab 移到「清理」页里的二级页面）。
 *
 * "删除"其实是把文件同卷 rename 到 <卷根>/.EropicTrash/（瞬时、零拷贝），保留期跟随设置
 * 「清理与回收站 → 回收站保留时长」。目录优先用设置里的配置，配置目录写不进去时
 * 会自动回退到卷根或应用私有目录（卡片里写明实际在用的目录，避免用户以为丢图）。
 * 本页负责查看、恢复、永久删除、清空，以及配置回收站目录；
 * 所有改动文件的操作都依赖"所有文件访问"权限（[hasWriteAccess]），未授权时按钮全部禁用，
 * 只让用户查看内容与到期时间，不会出现点了没反应的操作。
 *
 * @param onMessage 一次性提示（Snackbar 文案），由上层决定怎么显示。
 * @param onBack 返回上一页（顶栏返回箭头与系统返回键都会走它）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    viewModel: EropicViewModel,
    hasWriteAccess: Boolean,
    onMessage: (String) -> Unit,
    onBack: () -> Unit,
) {
    val trashItems by viewModel.trashItems.collectAsStateWithLifecycle()
    val trashCount by viewModel.trashCount.collectAsStateWithLifecycle()
    val trashTotalBytes by viewModel.trashTotalBytes.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<TrashConfirmAction?>(null) }
    var showLocationDialog by remember { mutableStateOf(false) }

    // ---- 回收站位置：设置变了就重新读一次真实目录（含回退链）与已有条目所在目录 ----
    var configuredDir by remember { mutableStateOf<String?>(null) }
    var effectiveDir by remember { mutableStateOf<String?>(null) }
    var dirCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var existingLocations by remember { mutableStateOf<List<String>>(emptyList()) }
    var locationError by remember { mutableStateOf<String?>(null) }
    var locationLoading by remember { mutableStateOf(true) }
    LaunchedEffect(settings.trashRelativeDir) {
        locationLoading = true
        locationError = null
        // 配置值只是"用户希望放哪"，真正会写进去的是回退链里第一个可写的目录，
        // 所以两个都读：前者用来判断要不要提示「已回退」，后者是给用户看的事实。
        configuredDir = runCatching { viewModel.configuredTrashDir() }
            .getOrElse {
                locationError = "读取配置目录失败：" + (it.message ?: it::class.simpleName)
                null
            }
        effectiveDir = runCatching { viewModel.effectiveTrashDir() }
            .getOrElse {
                locationError = "读取实际目录失败：" + (it.message ?: it::class.simpleName)
                null
            }
        dirCandidates = runCatching { viewModel.trashDirCandidates() }.getOrDefault(emptyList())
        existingLocations = runCatching { viewModel.trashLocations() }.getOrDefault(emptyList())
        locationLoading = false
    }

    // 恢复/永久删除后条目会从 Flow 里消失，把已经不存在的 id 从选中集合剔掉，
    // 否则「恢复所选」会一直亮着，点了却什么也不做。
    LaunchedEffect(trashItems) {
        val alive = trashItems.map { it.id }.toSet()
        if (!alive.containsAll(selectedIds)) selectedIds = selectedIds intersect alive
    }

    /**
     * 统一执行入口：防重复点击、把结果转成 Snackbar 文案、异常不崩界面。
     *
     * 失败原因里如果出现「无法创建回收站目录 / 不可写」，说明是目录写不进去，
     * 只回一句"失败"用户没法自救，所以在提示末尾直接给出下一步（换个目录或补权限）。
     */
    fun runTrashAction(label: String, action: suspend () -> TrashActionOutcome) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val outcome = action()
                var text = outcome.message
                if (outcome.failures.any { looksLikeTrashDirProblem(it.reason) }) {
                    text += "；" + TrashDirHint
                }
                onMessage(text)
                selectedIds = emptySet()
            } catch (t: Throwable) {
                val reason = t.message ?: t::class.simpleName ?: "未知错误"
                val suffix = if (looksLikeTrashDirProblem(reason)) "；" + TrashDirHint else ""
                onMessage(label + "失败：" + reason + suffix)
            } finally {
                busy = false
            }
        }
    }

    val retention = settings.retention
    val retentionText = if (retention.days <= 0) "永久保留" else "默认保留 " + retention.label
    val subtitle = formatCount(trashCount.toLong()) + " 项 · " + formatBytes(trashTotalBytes) +
        " · " + retentionText
    val selectionEmpty = selectedIds.isEmpty()
    val trashEmpty = trashItems.isEmpty()

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    // 操作进行中给一个小指示器：恢复大量文件可能要好几百毫秒。
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp).size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            )

            // 没有"所有文件访问"时先提示，再把所有写按钮禁用——只有本页需要写盘。
            // 整条提示都可点（不只是「去授权」三个字）：进应用内的权限说明页，那里再跳系统设置。
            if (!hasWriteAccess) {
                WriteAccessHintBar(
                    onOpenPermission = { viewModel.openScreen(AppScreen.Permission) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            // 四个操作按钮横排在顶部；窄屏可横向滚动，不会被挤成两行。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        val ids = trashItems.map { it.id }
                        runTrashAction("全部恢复") {
                            val result = viewModel.restoreFromTrash(ids)
                            TrashActionOutcome(result.message, result.failures)
                        }
                    },
                    enabled = hasWriteAccess && !busy && !trashEmpty,
                ) { Text("全部恢复") }

                OutlinedButton(
                    onClick = {
                        val ids = selectedIds.toList()
                        runTrashAction("恢复所选") {
                            val result = viewModel.restoreFromTrash(ids)
                            TrashActionOutcome(result.message, result.failures)
                        }
                    },
                    enabled = hasWriteAccess && !busy && !selectionEmpty,
                ) { Text("恢复所选") }

                Button(
                    onClick = { confirmAction = TrashConfirmAction.DELETE_SELECTED },
                    enabled = hasWriteAccess && !busy && !selectionEmpty,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("永久删除所选") }

                OutlinedButton(
                    onClick = { confirmAction = TrashConfirmAction.EMPTY_ALL },
                    enabled = hasWriteAccess && !busy && !trashEmpty,
                ) { Text("清空回收站") }
            }

            if (!selectionEmpty) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已选 " + selectedIds.size + " 项",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { selectedIds = emptySet() }) { Text("取消选择") }
                }
            }

            HorizontalDivider()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 位置信息放在列表最上面：进页面就能看到，往下滚看条目。
                item(key = "trash-location") {
                    TrashLocationCard(
                        configuredDir = configuredDir,
                        effectiveDir = effectiveDir,
                        candidates = dirCandidates,
                        existingLocations = existingLocations,
                        loading = locationLoading,
                        error = locationError,
                        onChangeLocation = { showLocationDialog = true },
                    )
                }

                if (trashEmpty) {
                    item(key = "trash-empty") {
                        EmptyTrashState(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            retentionDays = retention.days,
                        )
                    }
                } else {
                    items(trashItems, key = { it.id }) { item ->
                        TrashRow(
                            item = item,
                            selected = item.id in selectedIds,
                            enabled = hasWriteAccess && !busy,
                            onClick = {
                                selectedIds = if (item.id in selectedIds) {
                                    selectedIds - item.id
                                } else {
                                    selectedIds + item.id
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // 永久删除与清空都必须二次确认：这两个操作真的不可恢复。
    confirmAction?.let { action ->
        when (action) {
            TrashConfirmAction.DELETE_SELECTED -> ConfirmDialog(
                title = "永久删除所选？",
                text = "将永久删除选中的 " + selectedIds.size + " 项回收站文件，永久删除后无法恢复。",
                confirmText = "永久删除",
                destructive = true,
                onConfirm = {
                    val ids = selectedIds.toList()
                    runTrashAction("永久删除") {
                        val result = viewModel.deleteFromTrashPermanently(ids)
                        TrashActionOutcome(result.message, result.failures)
                    }
                },
                onDismiss = { confirmAction = null },
            )

            TrashConfirmAction.EMPTY_ALL -> ConfirmDialog(
                title = "清空回收站？",
                text = "将永久删除回收站里的 " + formatCount(trashCount.toLong()) + " 项（" +
                    formatBytes(trashTotalBytes) + "），永久删除后无法恢复。",
                confirmText = "清空回收站",
                destructive = true,
                onConfirm = {
                    runTrashAction("清空回收站") {
                        val result = viewModel.emptyTrashNow()
                        TrashActionOutcome(result.message, result.failures)
                    }
                },
                onDismiss = { confirmAction = null },
            )
        }
    }

    if (showLocationDialog) {
        TrashLocationDialog(
            currentRelativeDir = settings.trashRelativeDir,
            onDismiss = { showLocationDialog = false },
            onConfirm = { dir ->
                showLocationDialog = false
                viewModel.setTrashRelativeDir(dir)
                onMessage(
                    if (dir.isBlank()) {
                        "回收站位置已改为默认（<卷根>/.EropicTrash）"
                    } else {
                        "回收站位置已改为 " + dir + "；已有条目仍留在原目录"
                    },
                )
            },
        )
    }
}

/**
 * 「回收站位置」卡片：显示当前真实目录、已有条目散落的目录，并提供修改入口。
 *
 * 改目录只影响新移入的条目；老条目仍留在原目录，恢复时按各行记录的原始路径处理，
 * 所以这里要把"会散在多个目录"这件事明说，避免用户以为换个位置老文件会跟着搬家。
 */
@Composable
private fun TrashLocationCard(
    configuredDir: String?,
    effectiveDir: String?,
    candidates: List<String>,
    existingLocations: List<String>,
    loading: Boolean,
    error: String?,
    onChangeLocation: () -> Unit,
) {
    val configured = configuredDir
    val effective = effectiveDir
    // 配置值和实际值不一致 = 回退发生了（卷根不可写时会退到私有目录），必须明说，
    // 否则用户看到目录变了会以为文件被搬走了。
    val fellBack = !loading && configured != null && effective != null &&
        normalizeDirPath(configured) != normalizeDirPath(effective)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = "回收站位置", style = MaterialTheme.typography.titleMedium)
            // 用户真正关心「文件现在挪到哪了」，所以正文给实际在用的目录，配置值只在回退时出现。
            Text(
                text = "正在使用：" + when {
                    loading -> "读取中…"
                    effective != null -> effective
                    else -> "未知"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!loading && candidates.isNotEmpty()) {
                Text(
                    text = "按优先级尝试：" + candidates.joinToString(" → "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (fellBack && configured != null && effective != null) {
                Text(
                    text = "配置的位置不可写，已自动回退到这里，照片仍然只是改名移动、可正常恢复。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "配置的位置：" + configured,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!loading && error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (existingLocations.isNotEmpty()) {
                Text(
                    text = "已有条目所在目录",
                    style = MaterialTheme.typography.labelLarge,
                )
                existingLocations.forEach { location ->
                    Text(
                        text = location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = "已存在的条目留在原目录仍可恢复；新移入的图片会放到新位置。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onChangeLocation) { Text("更改位置") }
        }
    }
}

/** 「更改位置」对话框的四个预设（空串表示默认：<卷根>/.EropicTrash）。 */
private enum class TrashLocationPreset(val label: String, val relativeDir: String) {
    DEFAULT("默认：<卷根>/.EropicTrash", ""),
    PICTURES("Pictures/.EropicTrash", "Pictures/.EropicTrash"),
    DCIM("DCIM/.EropicTrash", "DCIM/.EropicTrash"),
    CUSTOM("自定义相对路径", ""),
}

/**
 * 回收站位置选择对话框。
 *
 * 选择结果交给 [EropicViewModel.setTrashRelativeDir]，规范化（自动补 .EropicTrash、
 * 非法输入退回默认）由 SettingsRepository 负责；这里只做"人话"校验，避免用户点了保存却什么都没变。
 */
@Composable
private fun TrashLocationDialog(
    currentRelativeDir: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val current = currentRelativeDir.trim().trim('/')
    val matched = when (current) {
        "" -> TrashLocationPreset.DEFAULT
        "Pictures", "Pictures/" + TRASH_DIR_NAME -> TrashLocationPreset.PICTURES
        "DCIM", "DCIM/" + TRASH_DIR_NAME -> TrashLocationPreset.DCIM
        else -> TrashLocationPreset.CUSTOM
    }
    var preset by remember { mutableStateOf(matched) }
    var draft by remember { mutableStateOf(if (matched == TrashLocationPreset.CUSTOM) current else "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更改回收站位置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TrashLocationPreset.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                preset = option
                                error = null
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = preset == option,
                            onClick = {
                                preset = option
                                error = null
                            },
                        )
                        Text(text = option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (preset == TrashLocationPreset.CUSTOM) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            error = null
                        },
                        label = { Text("如 Pictures/Camera") },
                        supportingText = { Text("会自动补成 <目录>/.EropicTrash") },
                        singleLine = true,
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (error != null) {
                    Text(
                        text = error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Text(
                    text = "目录不可写时会自动回退到应用私有目录，不会导致图片丢失。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "换位置后，已有条目留在原目录仍可恢复；新移入的才放到新位置。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val resolved = when (preset) {
                        TrashLocationPreset.DEFAULT -> ""
                        TrashLocationPreset.PICTURES -> TrashLocationPreset.PICTURES.relativeDir
                        TrashLocationPreset.DCIM -> TrashLocationPreset.DCIM.relativeDir
                        TrashLocationPreset.CUSTOM -> draft.trim()
                    }
                    val invalid = if (preset == TrashLocationPreset.CUSTOM) {
                        validateTrashRelativeDir(resolved)
                    } else {
                        null
                    }
                    if (invalid != null) {
                        error = invalid
                    } else {
                        onConfirm(resolved)
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 自定义目录的本地校验：只允许相对路径。
 *
 * 绝对路径、盘符/scheme、反斜杠、".."、以点开头的目录都在这里挡掉；
 * 空串表示"回到默认位置"，由对话框的第一项负责，自定义输入框不允许留空。
 */
private fun validateTrashRelativeDir(raw: String): String? {
    if (raw.isEmpty()) return "请输入相对路径"
    if (raw.startsWith("/")) return "不能是绝对路径，请填相对存储根的路径"
    if (raw.contains(':')) return "不要带盘符或 scheme"
    if (raw.contains('\\')) return "请用 / 分隔目录"
    if (raw.startsWith(".")) return "不能以点开头（默认位置请选第一项）"
    val segments = raw.split('/')
    if (segments.any { it.isEmpty() }) return "路径里不要出现连续的 /"
    if (segments.any { it == ".." || it == "." }) return "路径里不能包含 . 或 .."
    return null
}

@Composable
private fun EmptyTrashState(modifier: Modifier = Modifier, retentionDays: Int) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.DeleteSweep,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = "回收站是空的",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "从图库选中图片删除后会先移到这里，" +
                (if (retentionDays <= 0) "永久保留，直到你手动清空" else retentionDays.toString() + " 天内可随时恢复"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * 单条回收站记录。
 *
 * 缩略图直接读回收站里的绝对路径（用 Uri.fromFile 交给 Coil），文件已被外部删除时
 * 只留一个图标占位，不显示破图。点击切换多选。
 */
@Composable
private fun TrashRow(
    item: TrashItemEntity,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // 到期文案只要一个"现在"，不必每帧读系统时钟。
    val nowMs = remember(item.id, item.expiresAtMs) { System.currentTimeMillis() }
    val exists = remember(item.trashPath) { File(item.trashPath).exists() }

    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (exists) {
                    AsyncImage(
                        model = Uri.fromFile(File(item.trashPath)),
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.originalPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatBytes(item.sizeBytes) + " · " + expiryText(item.expiresAtMs, nowMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = if (selected) "已选中" else "未选中",
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** 到期文案："还剩 N 天"（向上取整）；已过期则提示等待自动清理。 */
private fun expiryText(expiresAtMs: Long, nowMs: Long): String {
    val left = expiresAtMs - nowMs
    if (left <= 0L) return "已到期，等待自动清理"
    val days = ((left + DAY_MS - 1) / DAY_MS).toInt()
    return "还剩 " + days + " 天"
}

private const val DAY_MS = 24L * 60L * 60L * 1000L

/** 目录写不进去时给用户的下一步；Snackbar 与卡片提示共用同一句，避免口径不一致。 */
private const val TrashDirHint = "可到「回收站位置」里换个目录，或到设置里确认「所有文件访问」权限"

/**
 * 失败原因看起来是「回收站目录写不进去」吗？
 *
 * 这两个词分别来自 TrashRepository 的移入失败（无法创建回收站目录）与恢复失败
 * （恢复失败（目标目录可能不可写））；命中才追加换目录引导，其它失败不打扰用户。
 */
private fun looksLikeTrashDirProblem(reason: String): Boolean =
    reason.contains("无法创建回收站目录") || reason.contains("不可写")

/** 动作结果 + 逐条失败原因：失败原因决定 Snackbar 要不要追加换目录引导。 */
private data class TrashActionOutcome(
    val message: String,
    val failures: List<OpFailure> = emptyList(),
)

/** 比较两个目录时忽略末尾的 /，避免同一个路径因为写法不同被误判成「回退」。 */
private fun normalizeDirPath(path: String): String = path.trimEnd('/')
