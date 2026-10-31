package io.github.kurumi33.eropic.core.diagnostics

import java.util.Locale

/**
 * 诊断日志的纯文本拼装。
 *
 * 为什么单独抽一个 object：真正的 [LogCollector.collect] 要碰 Context / MediaStore / logcat，
 * 在 JVM 单测里跑不动（要么上 Robolectric）；把"怎么排版"和"怎么取数据"分开，
 * 排版部分就能用普通 junit 断言，出问题时也更容易定位是取数错了还是格式错了。
 */
internal object LogFormatter {

    /** 文件头。用户把日志发给开发者时，第一眼要能确认"这份文件是什么、有没有隐私内容"。 */
    fun header(generatedAt: String): String = buildString {
        appendLine("=== Eropic 诊断日志 ===")
        appendLine("生成时间: $generatedAt")
        appendLine("说明: 本文件只在本机生成，点「分享」才会交给系统分享面板；Eropic 不会上传到任何服务器。")
        appendLine("隐私: 只含数量统计，不含图片/视频内容，也不含媒体文件路径。")
        appendLine()
    }

    fun appSection(
        versionName: String,
        versionCode: Long,
        buildStamp: String,
        buildTimeText: String,
        packageName: String,
        debuggable: Boolean,
    ): String = buildString {
        appendLine("【应用】")
        appendLine("版本: $versionName ($versionCode)")
        // BUILD_STAMP 是 yyyyMMdd-HHmm，和导出的 APK 文件名一致，能对得上"用户装的是哪次构建"。
        appendLine("构建: $buildStamp（$buildTimeText）")
        appendLine("包名: $packageName")
        appendLine("Debug: ${if (debuggable) "是" else "否"}")
        appendLine()
    }

    fun deviceSection(
        manufacturer: String,
        brand: String,
        model: String,
        device: String,
        release: String,
        sdkInt: Int,
        abis: List<String>,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        density: Float,
    ): String = buildString {
        appendLine("【设备】")
        appendLine("厂商/品牌: $manufacturer / $brand")
        appendLine("型号/设备: $model / $device")
        appendLine("系统: Android $release（SDK $sdkInt）")
        // ABI 能直接看出用户装的是不是 64 位包、有没有 32 位兼容层。
        appendLine("ABI: ${abis.joinToString()}")
        appendLine(
            "屏幕: ${widthPx}×${heightPx} px, ${densityDpi} dpi, " +
                String.format(Locale.US, "%.2f", density) + "x",
        )
        appendLine()
    }

    fun permissionSection(rows: List<Pair<String, String>>): String = buildString {
        appendLine("【权限】")
        if (rows.isEmpty()) {
            appendLine("（没有可报告的权限）")
        } else {
            rows.forEach { (name, state) -> appendLine("$name: $state") }
        }
        appendLine()
    }

    /**
     * 索引统计由 UI 侧算好再传进来（[stats] 为 null 表示这次没传）。
     * 本模块不直接读 Room：统计口径（在场合计/含缺席）属于 UI/仓库层，
     * 诊断模块只负责原样收下并排版，避免两边口径不一致。
     */
    fun statsSection(stats: String?): String = buildString {
        appendLine("【索引与数据统计】")
        val text = stats?.trim().orEmpty()
        appendLine(text.ifEmpty { "（本次未提供统计）" })
        appendLine()
    }

    fun crashSection(crashes: List<Pair<String, String>>): String = buildString {
        appendLine("【最近崩溃】")
        if (crashes.isEmpty()) {
            appendLine("（没有崩溃记录）")
        } else {
            crashes.forEach { (name, content) ->
                appendLine("--- $name ---")
                appendLine(content.trimEnd())
            }
        }
        appendLine()
    }

    fun logcatSection(lines: List<String>, failure: String?): String = buildString {
        appendLine("【logcat】")
        when {
            failure != null -> appendLine(failure)
            lines.isEmpty() -> appendLine("（没有匹配到本应用相关的行）")
            else -> lines.forEach { appendLine(it) }
        }
        appendLine()
    }

    /** 只留和 Eropic 有关的行；`E/` 是 logcat 里 error 级别的标签前缀（如 E/AndroidRuntime）。 */
    fun filterLogcatLines(raw: List<String>, maxLines: Int): List<String> =
        raw.asSequence()
            .filter { it.contains("eropic", ignoreCase = true) || it.contains("E/") }
            .take(maxLines.coerceAtLeast(0))
            .toList()

    /** 崩溃文件可能很长，原样塞进诊断日志会让整份文件不好发给别人，按字符数截断即可。 */
    fun truncate(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "\n…（已截断，原始 ${text.length} 字符）"
    }
}
