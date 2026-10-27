package io.github.kurumi33.eropic.core.model

/** 精确查重的轻量投影：先按大小分桶，再对同桶文件算快速哈希。 */
data class ExactCandidate(
    val id: Long,
    val path: String,
    val sizeBytes: Long,
    val mimeType: String,
    val dateModifiedMs: Long,
)
