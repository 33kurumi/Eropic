package io.github.kurumi33.eropic.ui.about

/**
 * 一条版本记录：版本号 + 一句话说清这一版最重要的变化。
 */
data class ChangelogEntry(
    val version: String,
    val summary: String,
)

/**
 * 0.1 → 0.6 的关键变更。
 *
 * 只写「用户能感知的一件大事」，不写内部重构细节：关于页是给用户看的，不是发布说明。
 * 数据与界面分开，后面加版本只需在这里补一行。
 */
val changelog: List<ChangelogEntry> = listOf(
    ChangelogEntry("0.1", "索引与查重原型：扫描图片、建本地索引、找出重复"),
    ChangelogEntry("0.2", "图库、文件夹、查看器、回收站，以及真实的文件移动"),
    ChangelogEntry("0.3", "清理页重构，文件夹按名称 / 大小 / 时间排序"),
    ChangelogEntry("0.4", "识图：本地指纹比对，加上联网以图搜图"),
    ChangelogEntry("0.5", "五个主 Tab 统一顶栏，文件夹页重做"),
    ChangelogEntry("0.6", "搜索入口与搜索语法、图库列数缩放、关于页与隐私协议"),
)
