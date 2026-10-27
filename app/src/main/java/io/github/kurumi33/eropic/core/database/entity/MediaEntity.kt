package io.github.kurumi33.eropic.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一张图片在本地索引中的记录。
 *
 * 事实源是 MediaStore，本表是可以随时重建的缓存（见 ADR-0003）；
 * 但 [dhash] / [phash] / [sha256] 计算成本高，重建后需要重新计算，因此要尽量保留。
 */
@Entity(
    tableName = "media_item",
    indices = [
        Index(value = ["bucketId", "dateTakenMs"]),
        Index(value = ["dateTakenMs"]),
        Index(value = ["sizeBytes"]),
        Index(value = ["path"]),
        Index(value = ["dhash"]),
        Index(value = ["sha256"]),
    ],
)
data class MediaEntity(
    /** MediaStore 的 _ID（合并卷内唯一） */
    @PrimaryKey val id: Long,
    val volumeName: String,
    /** 绝对路径，用于读图/移动/信息展示 */
    val path: String,
    val displayName: String,
    /** 相对路径，例如 DCIM/Camera/ */
    val relativePath: String,
    val bucketId: Long,
    val bucketName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateTakenMs: Long,
    val dateModifiedMs: Long,
    val dateAddedSecs: Long,
    val orientation: Int,
    val isFavorite: Boolean,
    /** MediaStore 中是否仍存在；扫描时先全部置 false，再逐批置回 true */
    val isPresent: Boolean = true,
    /** 感知哈希：9x8 dHash，64 bit */
    val dhash: Long? = null,
    /** 感知哈希：32x32 DCT pHash，64 bit */
    val phash: Long? = null,
    /** 完整内容 SHA-256，仅在精确查重需要时计算 */
    val sha256: String? = null,
    /** 拉普拉斯方差，越小越模糊；null 表示未计算 */
    val blurScore: Double? = null,
    /** 0 = 未计算签名，1 = 当前算法版本 */
    val signatureVersion: Int = 0,
    val indexedAtMs: Long,
)
