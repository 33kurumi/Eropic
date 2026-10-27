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
)
