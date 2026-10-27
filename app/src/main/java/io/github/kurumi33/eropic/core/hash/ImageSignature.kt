package io.github.kurumi33.eropic.core.hash

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Signature(
    val dhash: Long,
    val phash: Long,
    val blurScore: Double,
)

/** 一次解码，产出 dHash + pHash + 模糊分。 */
object ImageSignature {

    private const val DECODE_LONG_EDGE = 64

    suspend fun compute(contentResolver: ContentResolver, uri: Uri): Signature? = withContext(Dispatchers.IO) {
        val gray = BitmapGray.decode(contentResolver, uri, DECODE_LONG_EDGE) ?: return@withContext null
        val dhash = DHash.compute(gray.resize(9, 8))
        val phash = PHash.compute(gray.resize(32, 32))
        val blur = Laplacian.variance(gray)
        Signature(dhash = dhash, phash = phash, blurScore = blur)
    }
}
