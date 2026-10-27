package io.github.kurumi33.eropic.core.hash

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import java.security.MessageDigest

/**
 * 精确查重用的内容哈希。
 *
 * 先用 [quick]（大小 + 头 64KB + 尾 64KB）分桶，只有快速哈希相同的候选才需要
 * [full] 全文件 SHA-256，避免对 100GB 图库做无谓的全量读取。
 */
object Sha256Util {

    private const val CHUNK = 64 * 1024

    fun full(contentResolver: ContentResolver, uri: Uri): String? =
        digestStream(contentResolver, uri, tail = false)

    fun quick(contentResolver: ContentResolver, uri: Uri, sizeBytes: Long): String? =
        digestStream(contentResolver, uri, tail = true, knownSize = sizeBytes)

    private fun digestStream(
        contentResolver: ContentResolver,
        uri: Uri,
        tail: Boolean,
        knownSize: Long = -1,
    ): String? {
        var input: InputStream? = null
        return try {
            input = contentResolver.openInputStream(uri) ?: return null
            val digest = MessageDigest.getInstance("SHA-256")
            if (!tail) {
                val buffer = ByteArray(CHUNK)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            } else {
                digest.update(longToBytes(knownSize))
                val head = ByteArray(CHUNK)
                var headRead = 0
                while (headRead < CHUNK) {
                    val read = input.read(head, headRead, CHUNK - headRead)
                    if (read < 0) break
                    headRead += read
                }
                digest.update(head, 0, headRead)
                if (knownSize > CHUNK) {
                    skipFully(input, knownSize - CHUNK - headRead)
                    val tailBytes = ByteArray(CHUNK)
                    var tailRead = 0
                    while (tailRead < CHUNK) {
                        val read = input.read(tailBytes, tailRead, CHUNK - tailRead)
                        if (read < 0) break
                        tailRead += read
                    }
                    digest.update(tailBytes, 0, tailRead)
                }
            }
            digest.digest().toHex()
        } catch (e: Exception) {
            null
        } finally {
            try {
                input?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun longToBytes(value: Long): ByteArray {
        val size = if (value < 0) 0L else value
        return ByteArray(8) { i -> ((size ushr (8 * (7 - i))) and 0xFF).toByte() }
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            sb.append(HEX[(b.toInt() ushr 4) and 0x0F])
            sb.append(HEX[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
