package io.github.kurumi33.eropic.core.index

import android.content.ContentResolver
import io.github.kurumi33.eropic.core.database.entity.HashBandEntity
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.hash.Hashing
import io.github.kurumi33.eropic.core.hash.ImageSignature
import io.github.kurumi33.eropic.core.media.MediaUris

class SignatureGenerator(private val contentResolver: ContentResolver) {

    suspend fun generate(item: MediaEntity): GeneratedSignature? {
        val signature = ImageSignature.compute(contentResolver, MediaUris.of(item.id)) ?: return null
        val bands = Hashing.bands(signature.dhash).mapIndexed { bandNo, value ->
            HashBandEntity(mediaId = item.id, bandNo = bandNo, bandVal = value)
        }
        return GeneratedSignature(
            dhash = signature.dhash,
            phash = signature.phash,
            blurScore = signature.blurScore,
            bands = bands,
        )
    }
}

data class GeneratedSignature(
    val dhash: Long,
    val phash: Long,
    val blurScore: Double,
    val bands: List<HashBandEntity>,
)
