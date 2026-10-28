package io.github.kurumi33.eropic.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.5：本地识图关键词语法（空格=AND、竖线=OR、减号=排除）。 */
class KeywordSyntaxTest {

    private fun parse(raw: String?) = KeywordSyntax.parse(raw)

    @Test
    fun blankQueryMatchesEverything() {
        assertTrue(parse(null).isEmpty)
        assertTrue(parse("   ").isEmpty)
        assertTrue(KeywordSyntax.matches("cat.jpg", "DCIM/Camera/", "  "))
    }

    @Test
    fun spaceSeparatedWordsMustAllMatch() {
        val query = parse("cat 2024")
        assertTrue(KeywordSyntax.matches("cat-2024.jpg", "DCIM/", query))
        // 目录里出现也算命中
        assertTrue(KeywordSyntax.matches("photo.jpg", "DCIM/cat 2024/", query))
        assertFalse(KeywordSyntax.matches("cat.jpg", "DCIM/", query))
        assertFalse(KeywordSyntax.matches("2024.jpg", "DCIM/", query))
    }

    @Test
    fun matchIsCaseInsensitive() {
        assertTrue(KeywordSyntax.matches("Vacation.JPG", "DCIM/Camera/", "vacation"))
        assertTrue(KeywordSyntax.matches("img.jpg", "Pictures/CAT/", "cat"))
    }

    @Test
    fun pipeMeansOr() {
        val query = parse("cat|dog")
        assertTrue(KeywordSyntax.matches("cat.jpg", "", query))
        assertTrue(KeywordSyntax.matches("dog.jpg", "", query))
        assertFalse(KeywordSyntax.matches("bird.jpg", "", query))
        // 竖线与空格混用：每组必须各有一个命中
        val mixed = parse("cat|dog 2024")
        assertTrue(KeywordSyntax.matches("dog 2024.jpg", "", mixed))
        assertFalse(KeywordSyntax.matches("cat.jpg", "", mixed))
    }

    @Test
    fun minusPrefixExcludes() {
        val query = parse("screenshot -whatsapp")
        assertTrue(KeywordSyntax.matches("Screenshot_1.png", "", query))
        assertFalse(KeywordSyntax.matches("Screenshot_whatsapp.png", "", query))
        // 只有排除词时，等价于「除了这些都要」
        val onlyExclude = parse("-截图")
        assertTrue(KeywordSyntax.matches("a.jpg", "", onlyExclude))
        assertFalse(KeywordSyntax.matches("某截图.jpg", "", onlyExclude))
    }

    @Test
    fun percentAndUnderscoreAreLiteral() {
        val query = parse("100%")
        assertTrue(KeywordSyntax.matches("100% done.jpg", "", query))
        assertFalse(KeywordSyntax.matches("1000 done.jpg", "", query))
        val underscore = parse("a_b")
        assertTrue(KeywordSyntax.matches("a_b.jpg", "", underscore))
        assertFalse(KeywordSyntax.matches("axb.jpg", "", underscore))
    }

    @Test
    fun parseKeepsGroupsAndExcludes() {
        val query = parse("Cat|Dog  -Screenshot -wechat")
        assertEquals(listOf(listOf("cat", "dog")), query.includes)
        assertEquals(listOf("screenshot", "wechat"), query.excludes)
    }
}
