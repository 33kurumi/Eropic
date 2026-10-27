package io.github.kurumi33.eropic.core.database.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "duplicate_member",
    primaryKeys = ["groupId", "mediaId"],
    indices = [Index(value = ["mediaId"])],
)
data class DuplicateMemberEntity(
    val groupId: Long,
    val mediaId: Long,
    /** 与组内代表图的 dHash 距离；精确重复为 0 */
    val distance: Int,
)
