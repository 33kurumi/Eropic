package io.github.kurumi33.eropic.core.settings

/**
 * App 界面语言（v1.1）。
 *
 * 项目早期的文案是直接硬编码中文字面量的，为了能在设置里切换英文，
 * 我们做了一层「运行时翻译」：英文模式下 Compose 的 Text 渲染前会把中文串交给 [tr]，
 * 由内置词表替换成英文。因此切换语言不需要重启 Activity，也不会丢列表滚动位置。
 */
enum class AppLanguage(val code: String, val labelZh: String, val labelEn: String) {
    SYSTEM("system", "跟随系统", "System default"),
    CHINESE("zh", "简体中文", "简体中文"),
    ENGLISH("en", "English", "English"),
    ;

    /** 设置页展示用的名字：跟随系统时按当前生效语言显示。 */
    fun displayLabel(current: AppLanguage): String = when (this) {
        SYSTEM -> if (current == ENGLISH) labelEn + " (" + labelZh + ")" else "跟随系统"
        CHINESE -> labelZh
        ENGLISH -> labelEn
    }

    companion object {
        fun fromCode(raw: String?): AppLanguage = entries.firstOrNull { it.code == raw } ?: SYSTEM

        /**
         * 「跟随系统」的解析规则：系统语言是中文就用中文，其它语言一律英文
         * （本 App 只有中英两套，英文对小语种用户至少能看懂）。
         */
        fun resolve(configured: AppLanguage, systemLanguage: String): AppLanguage = when (configured) {
            CHINESE -> CHINESE
            ENGLISH -> ENGLISH
            SYSTEM -> if (systemLanguage.startsWith("zh")) CHINESE else ENGLISH
        }
    }
}