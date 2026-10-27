package io.github.kurumi33.eropic.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 写操作日志：回收站、恢复、移动、永久删除都记一条，用于排查与"最近操作"。
 *
 * payloadJson 存受影响的 id 列表（JSON 数组字符串），恢复逻辑不依赖它，
 * 它是给用户/开发者看的历史记录。
 */
@Entity(tableName = "operation_log")
data class OperationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** TRASH / RESTORE / MOVE / DELETE / EMPTY_TRASH */
    val type: String,
    val summary: String,
    val payloadJson: String,
    val createdAtMs: Long,
    val undone: Boolean = false,
)
