package io.github.kurumi33.eropic.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只覆盖纯文本拼装（[LogFormatter]）。取数/IO（Context、MediaStore、logcat、FileProvider）
 * 都要真机环境，junit 里不模拟——那部分留给发版前的真机冒烟。
 */
class LogFormatterTest {

    @Test
    fun headerContainsPurposeAndPrivacyNote() {
        val text = LogFormatter.header("2026-10-31 15:00:00")
        assertTrue(text.contains("=== Eropic 诊断日志 ==="))
        assertTrue(text.contains("2026-10-31 15:00:00"))
        assertTrue(text.contains("不会上传"))
    }

    @Test
    fun appSectionContainsVersionBuildAndDebugFlag() {
        val text = LogFormatter.appSection(
            versionName = "1.0.0",
            versionCode = 1790000000L,
            buildStamp = "20261031-1530",
            buildTimeText = "2026-10-31 15:30",
            packageName = "io.github.kurumi33.eropic",
            debuggable = false,
        )
        assertTrue(text.contains("版本: 1.0.0 (1790000000)"))
        assertTrue(text.contains("构建: 20261031-1530（2026-10-31 15:30）"))
        assertTrue(text.contains("包名: io.github.kurumi33.eropic"))
        assertTrue(text.contains("Debug: 否"))
    }

    @Test
    fun deviceSectionContainsAbiAndScreen() {
        val text = LogFormatter.deviceSection(
            manufacturer = "Xiaomi",
            brand = "Redmi",
            model = "2312DRA50C",
            device = "marble",
            release = "14",
            sdkInt = 34,
            abis = listOf("arm64-v8a", "armeabi-v7a"),
            widthPx = 1080,
            heightPx = 2400,
            densityDpi = 420,
            density = 2.625f,
        )
        assertTrue(text.contains("ABI: arm64-v8a, armeabi-v7a"))
        assertTrue(text.contains("1080×2400"))
        assertTrue(text.contains("420 dpi"))
        assertTrue(text.contains("2.63x"))
    }

    @Test
    fun permissionSectionListsEachRow() {
        val text = LogFormatter.permissionSection(
            listOf(
                "READ_MEDIA_IMAGES" to "已授予",
                "POST_NOTIFICATIONS" to "未授予",
            ),
        )
        assertTrue(text.contains("READ_MEDIA_IMAGES: 已授予"))
        assertTrue(text.contains("POST_NOTIFICATIONS: 未授予"))
    }

    @Test
    fun statsSectionFallsBackWhenMissing() {
        assertTrue(LogFormatter.statsSection(null).contains("（本次未提供统计）"))
        assertTrue(LogFormatter.statsSection("   ").contains("（本次未提供统计）"))
        assertTrue(LogFormatter.statsSection("图片总数: 1200\n视频总数: 8").contains("图片总数: 1200"))
    }

    @Test
    fun crashSectionHandlesEmptyAndContent() {
        assertTrue(LogFormatter.crashSection(emptyList()).contains("（没有崩溃记录）"))
        val text = LogFormatter.crashSection(listOf("crash-20261031-150000.txt" to "异常: NPE"))
        assertTrue(text.contains("--- crash-20261031-150000.txt ---"))
        assertTrue(text.contains("异常: NPE"))
    }

    @Test
    fun logcatFilterKeepsAppAndErrorLinesOnly() {
        val raw = listOf(
            "10-31 15:00:00.000 I/EropicSync: done",
            "10-31 15:00:01.000 E/AndroidRuntime: FATAL EXCEPTION",
            "10-31 15:00:02.000 I/other: 系统噪音",
            "10-31 15:00:03.000 D/eropic: lower case allowed",
        )
        val filtered = LogFormatter.filterLogcatLines(raw, 500)
        assertEquals(3, filtered.size)
        assertTrue(filtered.none { it.contains("系统噪音") })
    }

    @Test
    fun logcatFilterRespectsMaxLines() {
        val raw = (1..10).map { "line $it eropic" }
        assertEquals(3, LogFormatter.filterLogcatLines(raw, 3).size)
    }

    @Test
    fun logcatSectionWritesFailureOrEmptyHint() {
        assertTrue(LogFormatter.logcatSection(emptyList(), "读取 logcat 失败（IO）").contains("读取 logcat 失败（IO）"))
        assertTrue(LogFormatter.logcatSection(emptyList(), null).contains("（没有匹配到本应用相关的行）"))
        assertTrue(LogFormatter.logcatSection(listOf("I/Eropic: hi"), null).contains("I/Eropic: hi"))
    }

    @Test
    fun truncateKeepsShortTextAndMarksLongText() {
        assertEquals("abc", LogFormatter.truncate("abc", 10))
        val truncated = LogFormatter.truncate("x".repeat(100), 10)
        assertTrue(truncated.startsWith("x".repeat(10)))
        assertTrue(truncated.contains("已截断"))
    }
}
