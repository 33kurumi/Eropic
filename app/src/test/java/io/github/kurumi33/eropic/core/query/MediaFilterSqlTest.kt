package io.github.kurumi33.eropic.core.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MediaFilterSql.where] 的纯函数测试。
 *
 * 不测 toQuery：它要构造 SupportSQLiteQuery（Android 侧类型），纯 JVM 单测里不可靠；
 * 拼 SQL 的逻辑已经全部收敛在 where()，这里断言的就是实际下发给 Room 的那段条件。
 */
class MediaFilterSqlTest {

    // SQL 里的 LIKE 转义声明，和实现必须一致：单反斜杠做 ESCAPE 字符。
    private val likeEscape = " ESCAPE '\\'"

    @Test
    fun emptyFilterHasNoConditions() {
        val (sql, args) = MediaFilterSql.where(MediaFilter())

        assertEquals("", sql)
        assertTrue(args.isEmpty())
        assertTrue(MediaFilter().isEmpty)
    }

    @Test
    fun singleSizeConditionIsParameterized() {
        val filter = MediaFilter(sizeMinBytes = 1024L)
        val (sql, args) = MediaFilterSql.where(filter)

        assertEquals("sizeBytes >= ?", sql)
        assertEquals(listOf<Any>(1024L), args)
        assertFalse(filter.isEmpty)
    }

    @Test
    fun resolutionConditionsAreIndependent() {
        val (sql, args) = MediaFilterSql.where(MediaFilter(widthMin = 1080))

        assertEquals("width >= ?", sql)
        assertEquals(listOf<Any>(1080), args)
    }

    @Test
    fun mimeTypesAreSortedToKeepArgumentOrderStable() {
        // 故意乱序插入：Set 的迭代顺序不可依赖，实现里必须自己排序，否则单测与日志都不可复现。
        val types = linkedSetOf("image/png", "image/heic", "image/jpeg")
        val (sql, args) = MediaFilterSql.where(MediaFilter(mimeTypes = types))

        assertEquals("mimeType IN (?, ?, ?)", sql)
        assertEquals(listOf<Any>("image/heic", "image/jpeg", "image/png"), args)
    }

    @Test
    fun multiConditionsAreAndedInDocumentedOrder() {
        val filter = MediaFilter(
            sizeMinBytes = 100L,
            sizeMaxBytes = 200L,
            widthMin = 300,
            heightMin = 400,
            mimeTypes = setOf("image/png", "image/jpeg"),
            dateFromMs = 500L,
            dateToMs = 600L,
            modifiedFromMs = 700L,
            modifiedToMs = 800L,
            favoritesOnly = true,
            minBlurScore = 9.5f,
            maxBlurScore = 19.5f,
        )

        val (sql, args) = MediaFilterSql.where(filter)

        assertEquals(
            "sizeBytes >= ? AND sizeBytes <= ? AND width >= ? AND height >= ? " +
                "AND mimeType IN (?, ?) AND dateTakenMs >= ? AND dateTakenMs <= ? " +
                "AND dateModifiedMs >= ? AND dateModifiedMs <= ? " +
                "AND (isFavorite = 1 OR localFavorite = 1) AND blurScore >= ? AND blurScore <= ?",
            sql,
        )
        // 参数顺序必须与片段顺序一一对应：顺序错位在 SQLite 里不会报错，只会静默筛错数据。
        assertEquals(
            listOf<Any>(
                100L, 200L, 300, 400, "image/jpeg", "image/png",
                500L, 600L, 700L, 800L, 9.5f, 19.5f,
            ),
            args,
        )
        assertEquals(12, args.size)
        assertFalse(sql.contains("WHERE"))
        assertFalse(sql.contains("isPresent"))
    }

    @Test
    fun favoritesConditionCoversBothFavoriteSources() {
        val (sql, args) = MediaFilterSql.where(MediaFilter(favoritesOnly = true))

        assertEquals("(isFavorite = 1 OR localFavorite = 1)", sql)
        assertTrue(args.isEmpty())
    }

    @Test
    fun folderUsesLikePrefixForSubfolders() {
        val (sql, args) = MediaFilterSql.where(MediaFilter(folders = listOf("Pictures/Screenshots")))

        assertEquals("(relativePath LIKE ?" + likeEscape + ")", sql)
        assertEquals(listOf<Any>("Pictures/Screenshots/%"), args)
    }

    @Test
    fun folderTrimsTrailingSlashBeforeBuildingPattern() {
        val (sql, args) = MediaFilterSql.where(MediaFilter(folders = listOf("DCIM/Camera/")))

        assertEquals("(relativePath LIKE ?" + likeEscape + ")", sql)
        // 实体里的 relativePath 一定以 / 结尾，拼模式前要先去掉，否则会拼出 DCIM/Camera//%
        assertEquals(listOf<Any>("DCIM/Camera/%"), args)
    }

    @Test
    fun exactFolderUsesEqualityWithTrailingSlash() {
        val (sql, args) = MediaFilterSql.where(
            MediaFilter(folders = listOf("DCIM/2020"), includeSubfolders = false),
        )

        assertEquals("(relativePath = ?)", sql)
        assertEquals(listOf<Any>("DCIM/2020/"), args)
    }

    @Test
    fun multipleFoldersAreOredTogether() {
        val (sql, args) = MediaFilterSql.where(
            MediaFilter(folders = listOf("DCIM/Camera", "Pictures/Screenshots")),
        )

        assertEquals(
            "(relativePath LIKE ?" + likeEscape + " OR relativePath LIKE ?" + likeEscape + ")",
            sql,
        )
        assertEquals(listOf<Any>("DCIM/Camera/%", "Pictures/Screenshots/%"), args)
    }

    @Test
    fun folderNameWithWildcardsIsEscaped() {
        val (sql, args) = MediaFilterSql.where(MediaFilter(folders = listOf("100%_raw")))

        assertEquals("(relativePath LIKE ?" + likeEscape + ")", sql)
        // % 和 _ 在 LIKE 里是通配符，目录名里出现时必须转义，否则会匹配到别的目录。
        assertEquals(listOf<Any>("100\\%\\_raw/%"), args)
    }

    @Test
    fun folderNameWithBackslashIsEscaped() {
        val (_, args) = MediaFilterSql.where(MediaFilter(folders = listOf("a\\b")))

        // 反斜杠本身是转义字符，必须先转义它，否则后面的 \% 之类会被 SQLite 误解。
        assertEquals(listOf<Any>("a\\\\b/%"), args)
    }

    @Test
    fun blankFolderEntriesAreIgnored() {
        val (sql, args) = MediaFilterSql.where(MediaFilter(folders = listOf("/", "")))

        assertEquals("", sql)
        assertTrue(args.isEmpty())
    }
}
