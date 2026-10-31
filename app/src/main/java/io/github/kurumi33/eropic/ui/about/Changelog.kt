package io.github.kurumi33.eropic.ui.about

/**
 * 一条版本记录：版本号 + 一句话说清这一版最重要的变化。
 */
data class ChangelogEntry(
    val version: String,
    val summary: String,
    // 发布日期（yyyy-MM-dd）。老版本没有可靠日期，留空就不显示。
    val date: String = "",
)

/**
 * 0.1 → 0.7 的关键变更。
 *
 * 只写「用户能感知的一件大事」，不写内部重构细节：关于页是给用户看的，不是发布说明。
 * 数据与界面分开，后面加版本只需在这里补一行。
 */
val changelog: List<ChangelogEntry> = listOf(
    ChangelogEntry(
        version = "0.9",
        summary = "清理页新增「差分」分类：文件名主体相同、只有 p0/p1 结尾不同的图单独成组，不再混进相似；" +
            "对比预览支持组内多张左右滑动，并显示每张的分辨率/体积/格式/bpp 等参数与「建议保留」；" +
            "清理可以「全部取消」勾选；新增「保留本组」，以后不再出现这一组，并可一键清除所有保留标记重扫；" +
            "回收站显示真正在用的目录，目录不可写时自动回退，不再出现「无法创建回收站目录」。",
        date = "2026-10-31",
    ),
    ChangelogEntry(
        version = "0.8",
        summary = "文件夹路径大小写按真实存储校正（不再出现 Pictures 和 pictures 两个）；" +
            "文件夹页底部新增「视频」「GIF」虚拟入口（只统计，不动文件）；" +
            "图库「全部展开 / 收起」对全部日期生效；查看器新增「用其他应用打开」；" +
            "文件夹支持长按多选、批量置顶与移入回收站；文件夹页顶部显示真实张数与总大小。",
        date = "2026-10-31",
    ),
    ChangelogEntry(
        version = "0.7",
        summary = "联网识图扩到 7 家（Google Lens / 百度 / SauceNAO / ascii2d / IQDB / onegai.moe / lenso.ai）；" +
            "SauceNAO、ascii2d、IQDB 的结果改为应用内列表展示；本地识图新增颜色画像与同角色候选；" +
            "图库支持一键展开 / 收缩全部；指纹计算可以暂停与继续，已算好的结果不会丢；" +
            "设置页新增「应用更新」（检查更新、下载并安装）；发布包与版本号自增。",
        date = "2026-10-31",
    ),
    ChangelogEntry("0.1", "索引与查重原型：扫描图片、建本地索引、找出重复"),
    ChangelogEntry("0.2", "图库、文件夹、查看器、回收站，以及真实的文件移动"),
    ChangelogEntry("0.3", "清理页重构，文件夹按名称 / 大小 / 时间排序"),
    ChangelogEntry("0.4", "识图：本地指纹比对，加上联网以图搜图"),
    ChangelogEntry("0.5", "五个主 Tab 统一顶栏，文件夹页重做"),
    ChangelogEntry("0.6", "搜索入口与搜索语法、图库列数缩放、关于页与隐私协议"),
)
