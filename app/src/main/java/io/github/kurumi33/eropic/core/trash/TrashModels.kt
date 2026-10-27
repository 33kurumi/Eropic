package io.github.kurumi33.eropic.core.trash

/** 移入回收站的结果。 */
data class TrashResult(
    val trashed: Int,
    val failed: Int,
    val bytes: Long,
    /** 本次新建的回收站条目 id，用于「撤销」一键恢复 */
    val trashIds: List<Long>,
    val message: String,
)

/** 从回收站恢复的结果。 */
data class RestoreResult(
    val restored: Int,
    val failed: Int,
    val message: String,
)

/** 永久删除的结果。 */
data class DeleteResult(
    val deleted: Int,
    val failed: Int,
    val bytes: Long,
    val message: String,
)
