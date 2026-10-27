package io.github.kurumi33.eropic.core.model

/** 字节数的人类可读格式；报告页/操作历史里用，UI 层另有 Compose 版本。 */
object Bytes {
    fun human(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = value.toDouble() / 1024
        var unit = 0
        while (v >= 1024 && unit < units.lastIndex) {
            v /= 1024
            unit++
        }
        return String.format("%.1f %s", v, units[unit])
    }
}
