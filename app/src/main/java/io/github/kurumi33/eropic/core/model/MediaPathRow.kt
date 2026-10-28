package io.github.kurumi33.eropic.core.model

/**
 * 文件夹操作（重命名 / 整目录移入回收站）时用的轻量行。
 *
 * 只取四个字段而不是整行 [io.github.kurumi33.eropic.core.database.entity.MediaEntity]：
 * 一个目录可能有几千张图，整行会把指纹、时间戳等用不到的数据全读进内存。
 */
data class MediaPathRow(
    val id: Long,
    val path: String,
    val relativePath: String,
    val displayName: String,
)
