package io.github.kurumi33.eropic.core.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * dHash 的 4 段 16 bit 倒排索引。
 *
 * 两张图的 64 bit 汉明距离 <= 10 时，由鸽巢原理至少有一段 16 bit 的距离 <= 2，
 * 因此查候选时每段只需探测 XOR popcount <= 2 的桶。
 */
@Entity(
    tableName = "hash_band",
    primaryKeys = ["mediaId", "bandNo"],
    indices = [Index(value = ["bandNo", "bandVal"])],
)
data class HashBandEntity(
    val mediaId: Long,
    val bandNo: Int,
    val bandVal: Int,
)
