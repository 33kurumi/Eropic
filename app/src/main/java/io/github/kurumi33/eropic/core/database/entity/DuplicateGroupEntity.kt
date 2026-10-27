package io.github.kurumi33.eropic.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 重复/相似分组。M0 只读，不提供删除动作。 */
@Entity(tableName = "duplicate_group")
data class DuplicateGroupEntity(
    @PrimaryKey(autoGenerate = true) val groupId: Long = 0,
    /** EXACT 或 NEAR */
    val kind: String,
    /** 组内最大汉明距离；精确重复为 0 */
    val maxDistance: Int,
    /** 若只保留一张（体积最大者），可释放的字节数 */
    val reclaimableBytes: Long,
    val memberCount: Int,
    val createdAtMs: Long,
)
