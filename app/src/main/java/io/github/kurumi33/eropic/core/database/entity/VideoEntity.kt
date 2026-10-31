package io.github.kurumi33.eropic.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 视频索引行（v0.8）。
 *
 * 只做「识别 + 统计 + 浏览」，**不改动文件位置**：视频仍然躺在原来的目录里，
 * 文件夹页最下面的「视频」只是把这些行聚起来展示。
 * 与 [MediaEntity] 分开存表，是因为视频的字段（时长、宽高、无指纹/收藏语义）和图片差异大，
 * 混在一张表里会让图片侧的所有查询多带一个 mimeType 过滤条件。
 */
@Entity(
    tableName = "video_item",
    indices = [
        Index(value = ["relativePath"]),
        Index(value = ["dateTakenMs"]),
        Index(value = ["sizeBytes"]),
        Index(value = ["isPresent", "dateTakenMs"]),
    ],
)
data class VideoEntity(
    @PrimaryKey val id: Long,
    val displayName: String,
    val path: String,
    val relativePath: String,
    val bucketId: Long,
    val bucketName: String?,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val dateTakenMs: Long,
    val dateModifiedMs: Long,
    val isPresent: Boolean = true,
)
