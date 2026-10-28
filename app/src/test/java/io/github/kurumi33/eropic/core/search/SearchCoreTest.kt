package io.github.kurumi33.eropic.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * core/search 的纯 JVM 单测。
 *
 * 只测纯逻辑（Hamming 距离、相似度公式、keyword 过滤、响应解析、枚举与提示文案）：
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
    fun reverseImageProvider_keepsFourDistinctHttpsHomes() {
        val providers = ReverseImageProvider.entries
        assertEquals(4, providers.size)
        // 顺序即 UI 上按钮的排列顺序：Google / Yandex / SauceNAO / ascii2d
        assertEquals(
            listOf("GOOGLE_LENS", "YANDEX", "SAUCENAO", "ASCII2D"),
            providers.map { it.name },
        )
        assertEquals(4, providers.map { it.label }.distinct().size)
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

    // ---------- SauceNAO 响应解析 ----------

    /** 贴近真实输出的样例：两条命中，第二条的 ext_urls 是 null（真实响应里常见）。 */
    private val sauceNaoJson = """
        {
          "header": {
            "user_id": "42",
            "account_type": "0",
            "short_limit": "4",
            "long_limit": "100",
            "long_remaining": 96,
            "short_remaining": 4,
            "status": 0,
            "results_requested": "6",
            "search_depth": "128",
            "minimum_similarity": "63.51",
            "results_returned": 2
          },
          "results": [
            {
              "header": {
                "similarity": "94.2",
                "thumbnail": "https:\/\/img1.saucenao.com\/thumb.jpg",
                "index_id": 5,
                "index_name": "Pixiv"
              },
              "data": {
                "ext_urls": ["https:\/\/www.pixiv.net\/artworks\/123"],
                "title": "夜 [限定]",
                "pixiv_id": 123
              }
            },
            {
              "header": {
                "similarity": "88.0",
                "index_id": 25,
                "index_name": "Twitter"
              },
              "data": {
                "ext_urls": null,
                "title": "no url here"
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun sauceNaoStatus_readsHeaderStatus() {
        assertEquals(0, ReverseSearchParsers.sauceNaoStatus(sauceNaoJson))
        assertEquals(-1, ReverseSearchParsers.sauceNaoStatus("""{"header":{"status":-1}}"""))
        assertEquals(-2, ReverseSearchParsers.sauceNaoStatus("""{"header":{"status": -2 }}"""))
        // 网关错误页没有 JSON，不能让调用方误以为 status=0
        assertNull(ReverseSearchParsers.sauceNaoStatus("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun sauceNaoResultsReturned_readsCount() {
        assertEquals(2, ReverseSearchParsers.sauceNaoResultsReturned(sauceNaoJson))
        assertNull(ReverseSearchParsers.sauceNaoResultsReturned("{}"))
    }

    @Test
    fun sauceNaoResults_parsesHitsAndExtUrlsIndependently() {
        val hits = ReverseSearchParsers.sauceNaoResults(sauceNaoJson)
        assertEquals(2, hits.size)
        assertEquals(94.2, hits[0].similarity ?: -1.0, 1e-9)
        assertEquals("Pixiv", hits[0].indexName)
        assertEquals("夜 [限定]", hits[0].title)
        assertEquals("https://www.pixiv.net/artworks/123", hits[0].extUrl)
        assertEquals(88.0, hits[1].similarity ?: -1.0, 1e-9)
        assertEquals("Twitter", hits[1].indexName)
        // 第二条 ext_urls 是 null：必须是 null，不能把上一条的链接串过来
        assertNull(hits[1].extUrl)
    }

    @Test
    fun sauceNaoResults_handlesEmptyAndGarbage() {
        assertTrue(ReverseSearchParsers.sauceNaoResults("""{"header":{"status":0},"results":[]}""").isEmpty())
        assertTrue(ReverseSearchParsers.sauceNaoResults("<html>nope</html>").isEmpty())
        assertTrue(ReverseSearchParsers.sauceNaoResults("""{"header":{"status":0}}""").isEmpty())
    }

    @Test
    fun sauceNaoResults_skipsNonHttpExtUrls() {
        val body = """
            {"results":[{"header":{"similarity":"70.0"},"data":{"ext_urls":["ftp:\/\/example.com\/a","https:\/\/example.com\/b"]}}]}
        """.trimIndent()
        assertEquals("https://example.com/b", ReverseSearchParsers.sauceNaoResults(body).first().extUrl)
    }

    @Test
    fun sauceNaoResults_unescapesQuotesAndSlashes() {
        val body = """
            {"results":[{"header":{"similarity":"60"},"data":{"ext_urls":["https:\/\/x.test\/p"],"title":"say \"hi\""}}]}
        """.trimIndent()
        val hit = ReverseSearchParsers.sauceNaoResults(body).first()
        assertEquals("https://x.test/p", hit.extUrl)
        assertEquals("say \"hi\"", hit.title)
    }

    // ---------- 提示文案 ----------

    @Test
    fun sauceNaoMissingKeyMessage_pointsToSettings() {
        assertTrue(SAUCE_NAO_NO_KEY_MESSAGE.contains("设置"))
        assertTrue(SAUCE_NAO_NO_KEY_MESSAGE.contains("联网识图"))
        assertTrue(SAUCE_NAO_NO_KEY_MESSAGE.contains("SauceNAO API Key"))
    }

    @Test
    fun sauceNaoStatusMessage_mapsDocumentedCodes() {
        assertTrue(sauceNaoStatusMessage(-1).contains("API Key"))
        assertTrue(sauceNaoStatusMessage(-2).contains("频繁"))
        assertTrue(sauceNaoStatusMessage(-3).contains("出错"))
        // 未知负数也要带出原始码，方便用户反馈
        assertTrue(sauceNaoStatusMessage(-9).contains("-9"))
    }

    @Test
    fun sauceNaoHttpMessage_mapsCommonCodes() {
        assertTrue(sauceNaoHttpMessage(403).contains("API Key"))
        assertTrue(sauceNaoHttpMessage(413).contains("图片"))
        assertTrue(sauceNaoHttpMessage(429).contains("频繁"))
        assertTrue(sauceNaoHttpMessage(503).contains("503"))
    }
}
