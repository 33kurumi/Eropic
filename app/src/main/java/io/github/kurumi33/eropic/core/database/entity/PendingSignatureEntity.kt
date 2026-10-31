package io.github.kurumi33.eropic.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 待落盘的图片指纹（v0.7）。
 *
 * 存在的理由：指纹计算是"逐张写入"的高频操作，如果直接写 media_item，
 * Room 的 InvalidationTracker 会把 media_item 当成脏表，图库的分页查询（Room PagingSource）
 * 每一张图都被判定失效并整体刷新 —— 用户看到的症状就是"算指纹时图库一直闪、只剩两个月"。
 *
 * 所以计算过程中先把结果写进这张独立的小表（不会触发图库刷新），
 * 在 暂停 / 取消 / 一轮算完 / 下次启动 时用一条 UPDATE ... 一次性合并回 media_item，
 * 把 10 万次失效压缩成 1 次。读路径（查重、识图、检测器）仍然读 media_item，无需改动。
 */
@Entity(tableName = "pending_signature")
data class PendingSignatureEntity(
    @PrimaryKey val mediaId: Long,
    val dhash: Long? = null,
    val phash: Long? = null,
    val blurScore: Double? = null,
    val sha256: String? = null,
    /** 1 = 已算好；-1 = 解码失败、不再重试 */
    val signatureVersion: Int = 1,
    val updatedAtMs: Long = System.currentTimeMillis(),
)
