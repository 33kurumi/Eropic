package io.github.kurumi33.eropic.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CharacterRecognizer 的纯 JVM 单测：覆盖「一条命中 / 多条命中投票 / 中英文分隔符 / 噪声过滤 / 空输入」。
 *
 * 这里断言的是**相对排序与来源标注**，不断言绝对分值：权重是产品口味（见 CharacterRecognition.kt 注释），
 * 写死绝对分会让以后调权重时单测到处红，而排序稳定才是用户能感知的行为。
 */
class CharacterRecognitionTest {

    private fun hit(
        title: String? = null,
        similarity: Double? = null,
        characters: String? = null,
        material: String? = null,
        creator: String? = null,
        source: String? = null,
    ) = ParsedHit(
        title = title,
        similarity = similarity,
        characters = characters,
        material = material,
        creator = creator,
        source = source,
    )

    @Test
    fun candidates_emptyInput_returnsEmpty() {
        assertTrue(CharacterRecognizer.candidates(emptyList()).isEmpty())
        assertTrue("max <= 0 时不应返回候选", CharacterRecognizer.candidates(listOf(hit(characters = "miku")), 0).isEmpty())
    }

    @Test
    fun candidates_singleHitWithCharacters() {
        val result = CharacterRecognizer.candidates(
            listOf(hit(characters = " hatsune miku ", similarity = 92.0, source = "SauceNAO")),
        )

        assertEquals(1, result.size)
        assertEquals("hatsune miku", result[0].name)
        assertEquals("SauceNAO", result[0].source)
        assertEquals("唯一候选归一化后应为 1.0", 1.0, result[0].score, 1e-9)
    }

    @Test
    fun candidates_moreHitsWin() {
        val result = CharacterRecognizer.candidates(
            listOf(
                hit(characters = "hatsune miku", similarity = 90.0, source = "SauceNAO"),
                hit(characters = "hatsune miku", similarity = 80.0, source = "SauceNAO"),
                hit(characters = "kagamine rin", similarity = 95.0, source = "SauceNAO"),
            ),
        )

        assertEquals("两条命中的角色应排第一", "hatsune miku", result[0].name)
        assertEquals("kagamine rin", result[1].name)
        assertTrue("第二名分数应低于第一名", result[1].score < result[0].score)
    }

    @Test
    fun candidates_splitsChineseAndIdeographicCommas() {
        val result = CharacterRecognizer.candidates(
            listOf(hit(characters = "初音ミク，鏡音リン、巡音ルカ", similarity = 88.0, source = "SauceNAO")),
        )

        val names = result.map { it.name }
        assertTrue("中文逗号", names.contains("初音ミク"))
        assertTrue("顿号", names.contains("鏡音リン"))
        assertTrue("顿号", names.contains("巡音ルカ"))
    }

    @Test
    fun candidates_filtersNoiseWords() {
        val result = CharacterRecognizer.candidates(
            listOf(
                hit(
                    characters = "original, comic, anime, illustration, hatsune miku",
                    similarity = 90.0,
                    source = "SauceNAO",
                ),
            ),
        )

        assertEquals("噪声词之外只应剩角色名", listOf("hatsune miku"), result.map { it.name })
    }

    @Test
    fun candidates_dropsNumericAndOverlongTokens() {
        val longName = "a".repeat(41)
        val result = CharacterRecognizer.candidates(
            listOf(hit(characters = "2024, $longName, miku", similarity = 70.0, source = "SauceNAO")),
        )

        assertEquals(listOf("miku"), result.map { it.name })
    }

    @Test
    fun candidates_mergesUnderscoreAndCaseVariants() {
        val result = CharacterRecognizer.candidates(
            listOf(
                hit(characters = "hatsune_miku", similarity = 70.0, source = "SauceNAO"),
                hit(characters = "Hatsune Miku", similarity = 70.0, source = "SauceNAO"),
            ),
        )

        assertEquals("下划线/大小写不同的同名角色应合并", 1, result.size)
        assertEquals("hatsune_miku", result[0].name)
    }

    @Test
    fun candidates_usesTitleForAscii2dWithItsOwnSource() {
        val result = CharacterRecognizer.candidates(
            listOf(hit(title = "初音ミク", similarity = 80.0, source = "ascii2d")),
        )

        assertEquals("初音ミク", result[0].name)
        assertEquals("ascii2d", result[0].source)
    }

    @Test
    fun candidates_titleOnlyFallsBackToTitleSource() {
        val result = CharacterRecognizer.candidates(
            listOf(hit(title = "夜のダンス", similarity = 80.0, source = "SauceNAO")),
        )

        assertEquals("夜のダンス", result[0].name)
        assertEquals("标题", result[0].source)
    }

    @Test
    fun candidates_respectsMax() {
        val hits = (1..5).map { hit(characters = "char$it", similarity = 90.0, source = "SauceNAO") }
        assertEquals(2, CharacterRecognizer.candidates(hits, max = 2).size)
    }

    @Test
    fun candidates_emptyHitsOrNoNames_returnsEmpty() {
        assertTrue(CharacterRecognizer.candidates(listOf(ParsedHit(similarity = 90.0))).isEmpty())
        assertTrue(CharacterRecognizer.candidates(listOf(hit(characters = "   ", similarity = 90.0))).isEmpty())
    }

    @Test
    fun splitNames_handlesNullAndBlank() {
        assertTrue(CharacterRecognizer.splitNames(null).isEmpty())
        assertTrue(CharacterRecognizer.splitNames("   ").isEmpty())
        assertEquals(listOf("miku"), CharacterRecognizer.splitNames("miku"))
    }

    @Test
    fun topMaterial_returnsMostFrequent() {
        val hits = listOf(
            hit(material = "vocaloid", source = "SauceNAO"),
            hit(material = "vocaloid", source = "SauceNAO"),
            hit(material = "touhou", source = "SauceNAO"),
        )
        assertEquals("vocaloid", CharacterRecognizer.topMaterial(hits))
        assertNull(CharacterRecognizer.topMaterial(emptyList()))
    }
}
