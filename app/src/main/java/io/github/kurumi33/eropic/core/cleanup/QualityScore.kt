package io.github.kurumi33.eropic.core.cleanup

import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round

/**
 * 一个质量因子的得分。
 *
 * [score] 是该因子归一化后的 0~100 分，越高越好；[weight] 是本组内归一化后的权重，
 * 同一组内各因子权重之和为 1。用户要看的是「为什么选它」，所以每个因子都带可读的
 * [value]（原始值的中文描述）和可选的 [note]（解释性提示）。
 */
data class QualityFactor(
    val label: String,
    val value: String,
    val score: Double,
    val weight: Double,
    val note: String? = null,
)

/** 单张图片的综合质量评分，[total] 为 0~100。 */
data class QualityScore(
    val mediaId: Long,
    val total: Double,
    val factors: List<QualityFactor>,
)

/**
 * 一组重复/相似图片的综合评分结论。
 *
 * [scores] 按 [QualityScore.total] 降序（同分时按 id 升序，保证结果稳定）；
 * [keepMediaId] 是建议保留的那张，[trashIds] 是其余建议移入回收站的图片；
 * [rationale] 是 3~6 条中文依据，直接展示给用户；[confidence] 为 0~1，
 * 最高分与次高分的差距越大越接近 1（单张或资料不足时为 0）。
 */
data class GroupQualityAssessment(
    val scores: List<QualityScore>,
    val keepMediaId: Long,
    val trashIds: List<Long>,
    val rationale: List<String>,
    val confidence: Double,
)

/**
 * 多因子质量评分：为「一组重复/相似图片」挑出最值得保留的一张。
 *
 * 纯函数、无 Android 依赖，可直接 JVM 单测。评分维度与权重：
 * - 像素（45%，相对同组最高像素比例）：分辨率是最硬的画质指标，权重最高；
 * - 格式（10%）：PNG/BMP/TIFF/DNG/HEIC 无损优先于 JPG/WebP 有损；
 * - bpp（15%）：bits per pixel 低于 1.0 说明压缩过狠会被扣分，偏高只作解释不额外加分；
 * - 拍摄时间（8%）：越新越好，但明确告知「新≠一定更好」，权重低于像素；
 * - 文件名（5%）：IMG_0001.jpg / 微信图片_xxx 这类自动命名扣分，语义命名加分；
 * - 清晰度（17%，仅当组内全部已计算 blurScore 时参与）：Laplacian 方差越大越清晰，
 *   缺失时该维度整体去掉、其余权重按比例放大，避免把「没算过」当成「很模糊」。
 *
 * 注意：本对象只给建议，不会删除或移动任何文件。
 */
object QualityScorer {

    // ---- 各因子基础权重（清晰度缺失时按比例归一化）----
    private const val WEIGHT_PIXEL = 0.45
    private const val WEIGHT_FORMAT = 0.10
    private const val WEIGHT_BPP = 0.15
    private const val WEIGHT_DATE = 0.08
    private const val WEIGHT_NAME = 0.05
    private const val WEIGHT_SHARPNESS = 0.17

    // ---- 因子名（同时用于 UI 分组展示与排序，保持固定顺序）----
    private const val LABEL_PIXEL = "像素"
    private const val LABEL_FORMAT = "格式"
    private const val LABEL_BPP = "压缩率"
    private const val LABEL_DATE = "拍摄时间"
    private const val LABEL_NAME = "文件名"
    private const val LABEL_SHARPNESS = "清晰度"

    /** bpp 达到该值即视为「压缩不算狠」，给满分。 */
    private const val FULL_BPP = 1.0

    /** bpp 低于该值视为压缩过狠。 */
    private const val LOW_BPP = 0.5

    /** bpp 高于该值多半是无损格式或大图，只解释、不额外加分。 */
    private const val HIGH_BPP = 2.5

    // 数据缺失时的中性分：不奖励也不重罚，避免一条脏数据决定谁被删。
    private const val NEUTRAL_PIXEL = 60.0
    private const val NEUTRAL_BPP = 60.0
    private const val NEUTRAL_DATE = 50.0

    private val LOSSLESS_EXTS = setOf("png", "bmp", "tif", "tiff", "dng", "heic", "heif")
    private val LOSSLESS_MIMES = setOf(
        "image/png",
        "image/bmp",
        "image/x-ms-bmp",
        "image/tiff",
        "image/x-adobe-dng",
        "image/dng",
        "image/heic",
        "image/heif",
    )
    private val LOSSY_EXTS = setOf("jpg", "jpeg", "webp", "avif", "jfif")
    private val LOSSY_MIMES = setOf("image/jpeg", "image/webp", "image/avif")

    /** 常见的自动命名：IMG_0001、DSC_1234、微信图片_2023、Screenshot_xxx、哈希/时间戳名等。 */
    private val AUTO_NAME_PATTERNS = listOf(
        Regex("^img[-_]?\\d+.*$"),
        Regex("^dsc[-_]?\\d+.*$"),
        Regex("^dscn\\d+.*$"),
        Regex("^pxl[-_]?\\d+.*$"),
        Regex("^image[-_]?\\d+.*$"),
        Regex("^photo[-_]?\\d+.*$"),
        Regex("^pic[-_]?\\d+.*$"),
        Regex("^snap[-_]?\\d+.*$"),
        Regex("^screenshot.*$"),
        Regex("^screen[-_]?shot.*$"),
        Regex("^mmexport\\d+.*$"),
        Regex("^微信图片.*$"),
        Regex("^微信截图.*$"),
        Regex("^videocapture.*$"),
        Regex("^[0-9a-f]{16,}$"),
        Regex("^\\d{8,}$"),
        Regex("^\\d{8}[-_]\\d+$"),
    )
    private val CJK_REGEX = Regex("[\\u4e00-\\u9fff]")
    private val WORD_REGEX = Regex("[A-Za-z]{3,}")

    /**
     * 为一组图片打分并给出保留建议。
     *
     * 空列表返回空评分（keepMediaId = -1）；单张时没有可移入回收站的项，
     * confidence 为 0。结果对同样的输入永远稳定（平局按 id 升序保留）。
     */
    fun assess(members: List<MediaEntity>): GroupQualityAssessment {
        if (members.isEmpty()) {
            return GroupQualityAssessment(
                scores = emptyList(),
                keepMediaId = -1L,
                trashIds = emptyList(),
                rationale = listOf("组内没有图片，无法评分。"),
                confidence = 0.0,
            )
        }

        // 连分辨率和体积都没有（例如索引只写了路径），说明资料不足，别硬推荐。
        val hasUsableData = members.any { pixelCount(it) > 0L || it.sizeBytes > 0L }
        val ctx = ScoreContext(
            maxPixels = members.maxOf { pixelCount(it) },
            avgBpp = averageBpp(members),
            minDateMs = members.filter { it.dateTakenMs > 0L }.minOfOrNull { it.dateTakenMs } ?: 0L,
            maxDateMs = members.filter { it.dateTakenMs > 0L }.maxOfOrNull { it.dateTakenMs } ?: 0L,
            maxBlur = members.mapNotNull { it.blurScore }.maxOrNull() ?: 0.0,
            withSharpness = members.all { it.blurScore != null },
            dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA),
        )

        val scores = members
            .map { scoreOf(it, ctx) }
            .sortedWith(compareByDescending<QualityScore> { it.total }.thenBy { it.mediaId })

        val keep = scores.first()
        val trashIds = scores.drop(1).map { it.mediaId }
        val gap = if (scores.size >= 2) (scores[0].total - scores[1].total).coerceAtLeast(0.0) else 0.0
        val confidence = if (scores.size < 2 || !hasUsableData) 0.0 else (gap / 30.0).coerceIn(0.0, 1.0)

        val rationale = when {
            !hasUsableData ->
                listOf("资料不足：组内图片缺少分辨率与体积信息，无法可靠评分，请手动选择保留。")

            scores.size == 1 ->
                listOf("组内只有 1 张图片，没有需要移入回收站的重复项。")

            else -> buildRationale(members.size, scores)
        }

        return GroupQualityAssessment(
            scores = scores,
            keepMediaId = keep.mediaId,
            trashIds = trashIds,
            rationale = rationale,
            confidence = confidence,
        )
    }

    // ---- 单张评分 ----

    private class ScoreContext(
        val maxPixels: Long,
        val avgBpp: Double,
        val minDateMs: Long,
        val maxDateMs: Long,
        val maxBlur: Double,
        val withSharpness: Boolean,
        val dateFormatter: SimpleDateFormat,
    )

    private class FactorInput(
        val label: String,
        val value: String,
        val score: Double,
        val note: String? = null,
    )

    private fun scoreOf(member: MediaEntity, ctx: ScoreContext): QualityScore {
        val pixels = pixelCount(member)
        val bpp = bppOf(member)
        val format = formatInfo(member)
        val name = nameInfo(member)
        val inputs = mutableListOf<FactorInput>()

        // 像素：相对同组最高，避免绝对像素把「小图」一刀切；未知分辨率给中性分。
        val pixelScore = when {
            pixels <= 0L -> NEUTRAL_PIXEL
            ctx.maxPixels <= 0L -> NEUTRAL_PIXEL
            else -> (100.0 * pixels / ctx.maxPixels).coerceIn(0.0, 100.0)
        }
        val pixelValue = if (pixels <= 0L) {
            "分辨率未知"
        } else {
            String.format(Locale.CHINA, "%.0f 万像素（%d×%d）", pixels / 10_000.0, member.width, member.height)
        }
        inputs += FactorInput(LABEL_PIXEL, pixelValue, pixelScore)

        // 格式：无损优先。
        inputs += FactorInput(LABEL_FORMAT, format.value, format.score, format.note)

        // bpp：太低扣分，偏高只解释。
        val bppScore = when {
            pixels <= 0L || member.sizeBytes <= 0L -> NEUTRAL_BPP
            else -> (100.0 * bpp / FULL_BPP).coerceIn(0.0, 100.0)
        }
        val bppValue = if (bpp > 0.0) {
            String.format(Locale.CHINA, "%.2f bpp（同组均值 %.2f）", bpp, ctx.avgBpp)
        } else {
            "bpp 未知"
        }
        val bppNote = when {
            bpp <= 0.0 -> null
            bpp < LOW_BPP -> "bpp 偏低，可能压缩过狠"
            bpp > HIGH_BPP -> "bpp 偏高，多为无损格式或大图，不代表画质更好"
            else -> null
        }
        inputs += FactorInput(LABEL_BPP, bppValue, bppScore, bppNote)

        // 拍摄时间：越新越好，但只是弱因子。
        val dateScore = when {
            member.dateTakenMs <= 0L -> NEUTRAL_DATE
            ctx.maxDateMs <= ctx.minDateMs -> 100.0
            else -> 100.0 * (member.dateTakenMs - ctx.minDateMs) / (ctx.maxDateMs - ctx.minDateMs)
        }
        val dateValue = if (member.dateTakenMs > 0L) {
            ctx.dateFormatter.format(Date(member.dateTakenMs))
        } else {
            "未记录拍摄时间"
        }
        inputs += FactorInput(LABEL_DATE, dateValue, dateScore, "新≠一定更好，权重低于像素")

        // 文件名完整度。
        inputs += FactorInput(LABEL_NAME, name.value, name.score)

        // 清晰度：只有全组都算过指纹时才参与，否则缺失值会被误当成「很模糊」。
        if (ctx.withSharpness) {
            val blur = member.blurScore ?: 0.0
            val sharpnessScore = if (ctx.maxBlur <= 0.0) {
                NEUTRAL_PIXEL
            } else {
                (100.0 * blur / ctx.maxBlur).coerceIn(0.0, 100.0)
            }
            inputs += FactorInput(
                LABEL_SHARPNESS,
                String.format(Locale.CHINA, "Laplacian 方差 %.1f", blur),
                sharpnessScore,
                "Laplacian 方差越大越清晰",
            )
        }

        val rawWeights = buildList {
            add(WEIGHT_PIXEL)
            add(WEIGHT_FORMAT)
            add(WEIGHT_BPP)
            add(WEIGHT_DATE)
            add(WEIGHT_NAME)
            if (ctx.withSharpness) add(WEIGHT_SHARPNESS)
        }
        val weightSum = rawWeights.sum().takeIf { it > 0.0 } ?: 1.0
        val factors = inputs.mapIndexed { index, input ->
            QualityFactor(
                label = input.label,
                value = input.value,
                score = round1(input.score.coerceIn(0.0, 100.0)),
                weight = rawWeights[index] / weightSum,
                note = input.note,
            )
        }
        val total = round1(factors.sumOf { it.score * it.weight }.coerceIn(0.0, 100.0))
        return QualityScore(mediaId = member.id, total = total, factors = factors)
    }

    // ---- 依据文案 ----

    /**
     * 生成 3~6 条中文依据：优先讲清「保留 A、淘汰 B」的主要原因，
     * 最后一定给出综合分与移入回收站的范围。
     */
    private fun buildRationale(memberCount: Int, scores: List<QualityScore>): List<String> {
        val keep = scores[0]
        val next = scores[1]
        val gap = (keep.total - next.total).coerceAtLeast(0.0)

        fun factor(score: QualityScore, label: String): QualityFactor? =
            score.factors.firstOrNull { it.label == label }

        fun better(a: QualityFactor, b: QualityFactor): String = if (a.score >= b.score) "A" else "B"

        val lines = mutableListOf<String>()

        // 1) 像素
        factor(keep, LABEL_PIXEL)?.let { a ->
            factor(next, LABEL_PIXEL)?.let { b ->
                if (abs(a.score - b.score) >= 0.5) {
                    lines += "像素：A ${a.value}，B ${b.value}，${better(a, b)} 更优。"
                }
            }
        }

        // 2) 格式（有损 / 无损）
        factor(keep, LABEL_FORMAT)?.let { a ->
            factor(next, LABEL_FORMAT)?.let { b ->
                if (abs(a.score - b.score) >= 5.0) {
                    lines += "格式：A ${a.value}，B ${b.value}，${better(a, b)} 更优。"
                }
            }
        }

        // 3) bpp（压缩过狠）
        factor(keep, LABEL_BPP)?.let { a ->
            factor(next, LABEL_BPP)?.let { b ->
                val worst = if (a.score <= b.score) "A" to a else "B" to b
                if (min(a.score, b.score) < 70.0) {
                    lines += "压缩率：${worst.first} ${worst.second.value}，压缩过狠，画质会受影响。"
                }
            }
        }

        // 4) 清晰度（全组都有指纹时才存在该因子）
        factor(keep, LABEL_SHARPNESS)?.let { a ->
            factor(next, LABEL_SHARPNESS)?.let { b ->
                if (abs(a.score - b.score) >= 2.0) {
                    lines += "清晰度：A ${a.value}，B ${b.value}，${better(a, b)} 更清晰。"
                }
            }
        }

        // 5) 拍摄时间：明确告诉用户「新≠一定更好」。
        factor(keep, LABEL_DATE)?.let { a ->
            factor(next, LABEL_DATE)?.let { b ->
                if (abs(a.score - b.score) >= 5.0) {
                    lines += if (a.score >= b.score) {
                        "拍摄时间：A 更新（${a.value}），但新≠一定更好，时间因子权重较低，只用来打破平局。"
                    } else {
                        "拍摄时间：B 更新（${b.value}），但新≠一定更好——A 的像素/格式/清晰度优势更大。"
                    }
                }
            }
        }

        // 6) 文件名完整度
        factor(keep, LABEL_NAME)?.let { a ->
            factor(next, LABEL_NAME)?.let { b ->
                if (abs(a.score - b.score) >= 5.0) {
                    lines += "文件名：A ${a.value}，B ${b.value}，${better(a, b)} 更完整。"
                }
            }
        }

        if (lines.isEmpty()) {
            lines += "组内各张在像素、格式、压缩率、拍摄时间与文件名上非常接近，属于平局，按 id 从小到大稳定保留第一张。"
        }

        val summary = "组内共 $memberCount 张，建议保留 A，其余 ${memberCount - 1} 张移入回收站（30 天内可恢复）。"
        val finalLine = if (gap < 0.5) {
            "综合评分：A ${format1(keep.total)} 分 vs B ${format1(next.total)} 分，几乎平局，建议点「对比预览」人工确认。"
        } else {
            "综合评分：A ${format1(keep.total)} 分 vs B ${format1(next.total)} 分，建议保留 A（领先 ${format1(gap)} 分）。"
        }

        // 4 条因子依据 + 范围说明 + 综合分 = 6 条封顶；不足 3 条时由平局兜底行补齐。
        return (lines.take(4) + summary + finalLine)
    }

    // ---- 小工具 ----

    private fun pixelCount(member: MediaEntity): Long =
        member.width.toLong().coerceAtLeast(0L) * member.height.toLong().coerceAtLeast(0L)

    private fun bppOf(member: MediaEntity): Double {
        val pixels = pixelCount(member)
        return if (pixels > 0L && member.sizeBytes > 0L) {
            member.sizeBytes.toDouble() * 8.0 / pixels
        } else {
            0.0
        }
    }

    private fun averageBpp(members: List<MediaEntity>): Double {
        val values = members.map { bppOf(it) }.filter { it > 0.0 }
        return if (values.isEmpty()) 0.0 else values.average()
    }

    private class FormatInfo(val score: Double, val value: String, val note: String?)

    private fun formatInfo(member: MediaEntity): FormatInfo {
        val ext = member.displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = member.mimeType.lowercase(Locale.ROOT)
        val label = when {
            ext.isNotEmpty() -> ext.uppercase(Locale.ROOT)
            mime.startsWith("image/") -> mime.removePrefix("image/").uppercase(Locale.ROOT)
            else -> "未知格式"
        }
        return when {
            ext in LOSSLESS_EXTS || mime in LOSSLESS_MIMES ->
                FormatInfo(100.0, "$label（无损）", null)

            ext in LOSSY_EXTS || mime in LOSSY_MIMES ->
                FormatInfo(55.0, "$label（有损压缩）", "有损格式，可能已有压缩痕迹")

            else -> FormatInfo(70.0, "$label（未识别）", "无法判断是否有损")
        }
    }

    private class NameInfo(val score: Double, val value: String)

    private fun nameInfo(member: MediaEntity): NameInfo {
        val display = member.displayName.ifBlank { "未命名" }
        val stem = display.substringBeforeLast('.', display).lowercase(Locale.ROOT)
        val auto = stem.length <= 1 || AUTO_NAME_PATTERNS.any { it.matches(stem) }
        return when {
            auto -> NameInfo(25.0, "自动命名（$display）")
            CJK_REGEX.containsMatchIn(stem) || WORD_REGEX.containsMatchIn(stem) ->
                NameInfo(100.0, "语义命名（$display）")

            else -> NameInfo(60.0, "普通命名（$display）")
        }
    }

    private fun round1(value: Double): Double = round(value * 10.0) / 10.0

    private fun format1(value: Double): String = String.format(Locale.CHINA, "%.1f", value)
}
