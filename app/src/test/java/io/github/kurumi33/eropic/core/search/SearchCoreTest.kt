package io.github.kurumi33.eropic.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * core/search 的纯 JVM 单测。
 *
 * 只测纯逻辑（Hamming 距离、相似度公式、keyword 过滤、响应解析、枚举元数据）：
 * 仓库与上传都依赖 Android 运行时，本模块不做 Robolectric，所以它们的正确性靠接口约定与手工验证保证。
 */
class SearchCoreTest {

    // ---------- SimilarityMath ----------

    @Test
    fun hammingDistance_countsDifferentBits() {
        assertEquals(0, SimilarityMath.hammingDistance(0L, 0L))
        assertEquals(64, SimilarityMath.hammingDistance(0L, -1L))
        // 1(001) 与 3(011) 只差第 2 位
        assertEquals(1, SimilarityMath.hammingDistance(1L, 3L))
        assertEquals(2, SimilarityMath.hammingDistance(0L, 3L))
    }

    @Test
    fun similarity_perfectMatchIsHundred() {
        assertEquals(100.0, SimilarityMath.similarity(0, 0), 1e-9)
    }

    @Test
    fun similarity_worstMatchIsZero() {
        assertEquals(0.0, SimilarityMath.similarity(64, 64), 1e-9)
    }

    @Test
    fun similarity_weightsDhashSixToFour() {
        // 0.6*8 + 0.4*16 = 11.2，11.2/64 = 0.175，相似度 82.5%
        assertEquals(82.5, SimilarityMath.similarity(8, 16), 1e-9)
        // 仅 dHash 距离 16、phash 不计时：100*(1-0.6*16/64)=85.0
        assertEquals(85.0, SimilarityMath.similarity(16, 0), 1e-9)
    }

    @Test
    fun similarity_isClampedIntoRange() {
        assertEquals(100.0, SimilarityMath.similarity(-2, -3), 1e-9)
        assertEquals(0.0, SimilarityMath.similarity(200, 200), 1e-9)
    }

    @Test
    fun clampLimit_keepsRangeIn1To1000() {
        assertEquals(1, SimilarityMath.clampLimit(0))
        assertEquals(1, SimilarityMath.clampLimit(-5))
        assertEquals(200, SimilarityMath.clampLimit(200))
        assertEquals(1000, SimilarityMath.clampLimit(5000))
    }

    @Test
    fun clampDistance_keepsRangeIn0To64() {
        assertEquals(0, SimilarityMath.clampDistance(-3))
        assertEquals(16, SimilarityMath.clampDistance(16))
        assertEquals(64, SimilarityMath.clampDistance(99))
    }

    // ---------- keyword 过滤 ----------

    @Test
    fun matchesKeyword_blankKeywordMatchesEverything() {
        assertTrue(SimilarityMath.matchesKeyword("IMG_0001.jpg", "DCIM/Camera/", null))
        assertTrue(SimilarityMath.matchesKeyword("IMG_0001.jpg", "DCIM/Camera/", ""))
        assertTrue(SimilarityMath.matchesKeyword("IMG_0001.jpg", "DCIM/Camera/", "   "))
    }

    @Test
    fun matchesKeyword_matchesNameIgnoringCase() {
        assertTrue(SimilarityMath.matchesKeyword("Cat_Photo.JPG", "DCIM/", "cat"))
        assertTrue(SimilarityMath.matchesKeyword("cat_photo.jpg", "DCIM/", "CAT"))
    }

    @Test
    fun matchesKeyword_matchesRelativePath() {
        assertTrue(SimilarityMath.matchesKeyword("a.jpg", "Pictures/旅行/", "旅行"))
    }

    @Test
    fun matchesKeyword_rejectsMiss() {
        assertFalse(SimilarityMath.matchesKeyword("a.jpg", "DCIM/Camera/", "screenshot"))
    }

    @Test
    fun matchesKeyword_treatsLikeMetacharactersLiterally() {
        // keyword 走 Kotlin contains，不是 SQL LIKE：% 和 _ 是普通字符，不能被当成通配符
        assertTrue(SimilarityMath.matchesKeyword("100%_raw.jpg", "DCIM/", "%"))
        assertTrue(SimilarityMath.matchesKeyword("100%_raw.jpg", "DCIM/", "_"))
        assertFalse(SimilarityMath.matchesKeyword("plain.jpg", "DCIM/", "%"))
        assertFalse(SimilarityMath.matchesKeyword("plain.jpg", "DCIM/", "_"))
    }

    // ---------- 模型默认值 ----------

    @Test
    fun similarityQuery_hasStableDefaults() {
        val query = SimilarityQuery()
        assertNull(query.keyword)
        assertEquals(SimilarityQuery.DEFAULT_LIMIT, query.limit)
        assertEquals(SimilarityQuery.DEFAULT_MAX_DISTANCE, query.maxDistance)
        assertEquals(200, SimilarityQuery.DEFAULT_LIMIT)
        assertEquals(16, SimilarityQuery.DEFAULT_MAX_DISTANCE)
    }

    @Test
    fun reverseImageProvider_hasEightDistinctHttpsHomes() {
        val providers = ReverseImageProvider.entries
        assertEquals(8, providers.size)
        assertEquals(8, providers.map { it.label }.distinct().size)
        providers.forEach { provider ->
            assertTrue(
                "provider " + provider.name + " 的 homeUrl 必须是 https 地址",
                provider.homeUrl.startsWith("https://"),
            )
            assertTrue(provider.label.isNotBlank())
        }
    }

    // ---------- Yandex 响应解析 ----------

    @Test
    fun yandexResultUrl_prefersCbirIdUrlOverThumbnail() {
        val body = """
            {"blocks":[{"params":{"url":"https://avatars.mds.yandex.net/thumb.jpg"}},
                       {"params":{"url":"?rpt=imageview&cbir_id=abc123&cbir_page=similar"}}]}
        """.trimIndent()
        assertEquals(
            "https://yandex.com/images/search?rpt=imageview&cbir_id=abc123&cbir_page=similar",
            ReverseSearchParsers.yandexResultUrl(body),
        )
    }

    @Test
    fun yandexResultUrl_acceptsAbsoluteUrl() {
        val body = """{"url":"https://yandex.com/images/search?rpt=imageview&cbir_id=xyz"}"""
        assertEquals(
            "https://yandex.com/images/search?rpt=imageview&cbir_id=xyz",
            ReverseSearchParsers.yandexResultUrl(body),
        )
    }

    @Test
    fun yandexResultUrl_fallsBackToCbirId() {
        val body = """{"blocks":[{"params":{}}],"cbir_id":"deadbeef"}"""
        assertEquals(
            "https://yandex.com/images/search?rpt=imageview&cbir_id=deadbeef",
            ReverseSearchParsers.yandexResultUrl(body),
        )
    }

    @Test
    fun yandexResultUrl_returnsNullForGarbage() {
        assertNull(ReverseSearchParsers.yandexResultUrl(""))
        assertNull(ReverseSearchParsers.yandexResultUrl("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun yandexResultUrl_unescapesJsonSlashes() {
        // 服务端有时把 / 转义成 \/，拼出来的链接不能带反斜杠
        val body = """{"url":"https:\/\/yandex.com\/images\/search?rpt=imageview&cbir_id=a1"}"""
        assertEquals(
            "https://yandex.com/images/search?rpt=imageview&cbir_id=a1",
            ReverseSearchParsers.yandexResultUrl(body),
        )
    }

    // ---------- Google 响应解析 ----------

    @Test
    fun googleResultUrl_decodesAmpAndStopsAtQuote() {
        val html = "<a href=\"https://lens.google.com/search?ep=abc&amp;re=df\">结果</a>"
        assertEquals(
            "https://lens.google.com/search?ep=abc&re=df",
            ReverseSearchParsers.googleResultUrl(html),
        )
    }

    @Test
    fun googleResultUrl_returnsNullWhenAbsent() {
        assertNull(ReverseSearchParsers.googleResultUrl("<html>no result</html>"))
    }

    // ---------- Location 解析 ----------

    @Test
    fun resolveUrl_handlesAbsoluteAndRelative() {
        assertEquals(
            "https://www.bing.com/images/search?view=detailv2",
            ReverseSearchParsers.resolveUrl(
                "https://www.bing.com/images/searchbyimage/upload?cbir=sbi",
                "https://www.bing.com/images/search?view=detailv2",
            ),
        )
        assertEquals(
            "https://www.bing.com/images/search?view=detailv2",
            ReverseSearchParsers.resolveUrl(
                "https://www.bing.com/images/searchbyimage/upload?cbir=sbi",
                "/images/search?view=detailv2",
            ),
        )
    }

    @Test
    fun resolveUrl_rejectsMissingOrUnsafeLocation() {
        assertNull(ReverseSearchParsers.resolveUrl("https://www.bing.com/upload", null))
        assertNull(ReverseSearchParsers.resolveUrl("https://www.bing.com/upload", "   "))
        assertNull(ReverseSearchParsers.resolveUrl("https://www.bing.com/upload", "javascript:alert(1)"))
        assertNull(ReverseSearchParsers.resolveUrl("https://www.bing.com/upload", "intent://scan/#Intent;end"))
    }
}
