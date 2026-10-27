package io.github.kurumi33.eropic.ui.gallery

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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.kurumi33.eropic.core.index.IndexPhase
import io.github.kurumi33.eropic.core.index.IndexState
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.core.media.SortOrder
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.formatBytes
import io.github.kurumi33.eropic.ui.common.formatCount
import io.github.kurumi33.eropic.ui.permission.MediaAccess
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: EropicViewModel,
    access: MediaAccess,
    onRequestFullAccess: () -> Unit,
    onOpenViewer: (ids: List<Long>, startIndex: Int) -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val count by viewModel.count.collectAsStateWithLifecycle()
    val totalSize by viewModel.totalSize.collectAsStateWithLifecycle()
    val indexState by viewModel.indexState.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.favoritesOnly.collectAsStateWithLifecycle()
    val items = viewModel.media.collectAsLazyPagingItems()
    val scope = rememberCoroutineScope()
    var sortMenuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(text = "图库")
                    if (settings.showMeta) {
                        Text(
                            text = formatCount(count) + " 张 · " + formatBytes(totalSize),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            actions = {
                IconButton(onClick = { viewModel.setShowMeta(!settings.showMeta) }) {
                    Icon(
                        imageVector = if (settings.showMeta) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = if (settings.showMeta) "隐藏文件数与大小" else "显示文件数与大小",
                    )
                }
                Box {
                    IconButton(onClick = { sortMenuOpen = true }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Sort, contentDescription = "排序方式")
                    }
                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                        SortOrder.entries.forEach { order ->
                            DropdownMenuItem(
                                text = { Text(order.label) },
                                onClick = {
                                    viewModel.setSortOrder(order)
                                    sortMenuOpen = false
                                },
                                trailingIcon = {
                                    if (sortOrder == order) {
                                        Icon(imageVector = Icons.Filled.Check, contentDescription = null)
                                    }
                                },
                            )
                        }
                    }
                }
            },
        )

        if (!access.full) {
            PartialAccessBanner(onRequestFullAccess)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = !favoritesOnly,
                onClick = { if (favoritesOnly) viewModel.toggleFavoritesFilter() },
                label = { Text("全部") },
            )
            FilterChip(
                selected = favoritesOnly,
                onClick = { if (!favoritesOnly) viewModel.toggleFavoritesFilter() },
                label = { Text("收藏") },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Favorite, contentDescription = null)
                },
            )
        }

        IndexBanner(indexState = indexState, onStart = viewModel::startIndex)

        when {
            indexState.phase == IndexPhase.SYNCING && count == 0L -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = "正在读取系统相册索引…",
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }

            items.itemCount == 0 -> {
                EmptyGallery(
                    indexState = indexState,
                    favoritesOnly = favoritesOnly,
                    onStart = viewModel::startIndex,
                )
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
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clickable(enabled = item != null) {
                                    val clicked = item ?: return@clickable
                                    scope.launch {
                                        // 查看器需要完整的顺序 id 列表（与当前排序/筛选一致），
                                        // 十万级只取 id 约 800KB，实测几十毫秒。
                                        val ids = viewModel.viewerIds()
                                        if (ids.isNotEmpty()) {
                                            val start = ids.indexOf(clicked.id).coerceAtLeast(0)
                                            onOpenViewer(ids, start)
                                        }
                                    }
                                },
                        ) {
                            if (item != null) {
                                AsyncImage(
                                    model = MediaUris.of(item.id),
                                    contentDescription = item.displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (item.localFavorite || item.isFavorite) {
                                    Icon(
                                        imageVector = Icons.Filled.Favorite,
                                        contentDescription = "已收藏",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(3.dp)
                                            .fillMaxSize(0.18f),
                                    )
                                }
                            }
                        }
                    }
                }
                if (items.loadState.refresh is LoadState.Loading) {
                    Text(
                        text = "加载中…",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PartialAccessBanner(onRequestFull: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "当前只授权了部分照片",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "这样图库会漏图。请允许访问所有照片。",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onRequestFull) { Text("允许访问所有照片") }
        }
    }
}

@Composable
private fun IndexBanner(indexState: IndexState, onStart: () -> Unit) {
    when {
        indexState.isRunning -> {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = {
                        val total = indexState.signatureTotal
                        if (indexState.phase == IndexPhase.HASHING && total > 0) {
                            (indexState.signatureDone.toFloat() / total).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    },
                )
                Text(
                    text = buildString {
                        append(indexState.message)
                        if (indexState.phase == IndexPhase.HASHING && indexState.signatureTotal > 0) {
                            append(" ")
                            append(indexState.signatureDone)
                            append("/")
                            append(indexState.signatureTotal)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        indexState.phase == IndexPhase.ERROR -> {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = indexState.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onStart) { Text("重试") }
            }
        }
    }
}

@Composable
private fun EmptyGallery(indexState: IndexState, favoritesOnly: Boolean, onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (favoritesOnly) {
            Text(text = "还没有收藏的图片", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "在图片查看器里点爱心即可收藏。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            return
        }
        Text(text = "还没有建立索引", style = MaterialTheme.typography.titleLarge)
        Text(
            text = if (indexState.phase == IndexPhase.DONE) {
                "没有找到可显示的照片：请确认相册权限，或重新建立索引。"
            } else {
                "Eropic 会读取系统相册数据库，把照片索引到本地。首次索引十万张照片通常需要几分钟。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = onStart) { Text("开始建立索引") }
    }
}
