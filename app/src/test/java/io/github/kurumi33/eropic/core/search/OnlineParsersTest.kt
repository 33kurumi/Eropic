package io.github.kurumi33.eropic.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OnlineParsers 的纯 JVM 单测：用固定样例覆盖「正常命中 / 无结果 / 鉴权失败 / 页面结构不符」四类情况。
 * 这里不启动 Android、不发网络请求，解释器本身的健壮性必须靠这层兜住。
 */
class OnlineParsersTest {

    private val sauceNaoJson = """
        {
          "header": {"user_id": "1", "status": 0, "message": null},
          "results": [
            {
              "header": {"similarity": "94.5", "thumbnail": "https://img.example/pixiv.jpg", "index_name": "Pixiv"},
              "data": {"title": "夜のダンス", "ext_urls": ["https://www.pixiv.net/artworks/111"]}
            },
            {
              "header": {"similarity": "88.0", "thumbnail": "https://img.example/twitter.jpg", "index_name": "Twitter"},
              "data": {"source": "twitter @artist", "ext_urls": ["https://twitter.com/artist/status/222"]}
            },
            {
              "header": {"similarity": "75.2", "index_name": "Danbooru"},
              "data": {"material": "original", "ext_urls": []}
            }
          ]
        }
    """.trimIndent()

    @Test
    fun sauceNao_parsesThreeHits() {
        val response = OnlineParsers.sauceNao(sauceNaoJson)

        assertEquals("header.status 应为 0", 0, response.headerStatus)
        assertEquals("应解析出 3 条命中", 3, response.results.size)

        val first = response.results[0]
        assertEquals("夜のダンス", first.title)
        assertEquals(94.5, first.similarity ?: -1.0, 1e-9)
        assertEquals("https://www.pixiv.net/artworks/111", first.extUrl)
        assertEquals("https://img.example/pixiv.jpg", first.thumbnailUrl)

        val second = response.results[1]
        assertEquals("twitter @artist", second.title)
        assertEquals(88.0, second.similarity ?: -1.0, 1e-9)
        assertEquals("https://twitter.com/artist/status/222", second.extUrl)

        val third = response.results[2]
        assertEquals("original", third.title)
        assertEquals(75.2, third.similarity ?: -1.0, 1e-9)
        assertNull("ext_urls 为空时 extUrl 应为 null", third.extUrl)
        assertNull("没有 thumbnail 时 thumbnailUrl 应为 null", third.thumbnailUrl)
    }

    @Test
    fun sauceNao_parsesCharacterMaterialCreator() {
        val json = """
            {"header":{"status":0},"results":[
              {"header":{"similarity":"96.1"},"data":{"characters":"hatsune miku, kagamine rin","material":"vocaloid","creator":"artist_a, artist_b"}},
              {"header":{"similarity":"70.0"},"data":{"characters":"","material":"  ","creator":"","title":"t"}}
            ]}
        """.trimIndent()

        val response = OnlineParsers.sauceNao(json)
        val first = response.results[0]
        assertEquals("hatsune miku, kagamine rin", first.characters)
        assertEquals("vocaloid", first.material)
        assertEquals("artist_a, artist_b", first.creator)
        assertEquals("SauceNAO", first.source)

        val second = response.results[1]
        assertNull("空字符串应归一为 null", second.characters)
        assertNull("纯空白应归一为 null", second.material)
        assertNull("空字符串应归一为 null", second.creator)
    }

    @Test
    fun sauceNao_acceptsNumericSimilarity() {
        val json = """{"header":{"status":0},"results":[{"header":{"similarity":91.25},"data":{"title":"x"}}]}"""
        val response = OnlineParsers.sauceNao(json)
        assertEquals(91.25, response.results[0].similarity ?: -1.0, 1e-9)
    }

    @Test
    fun sauceNao_headerStatusOneMeansNoResults() {
        val response = OnlineParsers.sauceNao("""{"header":{"status":1},"results":[]}""")
        assertEquals("无结果时 header.status 原样透传", 1, response.headerStatus)
        assertTrue("无结果时不应有命中", response.results.isEmpty())
        assertNull(response.headerMessage)
    }

    @Test
    fun sauceNao_headerStatusThreeMeansInvalidKey() {
        val response = OnlineParsers.sauceNao("""{"header":{"status":3,"message":"Invalid API key"}}""")
        assertEquals(3, response.headerStatus)
        assertEquals("Invalid API key", response.headerMessage)
        assertTrue(response.results.isEmpty())
    }

    @Test
    fun sauceNao_invalidJsonReturnsSafeDefault() {
        val broken = OnlineParsers.sauceNao("{ not json at all")
        assertEquals(0, broken.headerStatus)
        assertTrue(broken.results.isEmpty())
        assertNull(broken.headerMessage)

        val nullResponse = OnlineParsers.sauceNao(null)
        assertEquals(0, nullResponse.headerStatus)
        assertTrue(nullResponse.results.isEmpty())

        val blank = OnlineParsers.sauceNao("   ")
        assertEquals(0, blank.headerStatus)
        assertTrue(blank.results.isEmpty())
    }

    private val ascii2dHtml = """
        <div class="item-box">
          <a href="/search/file?uri=abc&amp;type=color"><img src="/thumb/abc.jpg" alt=""></a>
          <div class="detail-box">
            <h6><a href="/search/file?uri=abc&amp;type=color">初音ミク</a></h6>
            <a href="https://www.pixiv.net/artworks/111">pixiv</a>
            <a href="https://twitter.com/artist/status/222">twitter</a>
          </div>
        </div>
        <div class="item-box">
          <div class="detail-box">
            <h6><a href="/search/file?uri=def">鏡音リン</a></h6>
            <a href="https://www.pixiv.net/artworks/333">pixiv</a>
          </div>
        </div>
    """.trimIndent()

    @Test
    fun ascii2dHtml_parsesTwoItems() {
        val hits = OnlineParsers.ascii2dHtml(ascii2dHtml)

        assertEquals("应解析出 2 个 item-box", 2, hits.size)

        val first = hits[0]
        assertEquals("初音ミク", first.title)
        assertEquals("https://www.pixiv.net/artworks/111", first.extUrl)
        assertEquals("相对链接应拼上 base", "https://ascii2d.net/search/file?uri=abc&type=color", first.pageUrl)
        assertEquals("https://ascii2d.net/thumb/abc.jpg", first.thumbnailUrl)

        val second = hits[1]
        assertEquals("鏡音リン", second.title)
        assertEquals("https://www.pixiv.net/artworks/333", second.extUrl)
        assertEquals("https://ascii2d.net/search/file?uri=def", second.pageUrl)
        assertNull("第二个条目没有图片", second.thumbnailUrl)
    }

    @Test
    fun ascii2dHtml_supportsCustomBaseUrl() {
        val hits = OnlineParsers.ascii2dHtml(ascii2dHtml, "https://mirror.example/ascii2d")
        assertEquals("https://mirror.example/ascii2d/search/file?uri=abc&type=color", hits[0].pageUrl)
    }

    @Test
    fun ascii2dHtml_handlesEmptyAndGarbage() {
        assertTrue(OnlineParsers.ascii2dHtml(null).isEmpty())
        assertTrue(OnlineParsers.ascii2dHtml("").isEmpty())
        assertTrue(OnlineParsers.ascii2dHtml("<html><body>no results</body></html>").isEmpty())
    }

    private val iqdbHtml = """
        <table>
          <tr><th>score</th><th>source</th><th>service</th></tr>
          <tr>
            <td class="match">93%</td>
            <td class="url"><a href="https://danbooru.donmai.us/posts/1">danbooru</a></td>
            <td class="service">Danbooru</td>
            <td><img src="/img/1.jpg"></td>
          </tr>
          <tr>
            <td class="match">87.5%</td>
            <td class="url"><a href="https://yande.re/post/show/2">yande.re</a></td>
            <td class="service">Yande.re</td>
          </tr>
        </table>
    """.trimIndent()

    @Test
    fun iqdbHtml_parsesTwoRows() {
        val hits = OnlineParsers.iqdbHtml(iqdbHtml)

        assertEquals("表头行应被跳过，只留 2 条命中", 2, hits.size)

        assertEquals("Danbooru", hits[0].title)
        assertEquals(93.0, hits[0].similarity ?: -1.0, 1e-9)
        assertEquals("https://danbooru.donmai.us/posts/1", hits[0].extUrl)
        assertEquals("https://iqdb.org/img/1.jpg", hits[0].thumbnailUrl)

        assertEquals("Yande.re", hits[1].title)
        assertEquals(87.5, hits[1].similarity ?: -1.0, 1e-9)
        assertEquals("https://yande.re/post/show/2", hits[1].extUrl)
        assertNull(hits[1].thumbnailUrl)
    }

    @Test
    fun iqdbHtml_handlesEmptyAndGarbage() {
        assertTrue(OnlineParsers.iqdbHtml(null).isEmpty())
        assertTrue(OnlineParsers.iqdbHtml("<html></html>").isEmpty())
        assertTrue(OnlineParsers.iqdbHtml("<table><tr><td>nothing</td></tr></table>").isEmpty())
    }

    @Test
    fun googleLensResultUrl_prefersUdmLinkOverCanonical() {
        val html = """
            <html><head>
              <link rel="canonical" href="https://lens.google.com/uploadbyurl?url=x">
            </head><body>
              <a href="/search?udm=44&amp;q=cat">results</a>
            </body></html>
        """.trimIndent()

        assertEquals(
            "https://www.google.com/search?udm=44&q=cat",
            OnlineParsers.googleLensResultUrl(html),
        )
    }

    @Test
    fun googleLensResultUrl_supportsUdm37AndUdm48() {
        val html37 = """<a href="https://lens.google.com/search?udm=37&amp;p=abc">x</a>"""
        assertEquals(
            "https://lens.google.com/search?udm=37&p=abc",
            OnlineParsers.googleLensResultUrl(html37),
        )

        val html48 = """<a href="https://lens.google.com/search?udm=48">x</a>"""
        assertEquals(
            "https://lens.google.com/search?udm=48",
            OnlineParsers.googleLensResultUrl(html48),
        )
    }

    @Test
    fun googleLensResultUrl_fallsBackToCanonical() {
        val html = """<html><head><link rel="canonical" href="https://lens.google.com/uploadbyurl?url=y"></head></html>"""
        assertEquals(
            "https://lens.google.com/uploadbyurl?url=y",
            OnlineParsers.googleLensResultUrl(html),
        )
    }

    @Test
    fun googleLensResultUrl_fallsBackToBaseUrl() {
        assertEquals(
            "https://lens.google.com/upload?foo=1",
            OnlineParsers.googleLensResultUrl("<html><body>nothing</body></html>", "https://lens.google.com/upload?foo=1"),
        )
        assertEquals(
            "https://lens.google.com/upload?foo=1",
            OnlineParsers.googleLensResultUrl(null, "https://lens.google.com/upload?foo=1"),
        )
        assertNull(OnlineParsers.googleLensResultUrl(null, null))
    }

    @Test
    fun parsers_neverThrowOnTruncatedInput() {
        // 真实网络场景里 response body 被截断很常见，这里确认解析器只是返回空列表/默认值
        val truncatedJson = """{"header":{"status":0},"results":[{"header":{"simil"""
        assertNotNull(OnlineParsers.sauceNao(truncatedJson))

        val truncatedHtml = """<div class="item-box"><div class="detail-box"><h6><a href="/x"""
        assertNotNull(OnlineParsers.ascii2dHtml(truncatedHtml))
        assertNotNull(OnlineParsers.iqdbHtml(truncatedHtml))
        // 截断页里既没有 udm 链接也没有 canonical，只能回退到 null，这是预期行为
        assertNull(OnlineParsers.googleLensResultUrl(truncatedHtml))
    }
}
