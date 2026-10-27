package io.github.kurumi33.eropic.core.analysis.detectors

import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.kurumi33.eropic.core.database.entity.MediaEntity

/**
 * 截图检测（规划 B-06）：纯启发式，两条独立信号命中任一即算。
 *
 * 为什么不用"无 EXIF"做判据：Android 上通过 ContentResolver 读 EXIF 需要额外打开文件流，
 * 对全库逐张做代价太高；而且微信/QQ 转发的截图在保存时 EXIF 已经被清掉，
 * 用"无 EXIF"会把大量聊天图片一起标成截图。文件名 + 分辨率足够区分绝大多数场景。
 *
 * 已知误报（会在 UI 里提示用户复核）：
 * - 手机壁纸、设计稿、微信原图：分辨率恰好等于常见屏幕尺寸，且是 PNG；
 * - 名字里带 "screenshot" 但其实是表情包/梗图的；
 * - 屏幕录制转出来的封面帧（视频，mimeType 以 video/ 开头）会因文件名命中。
 */
class ScreenshotDetector(private val dao: DetectionDao) {

    /**
     * 先 SQL 粗筛再 Kotlin 精筛：LIKE 全表扫描只做一次，精确判定在内存里跑，
     * 比在 SQL 里拼分辨率元组更好维护（新增机型分辨率不用改 SQL）。
     */
    suspend fun find(limit: Int = DEFAULT_LIMIT): List<MediaEntity> {
        val cap = limit.coerceIn(1, MAX_LIMIT)
        val (sql, args) = candidateQuery(cap)
        val candidates = dao.screenshotCandidates(SimpleSQLiteQuery(sql, args.toTypedArray()))
        return candidates
            .asSequence()
            .filter { looksLikeScreenshot(it.path, it.displayName, it.mimeType, it.width, it.height) }
            .take(cap)
            .toList()
    }

    companion object {

        /** 候选上限：粗筛包含所有 PNG，PNG 通常占总体量的一小部分，2000 条足够覆盖真实截图。 */
        const val DEFAULT_LIMIT: Int = 2000
        const val MAX_LIMIT: Int = 20000

        /** LIKE 转义字符统一用反斜杠，与 MediaQuery / MediaFilterSql 保持一致。 */
        private const val LIKE_ESCAPE = " ESCAPE '\\'"

        /**
         * 命名关键词，全部小写：SQL 侧走 lower(path)，Kotlin 侧走 lowercase()，两边必须同一套。
         * 中文厂商很少用英文名，所以中文关键词不能省。
         */
        private val NAME_KEYWORDS: List<String> = listOf(
            "screenshot",
            "screen_shot",
            "screen shot",
            "screen-shot",
            "screencapture",
            "screen_capture",
            "screen capture",
            "screenrecord",
            "screen_record",
            "scrnshot",
            "scr_",
            "截屏",
            "截图",
            "屏幕截图",
        )

        /**
         * 常见屏幕分辨率（竖屏取值）。
         *
         * 太冷门的尺寸不加：每加一格都会让"恰好同尺寸的壁纸"误报概率上升。
         */
        private val SCREEN_RESOLUTIONS: Set<Pair<Int, Int>> = setOf(
            1080 to 1920,
            1080 to 2160,
            1080 to 2340,
            1080 to 2400,
            1170 to 2532,
            1260 to 2800,
            1284 to 2778,
            1440 to 2960,
            1440 to 3120,
            1440 to 3200,
            1600 to 2560,
            720 to 1280,
        )

        /**
         * 纯判定函数：不碰数据库、不碰 Android 类，单测直接覆盖正反例。
         *
         * 横竖屏都算，因为同一张截图在相册里可能被旋转保存；因此横屏风景照
         * 如果有 1920x1080 这类尺寸也会被命中，属于可接受的误报。
         */
        fun looksLikeScreenshot(
            path: String,
            displayName: String,
            mimeType: String,
            width: Int,
            height: Int,
        ): Boolean {
            // path 里包含 displayName，但显示名可能被截断或与文件名不同，两边都查更稳
            val haystack = (path + "\n" + displayName).lowercase()
            if (NAME_KEYWORDS.any { haystack.contains(it) }) return true
            if (!mimeType.equals("image/png", ignoreCase = true)) return false
            if (width <= 0 || height <= 0) return false
            return isKnownScreenResolution(width, height)
        }

        private fun isKnownScreenResolution(width: Int, height: Int): Boolean =
            (width to height) in SCREEN_RESOLUTIONS || (height to width) in SCREEN_RESOLUTIONS

        /**
         * 生成粗筛查询：[limit] 条以内的候选。
         *
         * 条件是 "PNG 或 文件名命中关键词"，是 [looksLikeScreenshot] 的超集——
         * 粗筛宁可多捞也不能漏，否则精筛再准也没用。
         */
        fun candidateQuery(limit: Int): Pair<String, List<Any>> {
            val keywordArgs = ArrayList<Any>(NAME_KEYWORDS.size)
            val clauses = StringBuilder()
            for (keyword in NAME_KEYWORDS) {
                if (clauses.isNotEmpty()) clauses.append(" OR ")
                clauses.append("lower(path) LIKE ?").append(LIKE_ESCAPE)
                keywordArgs += likePattern(keyword)
            }
            val keywordExpr = clauses.toString()
            // 文件名/路径命中关键词的排前面：截图基本都在 Screenshots 目录，
            // 以前纯按拍摄时间倒序取 2000 条，库里 PNG 一多，更早的截图就永远进不了候选。
            val args = ArrayList<Any>(keywordArgs.size * 2 + 1)
            args.addAll(keywordArgs)
            args.addAll(keywordArgs)
            args.add(limit)
            val sql = "SELECT * FROM media_item WHERE isPresent = 1 " +
                "AND (lower(mimeType) = 'image/png' OR (" + keywordExpr + ")) " +
                "ORDER BY CASE WHEN (" + keywordExpr + ") THEN 0 ELSE 1 END, " +
                "dateTakenMs DESC, id DESC LIMIT ?"
            return sql to args
        }

        /**
         * 关键词转 LIKE 模式：转义 % 和 _，否则 "scr_" 里的下划线会变成通配符，
         * 粗筛结果和精确判定就对不上了。
         */
        private fun likePattern(keyword: String): String {
            val escaped = keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
                .lowercase()
            return "%" + escaped + "%"
        }
    }
}
