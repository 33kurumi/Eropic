package io.github.kurumi33.eropic.core.analysis.detectors

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.kurumi33.eropic.core.database.entity.MediaEntity

/**
 * 清理检测器的只读查询入口。
 *
 * 刻意不提供任何 update / delete 方法：M2 阶段的检测结果只是"建议"，
 * 真正的删除/移动要走 M4 的回收站与操作日志链路，检测层越权写库会绕开撤销能力。
 * 所有查询都带 isPresent = 1，已消失（被用户在系统相册删掉）的条目不应出现在清理建议里。
 */
@Dao
interface DetectionDao {

    /** Top N 大文件；排序里补 id 是为了同体积时结果稳定，避免两次查询顺序抖动。 */
    @Query(
        "SELECT * FROM media_item WHERE isPresent = 1 AND sizeBytes >= :minSizeBytes " +
            "ORDER BY sizeBytes DESC, id ASC LIMIT :limit",
    )
    suspend fun largeFiles(minSizeBytes: Long, limit: Int): List<MediaEntity>

    /**
     * 大文件的命中总数与总字节。
     *
     * 不能用 Top N 列表自己累加：列表被 LIMIT 截断过，UI 显示的"可释放"会偏小。
     */
    @Query(
        "SELECT COUNT(*) AS hits, COALESCE(SUM(sizeBytes), 0) AS bytes FROM media_item " +
            "WHERE isPresent = 1 AND sizeBytes >= :minSizeBytes",
    )
    suspend fun largeFileSummary(minSizeBytes: Long): LargeFileSummaryRow

    /**
     * 久远照片：拍摄时间早于 [beforeMs]，且系统收藏与 App 内收藏都为假。
     *
     * dateTakenMs 在扫描层已经做过回退（DATE_TAKEN 无效时取 DATE_MODIFIED，见 MediaStoreDataSource），
     * 理论上不会为 0；这里仍然保留 > 0 的判断，防御历史数据与迁移残留的空值行，
     * 否则它们会被当成"1970 年的老照片"排在最前面。
     */
    @Query(
        "SELECT * FROM media_item WHERE isPresent = 1 AND dateTakenMs > 0 " +
            "AND dateTakenMs < :beforeMs AND isFavorite = 0 AND localFavorite = 0 " +
            "ORDER BY dateTakenMs ASC, id ASC LIMIT :limit",
    )
    suspend fun oldPhotos(beforeMs: Long, limit: Int): List<MediaEntity>

    /**
     * 截图候选（粗筛）：SQL 只保证不漏，精确判定交给 [ScreenshotDetector.looksLikeScreenshot]。
     */
    @RawQuery(observedEntities = [MediaEntity::class])
    suspend fun screenshotCandidates(query: SupportSQLiteQuery): List<MediaEntity>

    /**
     * 模糊图：只取已计算过签名的行（signatureVersion > 0），未算的不能当成清晰。
     *
     * signatureVersion = -1 表示解码失败、blurScore 恒为 null，条件里的 IS NOT NULL 顺带排除它。
     */
    @Query(
        "SELECT * FROM media_item WHERE isPresent = 1 AND signatureVersion > 0 " +
            "AND blurScore IS NOT NULL AND blurScore < :threshold " +
            "ORDER BY blurScore ASC, id ASC LIMIT :limit",
    )
    suspend fun blurryImages(threshold: Double, limit: Int): List<MediaEntity>

    /**
     * 连拍候选：取某个时间点之后的图片，按目录 + 拍摄时间排好序，交给 [BurstDetector] 在内存里判间隔。
     *
     * 不在 SQL 里直接算间隔的原因：SQLite 窗口函数要 API 30+ 才稳定可用，
     * 而这里用 LIMIT 限制上限（默认两万行）后，一次线性扫描的开销远小于维护窗口函数查询。
     */
    @Query(
        "SELECT * FROM media_item WHERE isPresent = 1 AND dateTakenMs > :sinceMs " +
            "ORDER BY bucketName ASC, dateTakenMs ASC, id ASC LIMIT :limit",
    )
    suspend fun burstCandidates(sinceMs: Long, limit: Int): List<MediaEntity>

    /**
     * 大图（v0.3）：按分辨率从大到小，像素数 >= [minPixels]。
     *
     * 与 [largeFiles] 的区别：那个按文件体积（MB），这个按像素（宽×高）。
     * 一张 12MP 的 PNG 截图可能只有 1MB，而一张 8MP 的 JPG 有 6MB —— 两种"大"不是一回事，
     * 用户要的是"找大图"（分辨率高、放大看细节、可能值得压缩/转存），所以单列一类。
     */
    @Query(
        "SELECT * FROM media_item WHERE isPresent = 1 AND width > 0 AND height > 0 " +
            "AND (width * height) >= :minPixels " +
            "ORDER BY (width * height) DESC, sizeBytes DESC, id ASC LIMIT :limit",
    )
    suspend fun largeImages(minPixels: Int, limit: Int): List<MediaEntity>

    /** 大图的命中总数与总字节（不受 [largeImages] 的 LIMIT 影响）。 */
    @Query(
        "SELECT COUNT(*) AS hits, COALESCE(SUM(sizeBytes), 0) AS bytes FROM media_item " +
            "WHERE isPresent = 1 AND width > 0 AND height > 0 AND (width * height) >= :minPixels",
    )
    suspend fun largeImageSummary(minPixels: Int): LargeFileSummaryRow

    /** 小图：按面积从小到大；面积用 width*height 现算，避免再加一列派生数据。 */
    @Query(
        "SELECT * FROM media_item WHERE isPresent = 1 AND width > 0 AND height > 0 " +
            "AND (width * height) < :maxPixels ORDER BY (width * height) ASC, id ASC LIMIT :limit",
    )
    suspend fun smallImages(maxPixels: Int, limit: Int): List<MediaEntity>
}

/** [DetectionDao.largeFileSummary] 的聚合行；字段名必须和 SQL 里的别名一致，Room 靠列名映射。 */
data class LargeFileSummaryRow(
    val hits: Long,
    val bytes: Long,
)