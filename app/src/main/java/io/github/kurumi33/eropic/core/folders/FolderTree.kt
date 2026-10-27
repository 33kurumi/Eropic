package io.github.kurumi33.eropic.core.folders

import io.github.kurumi33.eropic.core.model.FolderRow

/**
 * 文件夹树节点（只读快照）。
 *
 * 路径沿用 [io.github.kurumi33.eropic.core.database.entity.MediaEntity.relativePath] 的写法：
 * 以 "/" 结尾，例如 "DCIM/Camera/"；图片直接落在存储根目录（relativePath 为空）时，
 * 会合成一个 path = "" 的「根目录」节点。
 */
data class FolderNode(
    /** 目录路径，以 "/" 结尾，与 MediaEntity.relativePath 一致；根目录为 ""。 */
    val path: String,
    /** 显示名：最后一级目录名；根目录为「根目录」。 */
    val name: String,
    /** 该目录直属图片数（不含子目录）。 */
    val directCount: Long,
    /** 该目录直属图片总字节数（不含子目录）。 */
    val directBytes: Long,
    /** 含所有子目录的图片数。 */
    val totalCount: Long,
    /** 含所有子目录的总字节数。 */
    val totalBytes: Long,
    /** 子节点，按 totalBytes 降序、name 升序。 */
    val children: List<FolderNode>,
    /** 所在层级：顶层目录为 0，逐级 +1；根目录节点为 0。 */
    val depth: Int,
)

/**
 * 把 [FolderRow]（按 relativePath 聚合出的一行）组装成目录树。
 *
 * 纯 Kotlin 实现，不依赖 Android，便于单元测试。
 * 用 "/" 切分 relativePath 逐级建树：中间目录即使没有直属图片也会建节点（directCount = 0）；
 * 各级 total 自底向上累计；兄弟节点按 totalBytes 降序、name 升序排序。
 */
object FolderTreeBuilder {

    /** 构建过程中的可变节点，统计值在 [computeTotals] 里自底向上累计。 */
    private class MutableNode(
        val path: String,
        val name: String,
        val depth: Int,
    ) {
        var directCount: Long = 0
        var directBytes: Long = 0
        var totalCount: Long = 0
        var totalBytes: Long = 0
        val children: MutableMap<String, MutableNode> = LinkedHashMap()
    }

    /** 兄弟节点排序：先按总大小降序，再按名字升序。 */
    private val siblingOrder: Comparator<MutableNode> =
        compareByDescending<MutableNode> { it.totalBytes }.thenBy { it.name }

    /**
     * 返回顶层节点：按 totalBytes 降序；根目录节点（若存在）固定排在最后。
     * 入参可为空列表，返回空列表。
     */
    fun build(rows: List<FolderRow>): List<FolderNode> {
        val roots = LinkedHashMap<String, MutableNode>()
        var rootDirectCount = 0L
        var rootDirectBytes = 0L

        for (row in rows) {
            val segments = splitSegments(row.relativePath)
            if (segments.isEmpty()) {
                // relativePath 为空：图片直接放在存储根目录，单独合成「根目录」节点。
                rootDirectCount += row.mediaCount
                rootDirectBytes += row.totalBytes
            } else {
                insert(roots, parentPath = "", segments = segments, depth = 0, row = row)
            }
        }

        // 先自底向上算完 total，兄弟排序才能读到正确的 totalBytes。
        roots.values.forEach { computeTotals(it) }

        val nodes = roots.values.sortedWith(siblingOrder).map { toNode(it) }.toMutableList()
        if (rootDirectCount > 0 || rootDirectBytes > 0) {
            nodes += FolderNode(
                path = "",
                name = "根目录",
                directCount = rootDirectCount,
                directBytes = rootDirectBytes,
                totalCount = rootDirectCount,
                totalBytes = rootDirectBytes,
                children = emptyList(),
                depth = 0,
            )
        }
        return nodes
    }

    /** 把一行统计按路径逐级插入；最后一段落到谁头上，directCount/directBytes 就加给谁。 */
    private fun insert(
        parent: MutableMap<String, MutableNode>,
        parentPath: String,
        segments: List<String>,
        depth: Int,
        row: FolderRow,
    ) {
        val name = segments.first()
        val path = parentPath + name + "/"
        val node = parent.getOrPut(name) { MutableNode(path = path, name = name, depth = depth) }
        if (segments.size == 1) {
            node.directCount += row.mediaCount
            node.directBytes += row.totalBytes
        } else {
            insert(
                parent = node.children,
                parentPath = path,
                segments = segments.subList(1, segments.size),
                depth = depth + 1,
                row = row,
            )
        }
    }

    /** 后序遍历：先算完所有子节点，再把结果汇总到当前节点。 */
    private fun computeTotals(node: MutableNode): Pair<Long, Long> {
        var count = node.directCount
        var bytes = node.directBytes
        for (child in node.children.values) {
            val (childCount, childBytes) = computeTotals(child)
            count += childCount
            bytes += childBytes
        }
        node.totalCount = count
        node.totalBytes = bytes
        return count to bytes
    }

    /** 转成不可变节点，同时按 siblingOrder 排好子节点。 */
    private fun toNode(node: MutableNode): FolderNode {
        val children = node.children.values.sortedWith(siblingOrder).map { toNode(it) }
        return FolderNode(
            path = node.path,
            name = node.name,
            directCount = node.directCount,
            directBytes = node.directBytes,
            totalCount = node.totalCount,
            totalBytes = node.totalBytes,
            children = children,
            depth = node.depth,
        )
    }

    /** 用 "/" 切分路径：忽略空段（开头的 "/"、重复的 "//"、结尾的 "/"）。 */
    private fun splitSegments(relativePath: String): List<String> =
        relativePath.split('/').filter { it.isNotBlank() }
}
