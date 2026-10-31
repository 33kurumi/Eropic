package io.github.kurumi33.eropic.core.media

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 修正 MediaStore 给出的 RELATIVE_PATH / DATA 的大小写。
 *
 * 为什么需要：Android 的模拟存储（/storage/emulated/0）底层是大小写不敏感的文件系统，
 * 同一个目录既能写成 "Pictures" 也能写成 "pictures"，而 MediaStore 不同来源的行
 * （系统相机、第三方 App 直接写、MTP 拷贝、旧版扫描）会保留各自写入时的大小写。
 * 原样落库就会出现 Pictures 和 pictures 两个相册（DCIM/dcim、Tencent/tencent 同理），
 * 用户看到的是「同一目录被拆成两份」。
 *
 * 做法：以文件系统里真实目录名为准，**逐段**把相对路径的每一级都校正成真实大小写。
 * - 每一级目录只 list 一次（结果按目录路径缓存 60s），10 万行同步也只有几千次 readdir；
 * - 目录读不到（无全盘读取权限 / 卷未挂载）时原样返回该段，调用方不需要处理 null；
 * - 幂等：大小写已经正确时返回值与入参相同。
 */
object PathCase {

    /** 目录名列表缓存：key = 目录绝对路径。 */
    private val listings = ConcurrentHashMap<String, Listing>()

    /** 缓存有效期。新建目录的真实大小写最多延迟这么久才被感知，换取扫描时不做重复 IO。 */
    private const val LISTING_TTL_MS = 60_000L

    /** 逐段探测的最大深度；超出的段原样保留（正常相册不会这么深）。 */
    private const val MAX_DEPTH = 12

    private class Listing(val names: List<String>, val atMs: Long)

    /**
     * 把 [relativePath]（如 "pictures/Camera/"、"dcim/camera/IMG_1.jpg"）的每一级目录
     * 替换成 [root] 下真实目录名。结尾斜杠、分隔符形式保持不变。
     *
     * 幂等；[root] 不存在或读不到（例如没有全盘读取权限时 list() 返回 null）也原样返回。
     */
    fun canonicalize(relativePath: String, root: String): String = canonicalizeDeep(relativePath, root)

    /** 同 [canonicalize]：逐段校正大小写。 */
    fun canonicalizeDeep(relativePath: String, root: String): String {
        if (relativePath.isEmpty() || root.isEmpty()) return relativePath
        val normalized = relativePath.replace('\\', '/')
        val trailingSlash = normalized.endsWith("/")
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return relativePath

        val parentRoot = root.trimEnd('/')
        val builder = StringBuilder()
        var parent = parentRoot
        for ((index, segment) in segments.withIndex()) {
            val actual = if (index < MAX_DEPTH) resolve(parent, segment) else null
            val name = actual ?: segment
            if (builder.isNotEmpty()) builder.append('/')
            builder.append(name)
            parent = parentRoot + "/" + builder.toString()
        }
        if (trailingSlash) builder.append('/')
        return builder.toString()
    }

    /**
     * 绝对路径同样逐段校正：先按卷根推出相对部分，再拼回真实大小写。
     * 路径不在任何已知卷内时原样返回 [absolutePath]。
     */
    fun canonicalizeAbsolute(absolutePath: String, root: String): String {
        if (absolutePath.isEmpty()) return absolutePath
        val relative = StoragePaths.relativePathOf(absolutePath) ?: return absolutePath
        if (relative.isEmpty()) return absolutePath
        // relativePathOf 只给目录部分，文件名单独拼回去，避免把文件名也当目录探测
        val name = absolutePath.substringAfterLast('/')
        val directory = canonicalizeDeep(relative, root)
        return if (directory.isEmpty()) root.trimEnd('/') + "/" + name else root.trimEnd('/') + "/" + directory + name
    }

    private fun resolve(parent: String, segment: String): String? {
        val now = System.currentTimeMillis()
        val cached = listings[parent]
        val listing = if (cached != null && now - cached.atMs < LISTING_TTL_MS) {
            cached
        } else {
            // 无全盘读取权限、卷未挂载时 list() 会返回 null 或抛 SecurityException，统一按"探测不到"处理；
            // 空列表同样缓存，避免每一行都去 list 一次同一个目录。
            val names = runCatching { File(parent).list()?.toList() }.getOrNull().orEmpty()
            Listing(names, now).also { listings[parent] = it }
        }
        return listing.names.firstOrNull { it.equals(segment, ignoreCase = true) }
    }

    /** 仅测试用：清空缓存的目录列表。 */
    internal fun clearCache() = listings.clear()
}
