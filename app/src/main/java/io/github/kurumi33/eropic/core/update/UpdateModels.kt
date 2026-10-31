package io.github.kurumi33.eropic.core.update

/**
 * 一个可安装的新版本（来自 GitHub Releases）。
 *
 * [versionCode] 刻意**不是**安装包真实的 versionCode：本项目的 versionCode 是构建时间戳
 * （秒级，见 app/build.gradle.kts），本地没法预先知道线上包的时间戳。
 * 所以能否更新由 [UpdateUiState.updateAvailable]（版本名比较）决定，[versionCode] 只作展示/兜底。
 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Long = 0L,
    val notes: String? = null,
    /** release 里 APK 资产的直链；没有 APK 资产时为 null（只能打开发布页）。 */
    val downloadUrl: String? = null,
    /** release 的网页地址，用于「打开发布页」。 */
    val pageUrl: String? = null,
)

/**
 * 设置页「应用更新」卡片的全部状态。
 *
 * 用一条不可变状态而不是多个 StateFlow：检查中/下载中/失败/已下载 是互斥的，
 * 拆成多个字段的组合会出现「既在检查又在下载」这类非法状态。
 */
data class UpdateUiState(
    val checking: Boolean = false,
    /** 检查失败原因（中文）。注意「仓库还没发 release」是正常情况，也走这里，文案要柔和。 */
    val checkingError: String? = null,
    val info: UpdateInfo? = null,
    /** 线上版本是否比当前安装的新；由版本名比较得出，见 [VersionCompare]。 */
    val updateAvailable: Boolean = false,
    val downloading: Boolean = false,
    /** 0f..1f；服务端不给 Content-Length 时保持 0。 */
    val progress: Float = 0f,
    /** 已下载完成的 APK 绝对路径；非空时卡片显示「安装更新」。 */
    val downloadedPath: String? = null,
    /** 一次性提示（下载成功 / 安装包被系统拒绝等）。 */
    val message: String? = null,
)

/**
 * 版本名比较（"0.10.0" > "0.9.0"，纯字符串比较会反），只看数字段。
 *
 * 本项目的版本名来自 build.gradle.kts 的 appVersionName（"0.7.0"），
 * debug 包会带 "-debug" 后缀，比较前先剥掉。
 */
object VersionCompare {

    /** [remote] 是否比 [local] 新。解析不出数字时保守地返回 false（不提示更新，避免误装旧包）。 */
    fun isNewer(remote: String?, local: String?): Boolean {
        val r = segments(remote) ?: return false
        val l = segments(local) ?: return false
        val size = maxOf(r.size, l.size)
        for (i in 0 until size) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** "v0.7.0-debug" → [0, 7, 0]；没有任何数字段时返回 null。 */
    fun segments(raw: String?): List<Int>? {
        val text = raw?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
        val head = text.substringBefore('-').substringBefore('+')
        val parts = head.split('.').mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
        return parts.ifEmpty { null }
    }
}
