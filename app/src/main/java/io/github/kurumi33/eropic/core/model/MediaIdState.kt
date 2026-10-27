package io.github.kurumi33.eropic.core.model

/**
 * 扫描增量比对用的轻量投影。
 *
 * 只比 [dateModifiedMs] 不够：系统相册加星不会改 mtime，外部改名/移动也可能不改 mtime，
 * 这些变化都要能触发索引更新（见 ADR-0018）。
 */
data class MediaIdState(
    val id: Long,
    val dateModifiedMs: Long,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val isFavorite: Boolean,
)
