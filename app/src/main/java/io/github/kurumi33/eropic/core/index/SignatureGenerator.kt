package io.github.kurumi33.eropic.core.index

import android.content.ContentResolver
import io.github.kurumi33.eropic.core.database.entity.HashBandEntity
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.hash.Hashing
import io.github.kurumi33.eropic.core.hash.ImageSignature
import io.github.kurumi33.eropic.core.media.MediaUris
import io.github.kurumi33.eropic.core.thumb.BitmapDecoder
import io.github.kurumi33.eropic.core.thumb.ThumbnailCache

/**
 * 索引阶段的指纹生成。
 *
 * M1.5 起同一次解码（长边 320）既算指纹、又写缩略图，避免为了缩略图再解一遍原图；
 * 索引慢 10~20%，换来的是一进图库就是小图，不会一格格去解原图。
 */
class SignatureGenerator(
    private val contentResolver: ContentResolver,
    private val thumbnailCache: ThumbnailCache,
) {

    suspend fun generate(item: MediaEntity): GeneratedSignature? {
        val bitmap = BitmapDecoder.decode(
            contentResolver = contentResolver,
            uri = MediaUris.of(item.id),
            longEdge = ImageSignature.DECODE_LONG_EDGE,
        ) ?: return null
        try {
            val signature = ImageSignature.compute(bitmap) ?: return null
            // 顺手落缩略图；写失败不影响指纹结果（网格会退回按需生成或原图兜底）。
            thumbnailCache.write(item, bitmap)
            val bands = Hashing.bands(signature.dhash).mapIndexed { bandNo, value ->
                HashBandEntity(mediaId = item.id, bandNo = bandNo, bandVal = value)
            }
            return GeneratedSignature(
                dhash = signature.dhash,
                phash = signature.phash,
                blurScore = signature.blurScore,
                bands = bands,
            )
        } finally {
            bitmap.recycle()
        }
    }
}

data class GeneratedSignature(
    val dhash: Long,
    val phash: Long,
    val blurScore: Double,
    val bands: List<HashBandEntity>,
)
