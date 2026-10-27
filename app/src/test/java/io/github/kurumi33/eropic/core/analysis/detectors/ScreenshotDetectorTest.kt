package io.github.kurumi33.eropic.core.analysis.detectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截图启发式判定的正反例。
 *
 * 判定是纯函数，不碰数据库也不碰 Android 类，所以这里能用普通 JVM 测试直接跑。
 */
class ScreenshotDetectorTest {

    private fun looks(
        path: String,
        displayName: String = path.substringAfterLast('/'),
        mimeType: String = "image/png",
        width: Int = 1080,
        height: Int = 2400,
    ): Boolean = ScreenshotDetector.looksLikeScreenshot(path, displayName, mimeType, width, height)

    // --- 正例：文件名信号 ---

    @Test
    fun screenshotFileNameIsDetected() {
        assertTrue(looks("/storage/emulated/0/DCIM/Screenshots/Screenshot_20240101-120000.png"))
    }

    @Test
    fun uppercaseFileNameIsDetected() {
        assertTrue(looks("/storage/emulated/0/Pictures/SCREENSHOT_2024.PNG"))
    }

    @Test
    fun chineseScreenshotNameIsDetected() {
        assertTrue(looks("/storage/emulated/0/Pictures/截屏_2024-01-01.png"))
    }

    @Test
    fun screenshotFolderAloneCountsBecausePathIsSearched() {
        // 目录名里带 Screenshots 但文件名是普通照片：path 包含关键词，判定为真。
        // 这是有意的偏保守选择：混在截图目录里的普通照片面积很小，漏判的代价更高。
        assertTrue(looks("/storage/emulated/0/DCIM/Screenshots/IMG_0001.jpg", mimeType = "image/jpeg"))
    }

    @Test
    fun screenrecordAndScreenCaptureKeywordsAreDetected() {
        assertTrue(looks("/storage/emulated/0/Movies/screenrecord_001.mp4", mimeType = "video/mp4"))
        assertTrue(looks("/storage/emulated/0/Pictures/screen capture.png", mimeType = "image/jpeg"))
        assertTrue(looks("/storage/emulated/0/Pictures/SCR_0001.jpg", mimeType = "image/jpeg"))
    }

    // --- 正例：分辨率信号 ---

    @Test
    fun commonScreenResolutionsAreDetected() {
        val resolutions = listOf(
            1080 to 2400,
            1260 to 2800,
            1440 to 3200,
            1170 to 2532,
        )
        for ((width, height) in resolutions) {
            assertTrue(
                "应为截图：" + width + "x" + height,
                looks("/storage/emulated/0/DCIM/Camera/IMG_0001.png", width = width, height = height),
            )
        }
    }

    @Test
    fun landscapeOrientationIsDetected() {
        assertTrue(looks("/storage/emulated/0/DCIM/Camera/IMG_0001.png", width = 2400, height = 1080))
    }

    // --- 反例 ---

    @Test
    fun cameraJpegIsNotScreenshot() {
        assertFalse(
            looks(
                "/storage/emulated/0/DCIM/Camera/IMG_20240101_120000.jpg",
                mimeType = "image/jpeg",
                width = 4000,
                height = 3000,
            ),
        )
    }

    @Test
    fun pngWithNonScreenResolutionIsNotScreenshot() {
        assertFalse(looks("/storage/emulated/0/Pictures/artwork.png", width = 800, height = 600))
    }

    @Test
    fun screenResolutionWithoutPngIsNotScreenshot() {
        // 分辨率只是辅助信号：JPEG 用屏幕尺寸多半是从截图存下来的原图，但不能据此断定是截图。
        assertFalse(
            looks(
                "/storage/emulated/0/DCIM/Camera/IMG_0001.jpg",
                mimeType = "image/jpeg",
                width = 1080,
                height = 2400,
            ),
        )
    }

    @Test
    fun similarButNotMatchingKeywordDoesNotHit() {
        // scr_ 必须带下划线，不能退化成前缀 scr；script_notes 不是截图。
        assertFalse(looks("/storage/emulated/0/Pictures/script_notes.png", width = 800, height = 600))
        assertFalse(looks("/storage/emulated/0/Pictures/screen_play.png", width = 800, height = 600))
    }

    @Test
    fun zeroSizeIsNotScreenshot() {
        assertFalse(looks("/storage/emulated/0/Pictures/broken.png", width = 0, height = 0))
    }

    // --- 粗筛 SQL ---

    @Test
    fun candidateQueryCoversPngAndKeywords() {
        val (sql, args) = ScreenshotDetector.candidateQuery(50)

        assertTrue(sql.startsWith("SELECT * FROM media_item WHERE isPresent = 1"))
        assertTrue(sql.contains("lower(mimeType) = 'image/png'"))
        assertTrue(sql.contains("lower(path) LIKE ?"))
        assertTrue(sql.contains("ESCAPE '\\'"))
        assertTrue(sql.endsWith("ORDER BY dateTakenMs DESC, id DESC LIMIT ?"))

        // 最后一个参数是 limit，其余全是 LIKE 模式。
        assertEquals(50, args.last())
        assertEquals(args.size - 1, args.count { it is String })
        assertTrue(args.contains("%screenshot%"))
        // 下划线必须转义，否则 scr_ 会匹配到 screen_、script_ 等名字（粗筛面过大）。
        assertTrue(args.contains("%scr\\_%"))
    }
}
