package io.github.kurumi33.eropic.core.settings

import io.github.kurumi33.eropic.core.media.SortOrder

/** 主题模式：跟随系统 / 强制浅色 / 强制深色。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

/** 用户可调的界面与行为设置（全部本地保存在 DataStore）。 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** 图库网格列数，3~6。 */
    val gridColumns: Int = 4,
    /** 标题栏是否显示"张数 · 总大小"。 */
    val showMeta: Boolean = true,
    /** 新进图库时的默认排序。 */
    val defaultSort: SortOrder = SortOrder.DATE_DESC,
    /** 缩略图磁盘缓存开关；关闭后网格直接用原图（老图片会慢一些）。 */
    val thumbnailCacheEnabled: Boolean = true,
    /** 缩略图磁盘缓存上限（MB）。 */
    val thumbnailCacheMb: Int = 1024,
    /** 缩略图补齐是否只在充电时做（false = 不插电也补，耗电略高但更快备齐）。 */
    val thumbnailBackfillChargingOnly: Boolean = true,
    /** 启动时自动续算被打断的指纹任务（只续算，不会在全新安装上擅自开始全量扫描）。 */
    val autoResumeIndex: Boolean = true,
)
