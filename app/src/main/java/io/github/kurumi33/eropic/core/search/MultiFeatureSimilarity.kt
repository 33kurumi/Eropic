package io.github.kurumi33.eropic.core.search

import io.github.kurumi33.eropic.core.hash.GrayImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 256 位感知哈希：把 64x64 灰度图做 2D DCT-II，取左上 16x16 低频系数与中位数比较。
 *
 * 为什么要在 64 bit 的 pHash 之外再加一份 256 bit：64 位只够描述「大结构」，
 * 同一角色不同姿势 / 同构图不同裁剪时，64 位哈希的汉明距离经常落在 20 上下，
 * 和「完全不同的图」拉不开差距；256 位（16x16 低频）保留了更多中频细节，
 * 在「整体构图相同、局部有改动」的图上区分度明显更好。
 *
 * 用四个 Long 拼起来，位序与 [io.github.kurumi33.eropic.core.hash.PHash] 保持一致：
 * 第 i 位对应低频系数 low[i]（low[0] 是 DC），i / 64 决定第几个 Long。
 * 这么做是为了调参时能用同一套汉明距离工具，也方便和 64 位版本对照。
 */
data class Hash256(
    val w0: Long,
    val w1: Long,
    val w2: Long,
    val w3: Long,
) {
    /** 与本哈希的汉明距离，0..[BIT_COUNT]。 */
    fun hammingDistance(other: Hash256): Int =
        java.lang.Long.bitCount(w0 xor other.w0) +
            java.lang.Long.bitCount(w1 xor other.w1) +
            java.lang.Long.bitCount(w2 xor other.w2) +
            java.lang.Long.bitCount(w3 xor other.w3)

    companion object {
        const val BIT_COUNT = 256

        /** 全 0 哈希：仅用于占位，不要拿它参与相似度计算。 */
        val ZERO = Hash256(0L, 0L, 0L, 0L)
    }
}

/**
 * 4x4 网格里的一个颜色块。
 *
 * 色相是**循环量**（0 度和 359 度几乎一样），不能直接相减，
 * 所以这里存的是「饱和度加权的色相单位向量」的均值分量，
 * 比较时用向量夹角，天然处理 0/360 环绕。
 *
 * @property hueX 色相向量的 x 分量（按饱和度加权后再除以块内不透明像素数），-1..1。
 * @property hueY 色相向量的 y 分量，-1..1。
 * @property hueWeight 块内平均饱和度：越大说明颜色越浓，色相越可信；接近 0 时色相无意义
 *   （灰/白/黑块），比较时退化为中性分。
 * @property saturation 块内 HSV 饱和度均值，0..1。
 * @property value 块内 HSV 明度均值，0..1。
 * @property filled 块内是否有不透明像素；全透明的块不参与布局比较。
 */
data class ColorBlock(
    val hueX: Double,
    val hueY: Double,
    val hueWeight: Double,
    val saturation: Double,
    val value: Double,
    val filled: Boolean,
)

/**
 * 一张图的「多特征画像」：256 位感知哈希 + 4x4 颜色布局 + 24 桶色相直方图 + 边缘密度 + 长宽比。
 *
 * 全部由纯字节/浮点数组构成，采样子图长边固定为 [MultiFeatureSimilarity.SAMPLE_MAX_EDGE]（64），
 * 所以安卓侧只要把 Bitmap 缩到 64 再取像素（[BitmapFeatures.extract]），
 * 纯 JVM 单测可以直接喂 [IntArray]（[MultiFeatureSimilarity.featuresFromPixels]），
 * 两边走同一套计算路径，分数才可比。
 *
 * @property hash256 256 位 pHash；尺寸非法/全透明时可能为 null。
 * @property blocks 16 个颜色块，行优先（先 y 后 x）。
 * @property hueHistogram 24 个 15 度色相桶的像素计数，只统计饱和度足够的「有彩色」像素。
 * @property chromaticPixels 参与色相直方图统计的像素数；为 0 说明整张图接近黑白。
 * @property saturationMean 全图饱和度均值，0..1。
 * @property valueMean 全图明度均值，0..1。
 * @property edgeDensity 相邻像素亮度差超过阈值的比例，0..1。
 * @property aspectRatio 宽 / 高（用解码后的原图尺寸算，不受采样缩放影响）。
 */
data class ImageFeatures(
    val hash256: Hash256?,
    val blocks: List<ColorBlock>,
    val hueHistogram: IntArray,
    val chromaticPixels: Int,
    val saturationMean: Double,
    val valueMean: Double,
    val edgeDensity: Double,
    val aspectRatio: Double,
) {
    // data class 默认对 IntArray 用引用相等，这里改成内容相等，避免单测/缓存里出现「同图不等于同图」。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageFeatures) return false
        return hash256 == other.hash256 &&
            blocks == other.blocks &&
            hueHistogram.contentEquals(other.hueHistogram) &&
            chromaticPixels == other.chromaticPixels &&
            saturationMean == other.saturationMean &&
            valueMean == other.valueMean &&
            edgeDensity == other.edgeDensity &&
            aspectRatio == other.aspectRatio
    }

    override fun hashCode(): Int {
        var result = hash256?.hashCode() ?: 0
        result = 31 * result + blocks.hashCode()
        result = 31 * result + hueHistogram.contentHashCode()
        result = 31 * result + chromaticPixels
        result = 31 * result + saturationMean.hashCode()
        result = 31 * result + valueMean.hashCode()
        result = 31 * result + edgeDensity.hashCode()
        result = 31 * result + aspectRatio.hashCode()
        return result
    }
}

/**
 * 综合分与四个分项，全部 0..100。
 *
 * 为什么要暴露分项：用户看到「这张只有 62%」时，需要一个能说清的理由——
 * 是配色差（[colorLayout] / [histogram]）还是构图差（[hash]）还是画幅不同（[aspect]）。
 * UI 现在只显示 [total]，但字段留着，详情页想展示分项时不用改接口。
 */
data class SimilarityScores(
    val colorLayout: Double,
    val hash: Double,
    val histogram: Double,
    val aspect: Double,
    val total: Double,
)

/**
 * 本地以图搜图的「多特征相似度」：把 1 个 64 位 pHash、1 个 256 位 pHash、
 * 4x4 颜色布局、24 桶色相直方图、边缘密度、长宽比合成一个 0..100 的分数。
 *
 * 为什么不用单一哈希距离：同一角色的两张插画，发型/姿势/背景都可能换，
 * 64 位哈希的汉明距离会很大；但「四个角的配色」「整体亮暗分布」往往一致。
 * 反过来，两张构图相同但配色完全不同的图（同一模板换色），哈希距离也会小。
 * 单看任何一项都会漏，所以拆成四个互不相关的分项加权：
 * - 颜色布局 0.4：对「同角色不同图」最有效，是这次升级的重点；
 * - 哈希 0.3：64 位 pHash 与 256 位 pHash 各占一部分，负责「同一张图」；
 * - 直方图 0.2：全局色相分布 + 线条密度，防止局部偶然撞色；
 * - 长宽比 0.1：对「同一张图被裁过/缩放过」给高一点，对异形图降权。
 *
 * 全部是纯函数、无状态、不依赖 Android；Bitmap 入口单独放在 [BitmapFeatures] 里，
 * 这样纯 JVM 单测可以在没有 Robolectric 的情况下覆盖所有评分逻辑。
 */
object MultiFeatureSimilarity {

    /** 采样子图的长边：64 已足够描述配色布局与 16x16 低频哈希，再大只是多花内存/时间。 */
    const val SAMPLE_MAX_EDGE = 64

    /** 颜色布局切成 4x4 共 16 块（与需求里的 4x4 网格一致）。 */
    const val BLOCK_GRID = 4

    /** 色相直方图桶数：24 个桶，每桶 15 度。 */
    const val HUE_BUCKET_COUNT = 24

    /** 参与颜色统计的最小 alpha；低于它视为透明背景，避免 PNG 透明区域被当成黑色。 */
    private const val MIN_ALPHA = 16

    /** 低于这个饱和度视为「无彩色」：色相不可信，不计入直方图/布局色相向量。 */
    private const val MIN_HUE_SATURATION = 0.10

    /** 布局比较时，块内平均饱和度低于它就把色相项退化成中性分（灰色块的色相是噪声）。 */
    private const val MIN_HUE_WEIGHT = 0.08

    /** 相邻像素亮度差超过它就算一条「边」，与 VisualSimilarity 保持同一经验值。 */
    private const val EDGE_LUMA_DELTA = 0.12

    /** 颜色布局内部权重：色相最重要，饱和/明度各占两成。 */
    private const val LAYOUT_HUE_WEIGHT = 0.60
    private const val LAYOUT_SATURATION_WEIGHT = 0.20
    private const val LAYOUT_VALUE_WEIGHT = 0.20

    /** 直方图分项内部权重：色相分布为主，边缘密度为辅（线条密度区分插画/照片）。 */
    private const val HISTOGRAM_HUE_WEIGHT = 0.80
    private const val HISTOGRAM_EDGE_WEIGHT = 0.20

    /** 边缘密度差到这个程度就算完全不同（0.25 约等于「一张纯色图 vs 一张线稿」）。 */
    private const val EDGE_DENSITY_MAX_DELTA = 0.25

    /** 哈希分项内部权重：256 位占六成（细节更多），64 位占四成（跨版本兼容、抗噪）。 */
    private const val HASH_64_WEIGHT = 0.40
    private const val HASH_256_WEIGHT = 0.60

    /** 长宽比相对误差达到 50% 时该项归零。 */
    private const val ASPECT_MAX_ERROR = 0.50

    /** 四个分项的最终权重（合计 1.0），与需求给出的建议权重一致。 */
    private const val SCORE_COLOR_LAYOUT_WEIGHT = 0.40
    private const val SCORE_HASH_WEIGHT = 0.30
    private const val SCORE_HISTOGRAM_WEIGHT = 0.20
    private const val SCORE_ASPECT_WEIGHT = 0.10

    /** 无法比较时的中性分（0.5 表示「既不加分也不扣分」）。 */
    private const val NEUTRAL = 0.5

    // ---- 256 位 DCT 哈希的内部参数 ----
    /** DCT 输入网格：64x64（比 64 位版本的 32x32 大一级，低频带宽才够 16x16）。 */
    private const val DCT_SIZE = 64
    /** 取左上 16x16 低频系数 = 256 bit。 */
    private const val DCT_LOW = 16
    /** 系数死区：纯色图的 AC 理论为 0，浮点累加会留下极噪声，直接比中位数会让哈希不稳定。 */
    private const val DCT_EPSILON = 1e-6

    /**
     * 从 ARGB 像素数组算完整画像（纯 JVM）。
     *
     * @param argb 行优先、长度至少 width*height 的 0xAARRGGBB 像素数组。
     *   长度不足或宽高非法时返回 null（索引期一张坏图不该打断整批，调用方跳过即可）。
     */
    fun featuresFromPixels(width: Int, height: Int, argb: IntArray): ImageFeatures? {
        if (width <= 0 || height <= 0) return null
        if (argb.size < width * height) return null
        return buildFeatures(width, height, argb, width.toDouble() / height)
    }

    /**
     * 同上，但长宽比单独传：Bitmap 入口先把图缩到 64 长边再取像素，
     * 此时 width/height 是缩放后的比例，而长宽比分项希望用原图比例（缩放的取整误差不该影响它）。
     */
    internal fun featuresFromPixels(
        width: Int,
        height: Int,
        argb: IntArray,
        aspectRatio: Double,
    ): ImageFeatures? {
        if (width <= 0 || height <= 0) return null
        if (argb.size < width * height) return null
        return buildFeatures(width, height, argb, aspectRatio)
    }

    /** 四个分项加权成总分，0..100。分项可以是脏数据（会被夹到范围里）。 */
    fun combine(
        colorLayout: Double,
        hash: Double,
        histogram: Double,
        aspect: Double,
    ): Double = (
        SCORE_COLOR_LAYOUT_WEIGHT * colorLayout.coerceIn(0.0, 100.0) +
            SCORE_HASH_WEIGHT * hash.coerceIn(0.0, 100.0) +
            SCORE_HISTOGRAM_WEIGHT * histogram.coerceIn(0.0, 100.0) +
            SCORE_ASPECT_WEIGHT * aspect.coerceIn(0.0, 100.0)
        ).coerceIn(0.0, 100.0)

    /**
     * 多特征综合评分。
     *
     * @param phash64Distance 64 位 pHash 的汉明距离（库里的 phash 列；缺 pHash 时调用方传 dHash 距离兜底）。
     */
    fun compare(
        phash64Distance: Int,
        base: ImageFeatures,
        candidate: ImageFeatures,
    ): SimilarityScores {
        val layout = layoutSimilarity(base.blocks, candidate.blocks)
        val hash = hashSimilarity(phash64Distance, base, candidate)
        val histogram = histogramSimilarity(base, candidate)
        val aspect = aspectSimilarity(base.aspectRatio, candidate.aspectRatio)
        return SimilarityScores(
            colorLayout = layout,
            hash = hash,
            histogram = histogram,
            aspect = aspect,
            total = combine(layout, hash, histogram, aspect),
        )
    }

    /** 4x4 颜色布局相似度，0..100：逐块比较色相（循环距离）、饱和度、明度，再取平均。 */
    fun layoutSimilarity(base: List<ColorBlock>, candidate: List<ColorBlock>): Double {
        val size = min(base.size, candidate.size)
        if (size == 0) return NEUTRAL * 100.0
        var sum = 0.0
        var used = 0
        for (index in 0 until size) {
            val a = base[index]
            val b = candidate[index]
            // 任一方在该块是全透明的：这个位置没有可比的颜色，跳过（不当作 0 分）。
            if (!a.filled || !b.filled) continue
            val hue = hueSimilarity(a, b)
            val saturation = 1.0 - abs(a.saturation - b.saturation).coerceIn(0.0, 1.0)
            val value = 1.0 - abs(a.value - b.value).coerceIn(0.0, 1.0)
            sum += LAYOUT_HUE_WEIGHT * hue + LAYOUT_SATURATION_WEIGHT * saturation +
                LAYOUT_VALUE_WEIGHT * value
            used++
        }
        // 整张图都透明（比如空 PNG）时没有可比信息，给中性分而不是 0，避免误伤。
        return if (used == 0) NEUTRAL * 100.0 else 100.0 * sum / used
    }

    /** 64 位 + 256 位 pHash 合成哈希分项，0..100。任一 256 位哈希缺失时退化为只用 64 位。 */
    fun hashSimilarity(phash64Distance: Int, base: ImageFeatures, candidate: ImageFeatures): Double {
        // 直接夹到 0..64：不借用 SimilarityMath，保证这个文件只依赖 core.hash，纯 JVM 可单测
        val distance64 = phash64Distance.coerceIn(0, 64)
        val score64 = 100.0 * (1.0 - distance64 / 64.0)
        val base256 = base.hash256 ?: return score64
        val candidate256 = candidate.hash256 ?: return score64
        val distance256 = base256.hammingDistance(candidate256)
        val score256 = 100.0 * (1.0 - distance256 / Hash256.BIT_COUNT.toDouble())
        return HASH_64_WEIGHT * score64 + HASH_256_WEIGHT * score256
    }

    /** 全局色相直方图 + 边缘密度，0..100。两边都接近黑白（无彩色）时色相项按中性分算。 */
    fun histogramSimilarity(base: ImageFeatures, candidate: ImageFeatures): Double {
        val hue = if (base.chromaticPixels == 0 || candidate.chromaticPixels == 0) {
            NEUTRAL
        } else {
            cosineSimilarity(base.hueHistogram, candidate.hueHistogram)
        }
        val edge = 1.0 - (abs(base.edgeDensity - candidate.edgeDensity) / EDGE_DENSITY_MAX_DELTA)
            .coerceIn(0.0, 1.0)
        return 100.0 * (HISTOGRAM_HUE_WEIGHT * hue + HISTOGRAM_EDGE_WEIGHT * edge)
    }

    /** 长宽比相似度，0..100：相对误差 0 得 100，误差达到 [ASPECT_MAX_ERROR] 得 0。 */
    fun aspectSimilarity(base: Double, candidate: Double): Double {
        if (base <= 0.0 || candidate <= 0.0) return NEUTRAL * 100.0
        val relative = abs(base - candidate) / max(base, candidate)
        return (100.0 * (1.0 - relative / ASPECT_MAX_ERROR)).coerceIn(0.0, 100.0)
    }

    /** 两个色相向量的循环相似度：0..1，用「1 + cos 夹角」的一半，0 度与 359 度几乎为 1。 */
    private fun hueSimilarity(a: ColorBlock, b: ColorBlock): Double {
        // 同一个块（同图、或缩放后统计结果恰好一致）必须拿满分。
        // 不能省：块内如果色相互相抵消（对角渐变、混色块）向量长度接近 0，
        // 下面会按「色相不可信」给中性分，同图就永远拿不到 100 了。
        if (a == b) return 1.0
        val lengthA = sqrt(a.hueX * a.hueX + a.hueY * a.hueY)
        val lengthB = sqrt(b.hueX * b.hueX + b.hueY * b.hueY)
        // 色相不可信（灰块/低饱和/向量互相抵消）时不猜，给中性分。
        if (min(a.hueWeight, b.hueWeight) < MIN_HUE_WEIGHT || lengthA < 1e-6 || lengthB < 1e-6) {
            return NEUTRAL
        }
        val cosine = (a.hueX * b.hueX + a.hueY * b.hueY) / (lengthA * lengthB)
        return (1.0 + cosine.coerceIn(-1.0, 1.0)) / 2.0
    }

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

    /**
     * 唯一的像素统计入口：一次遍历同时算布局、直方图、边缘密度与 256 位哈希。
     *
     * 只遍历一遍的理由：这个函数对每张候选图都会调用，遍历次数直接影响搜图耗时；
     * 边缘密度需要回头查右/下邻居，所以把 luma 与不透明标记先缓存下来。
     */
    private fun buildFeatures(
        width: Int,
        height: Int,
        pixels: IntArray,
        aspectRatio: Double,
    ): ImageFeatures {
        val pixelCount = width * height
        val luma = DoubleArray(pixelCount)
        val opaque = BooleanArray(pixelCount)

        val blockCount = BLOCK_GRID * BLOCK_GRID
        val blockSaturationSum = DoubleArray(blockCount)
        val blockValueSum = DoubleArray(blockCount)
        val blockPixelCount = IntArray(blockCount)
        val blockHueX = DoubleArray(blockCount)
        val blockHueY = DoubleArray(blockCount)
        val blockHueWeight = DoubleArray(blockCount)

        val histogram = IntArray(HUE_BUCKET_COUNT)
        var chromaticPixels = 0
        var saturationSum = 0.0
        var valueSum = 0.0
        var sampled = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val pixel = pixels[index]
                val alpha = (pixel ushr 24) and 0xFF
                // 透明像素不参与颜色统计，也不参与边缘统计，否则 PNG 的透明边会被当成边缘。
                if (alpha < MIN_ALPHA) continue

                val red = ((pixel ushr 16) and 0xFF) / 255.0
                val green = ((pixel ushr 8) and 0xFF) / 255.0
                val blue = (pixel and 0xFF) / 255.0
                val maxChannel = max(red, max(green, blue))
                val minChannel = min(red, min(green, blue))
                val delta = maxChannel - minChannel
                val saturation = if (maxChannel <= 0.0) 0.0 else delta / maxChannel
                val value = maxChannel

                luma[index] = 0.299 * red + 0.587 * green + 0.114 * blue
                opaque[index] = true
                sampled++
                saturationSum += saturation
                valueSum += value

                val blockX = min(BLOCK_GRID - 1, x * BLOCK_GRID / width)
                val blockY = min(BLOCK_GRID - 1, y * BLOCK_GRID / height)
                val blockIndex = blockY * BLOCK_GRID + blockX
                blockSaturationSum[blockIndex] += saturation
                blockValueSum[blockIndex] += value
                blockPixelCount[blockIndex]++

                // 只有「够浓」的像素才贡献色相：灰/白/黑的色相是噪声。
                if (saturation < MIN_HUE_SATURATION || delta <= 0.0) continue
                val hueRadians = hueDegreesOf(red, green, blue, maxChannel, delta) * PI / 180.0
                val hueCos = cos(hueRadians)
                val hueSin = sin(hueRadians)
                val bucket = min(HUE_BUCKET_COUNT - 1, (hueRadians * 180.0 / PI / 15.0).toInt())
                    .coerceAtLeast(0)
                histogram[bucket]++
                chromaticPixels++
                // 按饱和度加权：颜色越浓的像素对「这块是什么颜色」的话语权越大。
                blockHueX[blockIndex] += saturation * hueCos
                blockHueY[blockIndex] += saturation * hueSin
                blockHueWeight[blockIndex] += saturation
            }
        }

        val blocks = ArrayList<ColorBlock>(blockCount)
        for (index in 0 until blockCount) {
            val count = blockPixelCount[index]
            if (count == 0) {
                blocks += ColorBlock(0.0, 0.0, 0.0, 0.0, 0.0, filled = false)
                continue
            }
            blocks += ColorBlock(
                // 除以块内像素数而不是有彩色像素数：色相向量长度 < 1 本身就表示「这块颜色分散」，
                // 比较时用归一化后的夹角，长度只用来判断可信度。
                hueX = blockHueX[index] / count,
                hueY = blockHueY[index] / count,
                hueWeight = blockHueWeight[index] / count,
                saturation = blockSaturationSum[index] / count,
                value = blockValueSum[index] / count,
                filled = true,
            )
        }

        val edgeDensity = if (sampled == 0) 0.0 else edgeDensityOf(luma, opaque, width, height)
        // 采样时缩放过，尺寸与原始宽高不同，哈希只关心灰度结构；aspectRatio 用调用方给的原图比例。
        val gray = GrayImage.fromArgb(pixels.copyOf(pixelCount), width, height)
        val hash256 = DctHash256.compute(gray)

        return ImageFeatures(
            hash256 = hash256,
            blocks = blocks,
            hueHistogram = histogram,
            chromaticPixels = chromaticPixels,
            saturationMean = if (sampled == 0) 0.0 else saturationSum / sampled,
            valueMean = if (sampled == 0) 0.0 else valueSum / sampled,
            edgeDensity = edgeDensity,
            aspectRatio = aspectRatio,
        )
    }

    /** 色相角（0..360）：与 VisualSimilarity 里的公式一致，便于两套分数对照调参。 */
    private fun hueDegreesOf(
        red: Double,
        green: Double,
        blue: Double,
        maxChannel: Double,
        delta: Double,
    ): Double {
        val hue = when (maxChannel) {
            red -> 60.0 * (((green - blue) / delta) % 6.0)
            green -> 60.0 * (((blue - red) / delta) + 2.0)
            else -> 60.0 * (((red - green) / delta) + 4.0)
        }
        return if (hue < 0.0) hue + 360.0 else hue
    }

    /** 边缘密度：只看右/下邻居（不重复计数），分母是「两端都不透明的相邻对」。 */
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

    /**
     * 256 位 DCT 哈希（16x16 低频）。
     *
     * 与 [io.github.kurumi33.eropic.core.hash.PHash] 同一套算法，只是 N/LOW 从 32/8 变成 64/16：
     * - 64x64 输入保证 16x16 低频对应的是「真正的低频带」，不是把 32 网格的上半部分搬过来；
     * - DC 单独按符号判定，其余系数与 AC 中位数比较，避免整体亮度影响。
     * 朴素 2D DCT（两趟 1D），单张 64x64 约 52 万次乘加——只在精排的候选图上算，不在候选扫描里算。
     */
    private object DctHash256 {

        private val cosTable: Array<DoubleArray> = Array(DCT_SIZE) { u ->
            DoubleArray(DCT_SIZE) { x ->
                cos((2.0 * x + 1.0) * u * PI / (2.0 * DCT_SIZE))
            }
        }

        private val alpha: DoubleArray = DoubleArray(DCT_SIZE) { u ->
            if (u == 0) sqrt(1.0 / DCT_SIZE) else sqrt(2.0 / DCT_SIZE)
        }

        fun compute(gray: GrayImage): Hash256 {
            val image = if (gray.width == DCT_SIZE && gray.height == DCT_SIZE) {
                gray
            } else {
                gray.resize(DCT_SIZE, DCT_SIZE)
            }
            val dct = dct2d(image)
            val low = DoubleArray(DCT_LOW * DCT_LOW)
            for (y in 0 until DCT_LOW) {
                for (x in 0 until DCT_LOW) {
                    val coefficient = dct[y * DCT_SIZE + x]
                    low[y * DCT_LOW + x] = if (abs(coefficient) < DCT_EPSILON) 0.0 else coefficient
                }
            }
            // 中位数排除 DC，避免整体亮度把一半 bit 顶成 1。
            val median = medianOf(low, from = 1)
            val words = LongArray(4)
            if (low[0] > 0.0) words[0] = 1L
            for (index in 1 until low.size) {
                if (low[index] > median) {
                    words[index / 64] = words[index / 64] or (1L shl (index % 64))
                }
            }
            return Hash256(words[0], words[1], words[2], words[3])
        }

        private fun dct2d(image: GrayImage): DoubleArray {
            val rows = Array(DCT_SIZE) { DoubleArray(DCT_SIZE) }
            val rowInput = DoubleArray(DCT_SIZE)
            for (y in 0 until DCT_SIZE) {
                for (x in 0 until DCT_SIZE) rowInput[x] = image[x, y].toDouble()
                val transformed = dct1d(rowInput)
                for (x in 0 until DCT_SIZE) rows[y][x] = transformed[x]
            }
            val out = DoubleArray(DCT_SIZE * DCT_SIZE)
            val columnInput = DoubleArray(DCT_SIZE)
            for (x in 0 until DCT_SIZE) {
                for (y in 0 until DCT_SIZE) columnInput[y] = rows[y][x]
                val transformed = dct1d(columnInput)
                for (y in 0 until DCT_SIZE) out[y * DCT_SIZE + x] = transformed[y]
            }
            return out
        }

        private fun dct1d(input: DoubleArray): DoubleArray {
            val out = DoubleArray(DCT_SIZE)
            for (u in 0 until DCT_SIZE) {
                var sum = 0.0
                val cosRow = cosTable[u]
                for (x in 0 until DCT_SIZE) sum += input[x] * cosRow[x]
                out[u] = alpha[u] * sum
            }
            return out
        }

        /** 从 [from] 开始取中位数；长度不足时返回 0.0。 */
        private fun medianOf(values: DoubleArray, from: Int): Double {
            if (from >= values.size) return 0.0
            val copy = values.copyOfRange(from, values.size)
            copy.sort()
            val n = copy.size
            return if (n % 2 == 1) copy[n / 2] else (copy[n / 2 - 1] + copy[n / 2]) / 2.0
        }
    }
}
