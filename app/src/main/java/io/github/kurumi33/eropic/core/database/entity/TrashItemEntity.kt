package io.github.kurumi33.eropic.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 回收站条目：文件被"移入回收站"后的记录。
 *
 * 文件本身被同卷 rename 到 /<卷根>/.EropicTrash/ 下（零拷贝、瞬间完成），
 * 这里只保存它原来在哪、现在在哪、什么时候过期，用于恢复与 30 天清理。
 */
@Entity(tableName = "trash_item")
data class TrashItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 移入前的 MediaStore _ID，仅用于展示/日志，恢复后 id 可能变化 */
    val mediaId: Long,
    val displayName: String,
    /** 回收站里的绝对路径 */
    val trashPath: String,
    /** 原始绝对路径，恢复时按它放回去 */
    val originalPath: String,
    val sizeBytes: Long,
    val mimeType: String,
    val trashedAtMs: Long,
    /** 到期时间；超过后由清理任务永久删除 */
    val expiresAtMs: Long,
)
