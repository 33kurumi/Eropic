package io.github.kurumi33.eropic.core.analysis.detectors

import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 清理检测器的阈值判定。
 *
 * 这些数值直接决定"建议用户删什么"，散落在实现里最容易悄悄漂移，
 * 所以全部抽成 companion 里的纯函数，用测试锁住。
 */
class DetectorThresholdsTest {

    private val now = 1_700_000_000_000L
    private val dayMs = 24L * 60 * 60 * 1000

    @Test
    fun oldPhotoDefaultIsThreeYears() {
        assertEquals(3 * 365, OldPhotoDetector.DEFAULT_THRESHOLD_DAYS)

        val cutoff = OldPhotoDetector.cutoffMs(now, OldPhotoDetector.DEFAULT_THRESHOLD_DAYS)

        assertEquals(now - 3 * 365 * dayMs, cutoff)
    }

    @Test
    fun oldPhotoThresholdIsParameterizable() {
        assertEquals(now - 30 * dayMs, OldPhotoDetector.cutoffMs(now, 30))
    }

    @Test
    fun oldPhotoNegativeDaysDoesNotMoveCutoffIntoTheFuture() {
        // 传负数会让截止时间跑到未来，等于把所有照片都判成"久远"，必须钳到 0。
        assertEquals(now, OldPhotoDetector.cutoffMs(now, -5))
    }

    @Test
    fun oldPhotoHugeDaysDoesNotOverflow() {
        val cutoff = OldPhotoDetector.cutoffMs(now, Int.MAX_VALUE)

        assertEquals(now - OldPhotoDetector.MAX_THRESHOLD_DAYS.toLong() * dayMs, cutoff)
    }

    @Test
    fun largeFileDefaultsMatchPlan() {
        assertEquals(200, LargeFileDetector.DEFAULT_TOP_N)
        assertEquals(5L * 1024 * 1024, LargeFileDetector.DEFAULT_MIN_SIZE_BYTES)
    }

    @Test
    fun largeFileNonPositiveTopNFallsBackToDefault() {
        assertEquals(LargeFileDetector.DEFAULT_TOP_N, LargeFileDetector.effectiveTopN(0))
        assertEquals(LargeFileDetector.DEFAULT_TOP_N, LargeFileDetector.effectiveTopN(-3))
    }

    @Test
    fun largeFileTopNIsCapped() {
        assertEquals(LargeFileDetector.MAX_TOP_N, LargeFileDetector.effectiveTopN(100_000))
        assertEquals(50, LargeFileDetector.effectiveTopN(50))
    }

    @Test
    fun largeFileNegativeMinSizeBecomesZero() {
        assertEquals(0L, LargeFileDetector.effectiveMinSizeBytes(-1L))
        assertEquals(1024L, LargeFileDetector.effectiveMinSizeBytes(1024L))
    }

    @Test
    fun largeFileResultSumsOnlyReturnedItems() {
        val result = LargeFileResult(
            items = listOf(media(id = 1, sizeBytes = 10L), media(id = 2, sizeBytes = 32L)),
            hitCount = 7,
            reclaimableBytes = 999L,
        )

        // Top N 列表可能被 LIMIT 截断，所以 topBytes 和 reclaimableBytes 是两个不同的数。
        assertEquals(42L, result.topBytes)
        assertEquals(999L, result.reclaimableBytes)
    }

    @Test
    fun blurThresholdMatchesPlan() {
        assertEquals(100.0, BlurDetector.DEFAULT_BLUR_THRESHOLD, 0.0)
    }

    private fun media(id: Long, sizeBytes: Long): MediaEntity = MediaEntity(
        id = id,
        volumeName = "external_primary",
        path = "/storage/emulated/0/DCIM/Camera/IMG_$id.jpg",
        displayName = "IMG_$id.jpg",
        relativePath = "DCIM/Camera/",
        bucketId = 1L,
        bucketName = "Camera",
        mimeType = "image/jpeg",
        sizeBytes = sizeBytes,
        width = 4000,
        height = 3000,
        dateTakenMs = now,
        dateModifiedMs = now,
        dateAddedSecs = now / 1000,
        orientation = 0,
        isFavorite = false,
        indexedAtMs = now,
    )
}
