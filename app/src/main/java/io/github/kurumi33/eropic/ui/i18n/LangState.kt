package io.github.kurumi33.eropic.ui.i18n

import androidx.compose.runtime.getValue
import io.github.kurumi33.eropic.core.settings.AppLanguage
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * 当前生效语言（[AppLanguage.SYSTEM] 已解析成具体语言）。
 *
 * 用 Compose 的 [mutableStateOf] 存，是为了让 [tr] 在 Composable 里读到时能被记录成依赖：
 * 用户在设置页切换语言后，所有读过 [tr] 的界面会自动重组，无需重启。
 */
object LangState {

    /** 当前生效语言；默认跟随系统（启动时由 [update] 覆盖）。 */
    var current: AppLanguage by mutableStateOf(AppLanguage.CHINESE)
        private set

    /** 设置里配置的原始值（可能是 SYSTEM）。 */
    var configured: AppLanguage = AppLanguage.SYSTEM
        private set

    val isEnglish: Boolean get() = current == AppLanguage.ENGLISH

    /** 当前语言对应的 [Locale]，格式化日期/数字时使用。 */
    val locale: Locale get() = if (isEnglish) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE

    /**
     * 应用设置。幂等：值没变化时不做任何事，避免每次重组都触发全局刷新。
     *
     * @param settingsValue 设置里存的 raw code
     * @param systemLanguage 系统语言（省缺用 JVM 默认）
     */
    fun update(settingsValue: String, systemLanguage: String = Locale.getDefault().language) {
        val nextConfigured = AppLanguage.fromCode(settingsValue)
        val next = AppLanguage.resolve(nextConfigured, systemLanguage)
        if (configured == nextConfigured && current == next) return
        configured = nextConfigured
        current = next
        // 让平台侧的日期/数字格式化也跟着变（我们自己的 Text 不走资源，但系统控件会用到）。
        runCatching { Locale.setDefault(if (next == AppLanguage.ENGLISH) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE) }
    }
}