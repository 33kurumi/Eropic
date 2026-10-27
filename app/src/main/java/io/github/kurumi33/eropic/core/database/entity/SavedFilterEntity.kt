package io.github.kurumi33.eropic.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 保存的筛选条件（对应规划 B-17）。
 *
 * 条件本身序列化成 JSON 存 [filterJson]，见 [io.github.kurumi33.eropic.core.query.SavedFilterCodec]：
 * MediaFilter 字段还会变，用一张字段全展开的表意味着每加一个筛选维度都要写迁移。
 */
@Entity(tableName = "saved_filter")
data class SavedFilterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val filterJson: String,
    val createdAtMs: Long,
)
