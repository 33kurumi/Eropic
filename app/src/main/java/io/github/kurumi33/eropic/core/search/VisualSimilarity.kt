package io.github.kurumi33.eropic.core.search

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 一张图的「颜色画像」：本地以图搜图的补充特征。
 *
 * 为什么还需要它：dHash/pHash 只描述「结构」，对二次元图片很不友好——
 * 同一张线稿换一套配色、或者同一角色不同姿势，哈希距离可能很大，但人眼一看就是「同一类图」。
 * 颜色画像补的是另一半信息：主色调、饱和度、明度、线条密度、长宽比，以及一个
 * 「像不像二次元插画」的启发式判定（见 [VisualSimilarity.combinedScore]）。
 *
 * 全部字段都是可比较的纯数值，[VisualSimilarity.profileFromPixels] 不依赖 Android，
 * 方便在纯 JVM 单测里构造像素数组做断言。
 *
 * @property hueHistogram 12 个 30 度色相桶的像素计数，总和等于参与统计的采样点数
 *   （几乎全透明的像素会被跳过，见 [VisualSimilarity.profileFromPixels]）。
 * @property saturationMean HSV 饱和度均值，0.0..1.0。
 * @property valueMean HSV 明度均值，0.0..1.0。
 * @property edgeDensity 相邻像素亮度差超过阈值（约 0.12）的比例，0.0..1.0。
 * @property aspectRatio 宽 / 高（用原图像素尺寸，缩放前后一致），无效尺寸时为 0.0。
 * @property isLikelyAnime 是否「像二次元插画」的启发式判定，不是分类器。
 */
data class ColorProfile(
    val hueHistogram: IntArray,
    val saturationMean: Double,
    val valueMean: Double,
    val edgeDensity: Double,
    val aspectRatio: Double,
    val isLikelyAnime: Boolean,
) {
    // data class 默认用 IntArray 的引用相等，两个像素完全相同的画像会被判为「不等」；
    // 这里手动改成内容相等，避免调用方（包括单测）踩这个坑。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ColorProfile) return false
        return saturationMean == other.saturationMean &&
            valueMean == other.valueMean &&
            edgeDensity == other.edgeDensity &&
            aspectRatio == other.aspectRatio &&
            isLikelyAnime == other.isLikelyAnime &&
            hueHistogram.contentEquals(other.hueHistogram)
    }

    override fun hashCode(): Int {
        var result = hueHistogram.contentHashCode()
        result = 31 * result + saturationMean.hashCode()
        result = 31 * result + valueMean.hashCode()
        result = 31 * result + edgeDensity.hashCode()
        result = 31 * result + aspectRatio.hashCode()
        result = 31 * result + isLikelyAnime.hashCode()
        return result
    }
}

/**
 * 本地相似度的颜色分支。
 *
 * 分工：[SimilarityMath] 负责「哈希距离 → 0..100 基础分」，这里负责
 * 「颜色/构图是否像同一类图」以及把两者合成最终分数。
 * 全部是纯函数，不持有状态；唯一的 Android 依赖是 [profile] 里读 [Bitmap] 像素，
 * 真正的计算在 [profileFromPixels]，所以 JVM 单测可以完全绕过 Bitmap。
 *
 * 阈值和权重都写成具名常量，调参时只改一处；注释里标了「经验值」的都是拿图库实拍图
 * 和二次元插画对比调出来的，不是论文参数。
 */
object VisualSimilarity {

    /** 采样时缩到的长边像素数：64 已经够描述配色与线条密度，再大只是多花内存。 */
    private const val SAMPLE_MAX_EDGE = 64

    /** 色相桶数量：12 个桶，每桶 30 度。 */
    private const val HUE_BUCKET_COUNT = 12

    /** 每个色相桶覆盖的角度。 */
    private const val HUE_BUCKET_DEGREES = 30.0

    /**
     * 参与颜色统计的最小 alpha：低于它的像素视为「透明背景」直接跳过。
     * PNG 插画的透明背景 RGB 通常是 0，如果当成黑色统计，会把明度均值和无彩色桶一起拉偏。
     */
    private const val MIN_ALPHA = 16

    /** 相邻像素亮度差超过它就算一条「边」，经验值。 */
    private const val EDGE_LUMA_DELTA = 0.12

    // ---- 颜色相似度的四项权重（合计 1.0）----
    /** 色相直方图余弦相似：最能区分「配色完全不同」。 */
    private const val HUE_WEIGHT = 0.60
    /** 饱和度均值接近度。 */
    private const val SATURATION_WEIGHT = 0.15
    /** 明度均值接近度。 */
    private const val VALUE_WEIGHT = 0.15
    /** 长宽比接近度：同尺寸截图/插画往往同长宽比。 */
    private const val ASPECT_WEIGHT = 0.10

    // ---- 合成分数的参数 ----
    /** 两边都像二次元时，颜色分支占的权重（剩下 0.85 给哈希基础分）。 */
    private const val ANIME_COLOR_WEIGHT = 0.15
    /** 两边都像二次元时，哈希基础分的权重。 */
    private const val ANIME_BASE_WEIGHT = 0.85
    /** 只有一方像二次元时的固定扣分：风格不同，同样的哈希距离应该更不被信任。 */
    private const val MIXED_ANIME_PENALTY = 8.0
    /** 长宽比误差小于 5% 视为「同构图」，给一点奖励分。 */
    private const val ASPECT_TOLERANCE = 0.05
    /** 同构图奖励分。 */
    private const val ASPECT_BONUS = 3.0

    // ---- isLikelyAnime 的启发式阈值 ----
    /** 二次元插画通常高饱和（纯色块多）。 */
    private const val ANIME_MIN_SATURATION = 0.35
    /** 单色占比过高说明是「大片同色」，更像照片的天空/墙面，不像插画。 */
    private const val ANIME_MAX_HUE_SHARE = 0.65
    /** 插画线条多，边缘密度通常不低。 */
    private const val ANIME_MIN_EDGE_DENSITY = 0.08
    /** 太暗的图（夜景照片）不算。 */
    private const val ANIME_MIN_VALUE = 0.35
    /** 四条里满足几条才判定为「像二次元」。 */
    private const val ANIME_MIN_MATCHED_RULES = 3

    /** 空画像：尺寸非法或整张图全透明时使用，所有统计量为 0。 */
    private fun emptyProfile(aspectRatio: Double): ColorProfile = ColorProfile(
        hueHistogram = IntArray(HUE_BUCKET_COUNT),
        saturationMean = 0.0,
        valueMean = 0.0,
        edgeDensity = 0.0,
        aspectRatio = aspectRatio,
        isLikelyAnime = false,
    )

    /**
     * 读取 [bitmap] 的颜色画像。
     *
     * 先把长边缩到 [SAMPLE_MAX_EDGE]（比 64 小的图不放大，避免凭空造出细节），
     * 再 getPixels 交给 [profileFromPixels]。
     * 只回收自己创建出来的缩放副本：调用方的 bitmap 可能还挂在 UI 缩略图上，绝不能 recycle。
     */
    fun profile(bitmap: Bitmap): ColorProfile {
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return emptyProfile(0.0)
        // 长宽比用原图尺寸算，缩放的四舍五入不会影响它。
        val sourceAspect = sourceWidth.toDouble() / sourceHeight

        val longEdge = max(sourceWidth, sourceHeight)
        if (longEdge <= SAMPLE_MAX_EDGE) {
            val pixels = IntArray(sourceWidth * sourceHeight)
            bitmap.getPixels(pixels, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight)
            return buildProfile(sourceWidth, sourceHeight, pixels, sourceAspect)
        }

        val scale = SAMPLE_MAX_EDGE.toDouble() / longEdge
        val targetWidth = max(1, (sourceWidth * scale).roundToInt())
        val targetHeight = max(1, (sourceHeight * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        return try {
            val pixels = IntArray(targetWidth * targetHeight)
            scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
            buildProfile(targetWidth, targetHeight, pixels, sourceAspect)
        } finally {
            // createScaledBitmap 在尺寸相同时可能直接返回入参，回收前必须判一下。
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /**
     * 从 ARGB 像素数组算颜色画像（纯 JVM，可单测）。
     *
     * 约定：[argb] 是按行优先、长度至少为 width * height 的像素数组，元素格式为 0xAARRGGBB。
     * 几乎全透明（alpha < [MIN_ALPHA]）的像素不计入颜色统计，也不参与边缘密度计算；
     * 因此 [ColorProfile.hueHistogram] 的总和等于「参与统计的采样点数」，而不是宽 * 高。
     * 尺寸非法或数组太短时返回空画像（不抛异常：索引期一张坏图不该打断整批）。
     */
    fun profileFromPixels(width: Int, height: Int, argb: IntArray): ColorProfile {
        val aspectRatio = if (width > 0 && height > 0) width.toDouble() / height else 0.0
        return buildProfile(width, height, argb, aspectRatio)
    }

    /**
     * 颜色相似度，0..100。
     *
     * 四项加权：
     * - 色相直方图余弦相似 60%：主色调是否一致，最能区分「完全不同的配色」；
     * - 饱和度均值接近度 15%；
     * - 明度均值接近度 15%；
     * - 长宽比接近度 10%。
     *
     * 任一项在 0..1，加权后乘 100；对空画像（全 0）也不会出现 NaN：
     * 色相项退化为 0、饱和度/明度项为满分、长宽比无效时按 0 计，两张空画像互比是 40 分。
     * 调用方应先判断画像是否有效（例如宽高 > 0）再决定要不要展示这个分数。
     */
    fun colorSimilarity(a: ColorProfile, b: ColorProfile): Double {
        val hue = cosineSimilarity(a.hueHistogram, b.hueHistogram)
        val saturation = 1.0 - abs(a.saturationMean - b.saturationMean).coerceIn(0.0, 1.0)
        val value = 1.0 - abs(a.valueMean - b.valueMean).coerceIn(0.0, 1.0)
        val aspect = aspectCloseness(a.aspectRatio, b.aspectRatio)
        val score = 100.0 * (
            HUE_WEIGHT * hue +
                SATURATION_WEIGHT * saturation +
                VALUE_WEIGHT * value +
                ASPECT_WEIGHT * aspect
            )
        return score.coerceIn(0.0, 100.0)
    }

    /**
     * 把哈希距离与颜色画像合成最终相似度，0..100。
     *
     * 规则：
     * 1. 基础分沿用 [SimilarityMath.similarity]：100 * (1 - (0.6 * dhash + 0.4 * phash) / 64)；
     * 2. 两张都像二次元：基础分占 85%，颜色相似度占 15%（同风格下颜色比结构更可信）；
     * 3. 只有一方像二次元：基础分直接扣 8（跨风格时同样的哈希距离应该更不被信任）；
     * 4. 长宽比误差小于 5%：再加 3（同构图/同尺寸截图，通常是同一张图的不同版本）；
     * 5. 最后 clamp 到 0..100。
     *
     * 距离参数会先按 0..64 夹紧：调用方传负数或超范围的脏数据不会把分数顶到 100 以上。
     */
    fun combinedScore(
        dhashDist: Int,
        phashDist: Int,
        base: ColorProfile,
        candidate: ColorProfile,
    ): Double {
        val dhash = SimilarityMath.clampDistance(dhashDist)
        val phash = SimilarityMath.clampDistance(phashDist)
        var score = SimilarityMath.similarity(dhash, phash)

        val baseAnime = base.isLikelyAnime
        val candidateAnime = candidate.isLikelyAnime
        when {
            baseAnime && candidateAnime ->
                score = score * ANIME_BASE_WEIGHT + colorSimilarity(base, candidate) * ANIME_COLOR_WEIGHT
            // xor 等价于「只有一方像二次元」。
            baseAnime xor candidateAnime -> score -= MIXED_ANIME_PENALTY
        }

        if (aspectWithinTolerance(base.aspectRatio, candidate.aspectRatio, ASPECT_TOLERANCE)) {
            score += ASPECT_BONUS
        }
        return score.coerceIn(0.0, 100.0)
    }

    /**
     * 所有像素统计的唯一入口：[profileFromPixels] 与 [profile] 最后都走到这里。
     *
     * 参数校验放在最前面：负数尺寸、像素数组长度不够都返回空画像而不是抛异常
     * （索引期一张坏图不应该让整批相似度计算失败）。
     */
    private fun buildProfile(
        width: Int,
        height: Int,
        pixels: IntArray,
        aspectRatio: Double,
    ): ColorProfile {
        if (width <= 0 || height <= 0) return emptyProfile(aspectRatio)
        val pixelCount = width * height
        if (pixels.size < pixelCount) return emptyProfile(aspectRatio)

        val histogram = IntArray(HUE_BUCKET_COUNT)
        // luma/opaque 用一维数组按行优先存，index = y * width + x；边缘统计要回头查邻居，所以先缓存。
        val luma = DoubleArray(pixelCount)
        val opaque = BooleanArray(pixelCount)
        var sampled = 0
        var saturationSum = 0.0
        var valueSum = 0.0

        for (index in 0 until pixelCount) {
            val pixel = pixels[index]
            val alpha = (pixel ushr 24) and 0xFF
            // 透明像素：不参与颜色统计，也不参与边缘统计（否则 PNG 的透明边会被当成边缘）。
            if (alpha < MIN_ALPHA) continue

            val red = ((pixel ushr 16) and 0xFF) / 255.0
            val green = ((pixel ushr 8) and 0xFF) / 255.0
            val blue = (pixel and 0xFF) / 255.0
            val maxChannel = max(red, max(green, blue))
            val minChannel = min(red, min(green, blue))
            val delta = maxChannel - minChannel

            val value = maxChannel
            // 纯黑/纯灰时 maxChannel 为 0，直接给 0，避免除零。
            val saturation = if (maxChannel <= 0.0) 0.0 else delta / maxChannel
            histogram[hueBucketOf(red, green, blue, maxChannel, delta)]++

            saturationSum += saturation
            valueSum += value
            // Rec.601 亮度：与人眼感知接近，也够快。
            luma[index] = 0.299 * red + 0.587 * green + 0.114 * blue
            opaque[index] = true
            sampled++
        }

        // 整张图都透明：没有可统计的像素，返回空画像（色相桶全 0）。
        if (sampled == 0) return emptyProfile(aspectRatio)

        val saturationMean = saturationSum / sampled
        val valueMean = valueSum / sampled
        val edgeDensity = edgeDensityOf(luma, opaque, width, height)
        return ColorProfile(
            hueHistogram = histogram,
            saturationMean = saturationMean,
            valueMean = valueMean,
            edgeDensity = edgeDensity,
            aspectRatio = aspectRatio,
            isLikelyAnime = isLikelyAnime(histogram, sampled, saturationMean, valueMean, edgeDensity),
        )
    }

    /** 色相（0..360 度）落在 12 个 30 度桶的哪一个；无彩色（delta 为 0）统一落第 0 桶。 */
    private fun hueBucketOf(
        red: Double,
        green: Double,
        blue: Double,
        maxChannel: Double,
        delta: Double,
    ): Int {
        if (delta <= 0.0 || maxChannel <= 0.0) return 0
        val hueDegrees = when (maxChannel) {
            red -> 60.0 * (((green - blue) / delta) % 6.0)
            green -> 60.0 * (((blue - red) / delta) + 2.0)
            else -> 60.0 * (((red - green) / delta) + 4.0)
        }
        // 公式在 maxChannel == red 时可能给出负数，先归一到 0..360。
        val normalized = if (hueDegrees < 0.0) hueDegrees + 360.0 else hueDegrees
        return min((normalized / HUE_BUCKET_DEGREES).toInt(), HUE_BUCKET_COUNT - 1).coerceAtLeast(0)
    }

    /**
     * 边缘密度：相邻像素亮度差超过 [EDGE_LUMA_DELTA] 的比例，0..1。
     *
     * 只看右、下两个方向（不重复计数），分母是「两端都不透明的相邻对」。
     * 全透明图或 1x1 图的相邻对数为 0，返回 0.0。
     */
    private fun edgeDensityOf(
        luma: DoubleArray,
        opaque: BooleanArray,
        width: Int,
        height: Int,
    ): Double {
        var pairs = 0
        var edges = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (!opaque[index]) continue
                if (x + 1 < width && opaque[index + 1]) {
                    pairs++
                    if (abs(luma[index] - luma[index + 1]) > EDGE_LUMA_DELTA) edges++
                }
                val below = index + width
                if (y + 1 < height && opaque[below]) {
                    pairs++
                    if (abs(luma[index] - luma[below]) > EDGE_LUMA_DELTA) edges++
                }
            }
        }
        return if (pairs == 0) 0.0 else edges.toDouble() / pairs
    }

    /** 两个直方图的余弦相似度，0..1。长度不等时按较短的对齐；任一方为空向量返回 0.0。 */
    private fun cosineSimilarity(a: IntArray, b: IntArray): Double {
        val size = min(a.size, b.size)
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (index in 0 until size) {
            dot += a[index].toDouble() * b[index]
            normA += a[index].toDouble() * a[index]
            normB += b[index].toDouble() * b[index]
        }
        if (normA <= 0.0 || normB <= 0.0) return 0.0
        return (dot / (sqrt(normA) * sqrt(normB))).coerceIn(0.0, 1.0)
    }

    /** 长宽比接近度，0..1：1 表示完全一样，0 表示差了整整一个比例。 */
    private fun aspectCloseness(a: Double, b: Double): Double {
        if (a <= 0.0 || b <= 0.0) return if (a == b) 1.0 else 0.0
        val reference = max(a, b)
        return (1.0 - abs(a - b) / reference).coerceIn(0.0, 1.0)
    }

    /** 长宽比误差是否小于 [tolerance]（相对误差）；任一方无效时返回 false。 */
    private fun aspectWithinTolerance(a: Double, b: Double, tolerance: Double): Boolean {
        if (a <= 0.0 || b <= 0.0) return false
        return abs(a - b) / max(a, b) < tolerance
    }

    /**
     * 「像不像二次元插画」的启发式判定。
     *
     * 这是启发式，不是分类器：只看四个便宜的统计量，四条里满足至少三条即判定为「像」，
     * 故意留出容错，宁可把实拍人像偶尔判成插画，也不要把插画漏判。
     * - 饱和度均值 >= 0.35：插画纯色块多；
     * - 最大色相桶占比 < 0.65：避免「整张图一片同色」（天空、白墙照片）被误判；
     * - 边缘密度 >= 0.08：插画有描线；
     * - 明度均值 >= 0.35：排除夜景/暗调照片。
     */
    private fun isLikelyAnime(
        histogram: IntArray,
        sampled: Int,
        saturationMean: Double,
        valueMean: Double,
        edgeDensity: Double,
    ): Boolean {
        if (sampled <= 0) return false
        val maxBucketShare = (histogram.maxOrNull() ?: 0).toDouble() / sampled
        var matched = 0
        if (saturationMean >= ANIME_MIN_SATURATION) matched++
        if (maxBucketShare < ANIME_MAX_HUE_SHARE) matched++
        if (edgeDensity >= ANIME_MIN_EDGE_DENSITY) matched++
        if (valueMean >= ANIME_MIN_VALUE) matched++
        return matched >= ANIME_MIN_MATCHED_RULES
    }
}
