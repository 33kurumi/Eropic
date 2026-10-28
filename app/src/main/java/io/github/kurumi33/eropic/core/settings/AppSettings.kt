package io.github.kurumi33.eropic.core.settings

import io.github.kurumi33.eropic.core.media.SortOrder

/** 主题模式：跟随系统 / 强制浅色 / 强制深色。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

/** 回收站保留时长（对应规划 F-04）；[days] = 0 表示永不自动清理。 */
enum class RetentionOption(val days: Int, val label: String) {
    WEEK(7, "7 天"),
    MONTH(30, "30 天"),
    TWO_MONTHS(60, "60 天"),
    FOREVER(0, "永久保留"),
    ;

    companion object {
        fun fromDays(days: Int): RetentionOption = entries.firstOrNull { it.days == days } ?: MONTH
    }
}

/**
 * 相似图片判定档位（对应规划 F-05 / B-18）。
 *
 * 阈值同时作用于 dHash 与 pHash：两个距离都小于等于阈值才算相似，
 * 所以档位越严格、组越小、误报越少。
 */
enum class SimilarityLevel(val label: String, val dhashThreshold: Int, val phashThreshold: Int) {
    STRICT("严格：只合并几乎一样的", 6, 6),
    STANDARD("标准：推荐", 10, 8),
    LOOSE("宽松：找出更多相似", 12, 10),
    ;

    companion object {
        fun fromName(raw: String?): SimilarityLevel = entries.firstOrNull { it.name == raw } ?: STANDARD
    }
}

/**
 * 图库按时间分组的方式（用户要求：默认按月，可切成按日，也可关掉）。
 *
 * 折叠/展开交互在 ui/gallery/GalleryScreen.kt 里实现，这里只负责持久化。
 */
enum class DateGroupMode(val label: String) {
    NONE("不分组"),
    MONTH("按月"),
    DAY("按日"),
    ;

    companion object {
        fun fromName(raw: String?): DateGroupMode = entries.firstOrNull { it.name == raw } ?: MONTH
    }
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
    /** v0.2：图库按时间分组的粒度（不分组 / 按月 / 按日）。 */
    val dateGroupMode: DateGroupMode = DateGroupMode.MONTH,
    /** v0.2：回收站目录（相对主存储根；空串 = <卷根>/.EropicTrash）。 */
    val trashRelativeDir: String = "",
    /** M4：全屏查看器幻灯片间隔（秒）。 */
    val slideshowIntervalSec: Int = 3,
    /** M4：回收站保留时长。 */
    val retention: RetentionOption = RetentionOption.MONTH,
    /** M4：相似图片判定档位。 */
    val similarity: SimilarityLevel = SimilarityLevel.STANDARD,
    /** M4：扫描时是否包含隐藏目录（以 . 开头的目录）。 */
    val scanIncludeHidden: Boolean = false,
    /** M4：扫描时排除的目录（相对路径，不带结尾斜杠）。 */
    val scanExcludedDirs: List<String> = emptyList(),
    /**
     * v0.3：SauceNAO 的 API Key。
     *
     * SauceNAO 是少数必须带 key 的识图服务（免费注册后拿到个人 key）；
     * 不填也能用 Google Lens / Yandex / ascii2d，所以默认空串。
     */
    val sauceNaoApiKey: String = "",
) {
    /** 兼容 M4 的布尔语义：是否插入日期分组标题（= 分组粒度不是"不分组"）。 */
    val dateGrouping: Boolean get() = dateGroupMode != DateGroupMode.NONE

    /** 扫描范围；数据源与索引协调器都从这里取，避免两处规则不一致。 */
    fun scanScope(): io.github.kurumi33.eropic.core.index.ScanScope = io.github.kurumi33.eropic.core.index.ScanScope(
        includeHidden = scanIncludeHidden,
        excludedDirs = scanExcludedDirs,
    )
}
