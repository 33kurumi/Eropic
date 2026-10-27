package io.github.kurumi33.eropic.core.trash

import io.github.kurumi33.eropic.core.fileops.OpFailure

/** 移入回收站的结果。 */
data class TrashResult(
    val trashed: Int,
    val failed: Int,
    val bytes: Long,
    /** 本次新建的回收站条目 id，用于「撤销」一键恢复 */
    val trashIds: List<Long>,
    val message: String,
    /** M4：逐条失败原因，供结果报告页展示 */
    val failures: List<OpFailure> = emptyList(),
)

/** 从回收站恢复的结果。 */
data class RestoreResult(
    val restored: Int,
    val failed: Int,
    val message: String,
    val failures: List<OpFailure> = emptyList(),
)

/** 永久删除的结果。 */
data class DeleteResult(
    val deleted: Int,
    val failed: Int,
    val bytes: Long,
    val message: String,
    val failures: List<OpFailure> = emptyList(),
)

/** 一个待移入回收站的文件（不来自索引，例如"撤销复制"时要清掉复制出来的新文件）。 */
data class TrashFileRef(
    val path: String,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String = "image/*",
)
