package io.github.kurumi33.eropic.core.media

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 修正 MediaStore RELATIVE_PATH 的大小写。
 *
 * 为什么需要：个别设备对部分行返回小写的 "pictures/"，而文件系统里的真实目录是 "Pictures"；
 * 原样落库会让索引里出现 Pictures 与 pictures 两个相册，同一个目录被拆成两份。
 * DATA 绝对路径是文件系统里的真实名字，能反推时优先用它（见 StoragePaths.relativePathOf）；
 * 只有 DATA 为空的行才会走到这里，用 File.list() 探测卷根下的真实目录名。
 *
 * 只校正顶层目录：DATA 可用时下层目录本来就对；逐段探测会把 10 万行的扫描变成大量 stat/readdir，
 * 而收益只是极少数 DATA 缺失的行，不值得。
 */
object PathCase {

    /** 卷根目录名列表的缓存：一次同步 10 万行也只 list 一次卷根。 */
    private val listings = ConcurrentHashMap<String, Listing>()

    /** 缓存有效期。顶层新建目录的大小写最多延迟这么久才被感知，换取扫描时不做重复 IO。 */
    private const val LISTING_TTL_MS = 60_000L

    private class Listing(val names: List<String>, val atMs: Long)

    /**
     * 把 [relativePath]（如 "pictures/a.jpg"、"pictures/"）的顶层目录替换成 [root] 下的真实目录名。
     *
     * 幂等：大小写已经正确时原样返回；[root] 不存在或读不到（例如没有全盘读取权限时
     * list() 返回 null）也原样返回，调用方不需要处理 null。
     */
    fun canonicalize(relativePath: String, root: String): String {
        if (relativePath.isEmpty() || root.isEmpty()) return relativePath
        val slash = relativePath.indexOf('/')
        val first = if (slash < 0) relativePath else relativePath.substring(0, slash)
        if (first.isEmpty()) return relativePath
        val actual = resolveTopLevel(root, first) ?: return relativePath
        if (actual == first) return relativePath
        // 只替换首段，保留其后的路径与结尾斜杠（RELATIVE_PATH 的目录形式以 "/" 结尾）。
        return actual + relativePath.substring(first.length)
    }

    private fun resolveTopLevel(root: String, segment: String): String? {
        val now = System.currentTimeMillis()
        val cached = listings[root]
        val listing = if (cached != null && now - cached.atMs < LISTING_TTL_MS) {
            cached
        } else {
            // 无全盘读取权限、卷未挂载时 list() 会返回 null 或抛 SecurityException，统一按"探测不到"处理；
            // 空列表同样缓存，避免每一行都去 list 一次卷根。
            val names = runCatching { File(root).list()?.toList() }.getOrNull().orEmpty()
            Listing(names, now).also { listings[root] = it }
        }
        return listing.names.firstOrNull { it.equals(segment, ignoreCase = true) }
    }

    /** 仅测试用：清空缓存的目录列表。 */
    internal fun clearCache() = listings.clear()
}
