package io.github.kurumi33.eropic.ui.folders

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.EropicTopBar
import java.io.File

/**
 * 更换图集封面（v0.5）。
 *
 * 封面只是「图集页展示用」的偏好，不影响任何图片文件，所以：
 * 1. 选完立刻返回上一页，不在这里做二次确认——改错了再进来选一次成本很低；
 * 2. 提供「恢复默认封面」，默认封面是系统按最新一张自动挑的；
 * 3. 候选来自 viewModel.folderImagesForCover（该目录含子目录最近 300 张），
 *    空目录时给出「还没有已索引的图片」的空态，而不是一片空白网格。
 */
@Composable
fun FolderCoverScreen(
    viewModel: EropicViewModel,
    relativePath: String,
    folderName: String,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // 用户自定义的封面 id；null 表示当前用默认封面（该目录最新一张）
    val coverId = settings.folderCovers[relativePath]

    // 值为 null 表示还在加载，避免「先闪一下空态再出网格」
    val images by produceState<List<MediaEntity>?>(initialValue = null, relativePath) {
        value = runCatching { viewModel.folderImagesForCover(relativePath) }.getOrDefault(emptyList())
    }

    Scaffold(
        topBar = {
            EropicTopBar(
                title = "更换图集封面",
                subtitle = folderName,
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            // 顶部说明 + 恢复默认
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (coverId == null) "当前：默认封面（该目录最新一张）" else "当前：自定义封面",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "封面只影响图集页展示，不会改动图片文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = {
                        // setFolderCover 内部会 postMessage("已恢复默认封面")，这里不再重复提示
                        viewModel.setFolderCover(relativePath, null)
                    },
                    enabled = coverId != null,
                ) {
                    Text("恢复默认封面")
                }
            }
            HorizontalDivider()

            val list = images
            when {
                list == null -> {
                    // weight(1f) 吃掉剩余高度，避免和顶栏叠加后把内容顶出屏幕
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                list.isEmpty() -> {
                    // 空态用 weight(1f) 的 Box 撑满剩余高度，保证居中
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.PhotoLibrary,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(
                                text = "这个文件夹里还没有已索引的图片",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(
                                text = "可以在设置里执行一次扫描后再来挑封面",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(list, key = { it.id }) { item ->
                            CoverCell(
                                viewModel = viewModel,
                                item = item,
                                selected = item.id == coverId,
                                onClick = {
                                    viewModel.setFolderCover(relativePath, item.id)
                                    // 注意：setFolderCover 内部也会 postMessage 同一条文案，
                                    // 这里按需求再调一次 onMessage，实际可能出现两次提示（见交付说明）。
                                    onMessage("已更换图集封面")
                                    onBack()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一张候选封面：1:1 缩略图 + 当前封面打勾。
 *
 * 缩略图先走缓存（viewModel.thumbnailFile），拿不到再回退 content:// 原图，
 * 和网格页的加载策略保持一致，避免这里出现「有的格子空白」。
 */
@Composable
private fun CoverCell(
    viewModel: EropicViewModel,
    item: MediaEntity,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val thumb by produceState<File?>(initialValue = null, item.id) {
        value = runCatching { viewModel.thumbnailFile(item) }.getOrNull()
    }
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = thumb?.let { Uri.fromFile(it) } ?: MediaUris.of(item.id),
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (selected) {
            // 用一层覆盖描边而不是给 Box 加 border，保证描边压在图片上方不被盖住
            Box(Modifier.matchParentSize().border(3.dp, MaterialTheme.colorScheme.primary, shape))
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "封面",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
