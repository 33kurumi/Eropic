package io.github.kurumi33.eropic.core.media

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PathCaseTest {

    @get:Rule
    val temp = TemporaryFolder()

    // 缓存是进程级的，测试之间清掉，避免临时目录被复用后读到上一条用例的列表。
    @Before
    fun clearCache() {
        PathCase.clearCache()
    }

    private fun newRoot(): File = temp.newFolder("root")

    @Test
    fun lowerCaseTopLevelGetsRealDirectoryName() {
        val root = newRoot()
        File(root, "Pictures/Camera").mkdirs()

        assertEquals("Pictures/Camera/a.jpg", PathCase.canonicalize("pictures/Camera/a.jpg", root.absolutePath))
    }

    @Test
    fun alreadyCorrectCaseIsUnchanged() {
        val root = newRoot()
        File(root, "Pictures").mkdirs()

        assertEquals("Pictures/a.jpg", PathCase.canonicalize("Pictures/a.jpg", root.absolutePath))
    }

    @Test
    fun trailingSlashIsPreserved() {
        val root = newRoot()
        File(root, "Pictures").mkdirs()

        assertEquals("Pictures/", PathCase.canonicalize("pictures/", root.absolutePath))
    }

    @Test
    fun missingTopLevelIsReturnedAsIs() {
        val root = newRoot()
        File(root, "Pictures").mkdirs()

        assertEquals("screenshots/a.jpg", PathCase.canonicalize("screenshots/a.jpg", root.absolutePath))
    }

    @Test
    fun emptyRelativePathIsReturnedAsIs() {
        val root = newRoot()

        assertEquals("", PathCase.canonicalize("", root.absolutePath))
    }

    @Test
    fun blankRootIsReturnedAsIs() {
        assertEquals("pictures/a.jpg", PathCase.canonicalize("pictures/a.jpg", ""))
    }

    @Test
    fun unreadableRootIsReturnedAsIs() {
        val missing = File(temp.root, "not-created").absolutePath

        assertEquals("pictures/a.jpg", PathCase.canonicalize("pictures/a.jpg", missing))
    }

    @Test
    fun onlyTopLevelIsCorrected() {
        val root = newRoot()
        File(root, "Pictures/camera").mkdirs()

        // 下层目录的准确大小写只能来自 DATA，这里刻意保持原样。
        assertEquals("Pictures/camera/a.jpg", PathCase.canonicalize("pictures/camera/a.jpg", root.absolutePath))
    }

    @Test
    fun canonicalizeIsIdempotent() {
        val root = newRoot()
        File(root, "Pictures").mkdirs()

        val once = PathCase.canonicalize("pictures/a.jpg", root.absolutePath)
        val twice = PathCase.canonicalize(once, root.absolutePath)

        assertEquals("Pictures/a.jpg", once)
        assertEquals(once, twice)
    }
}
