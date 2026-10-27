package io.github.kurumi33.eropic.core.model

/** 按 relativePath 聚合出的一行文件夹统计。 */
data class FolderRow(
    val relativePath: String,
    val mediaCount: Long,
    val totalBytes: Long,
)
