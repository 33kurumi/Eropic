package io.github.kurumi33.eropic.ui.folders

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.formatCount
import kotlinx.coroutines.launch

/**
 * 单个文件夹的照片网格。
 *
 * 顶部的「包含子文件夹」开关控制查询范围：
 * - 关闭：只显示该目录直属的图片（folderMedia(path, false)）；
 * - 打开：该目录下所有子文件夹里的图片都显示出来（folderMedia(path, true)）。
 *
 * folderMedia 每次调用都会返回新的 Pager Flow，所以必须放进 remember 的 key 里，
 * 切换开关时重新订阅，才能拿到新范围的分页数据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderDetailScreen(
    viewModel: EropicViewModel,
    folderPath: String,
    folderName: String,
    onBack: () -> Unit,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
) {
    val isRoot = folderPath.isEmpty()
    var includeSubfolders by rememberSaveable { mutableStateOf(false) }
    // 根目录没有子文件夹：开关禁用，查询始终按「只看本目录」走。
    val effectiveIncludeSubfolders = includeSubfolders && !isRoot
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val items = remember(folderPath, effectiveIncludeSubfolders) {
        viewModel.folderMedia(folderPath, effectiveIncludeSubfolders)
    }.collectAsLazyPagingItems()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                    )
                }
            },
            title = {
                Column {
                    Text(text = folderName)
                    Text(
                        text = buildString {
                            append(if (isRoot) "根目录" else folderPath)
                            append(" · ")
                            append(if (effectiveIncludeSubfolders) "含子文件夹" else "仅本文件夹")
                            append(" ")
                            append(formatCount(items.itemCount.toLong()))
                            append(" 张")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )

        SubfolderModeBar(
            includeSubfolders = includeSubfolders,
            enabled = !isRoot,
            isRoot = isRoot,
            folderName = folderName,
            onToggle = { includeSubfolders = it },
        )

        when {
            items.itemCount == 0 && items.loadState.refresh is LoadState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = "正在读取…",
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }

            items.itemCount == 0 -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "这个文件夹下没有照片",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(settings.gridColumns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(count = items.itemCount) { index ->
                        val item = items[index]
                        if (item != null) {
                            AsyncImage(
                                model = MediaUris.of(item.id),
                                contentDescription = item.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clickable {
                                        scope.launch {
                                            // 查看器的顺序必须与网格保持一致（排序/筛选/文件夹范围都一样）。
                                            val ids = viewModel.folderIds(folderPath, effectiveIncludeSubfolders)
                                            val startIndex = ids.indexOf(item.id).coerceAtLeast(0)
                                            onOpenViewer(ids, startIndex)
                                        }
                                    },
                            )
                        } else {
                            // enablePlaceholders = false，理论上不会有 null，占位以防数据在翻页时被删除。
                            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                        }
                    }
                }
            }
        }
    }
}

/** 顶部开关 + 当前模式说明。 */
@Composable
private fun SubfolderModeBar(
    includeSubfolders: Boolean,
    enabled: Boolean,
    isRoot: Boolean,
    folderName: String,
    onToggle: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "包含子文件夹",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = when {
                        isRoot -> "根目录没有子文件夹，这里只显示落在根目录下的照片。"
                        includeSubfolders -> "当前显示「$folderName」及其所有子文件夹里的照片。"
                        else -> "当前只显示「$folderName」里的照片。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = includeSubfolders,
                onCheckedChange = onToggle,
                enabled = enabled,
            )
        }
        Text(
            text = "关闭时只看当前文件夹，打开后包含所有子文件夹的照片。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
    }
}
