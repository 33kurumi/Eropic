package io.github.kurumi33.eropic.ui

import io.github.kurumi33.eropic.core.fileops.FileOpReport

/**
 * 非底部 Tab 的二级页面（M4）。
 *
 * 用 sealed interface + 单个 StateFlow 管理，而不是引入 Navigation Compose：
 * 页面不多、参数都是内存对象（FileOpReport 无法序列化），手写一个返回栈足够，
 * 也避免旋转屏幕后参数丢失的问题。
 */
sealed interface AppScreen {

    /** 图片搜索（文件名/目录模糊搜索 + 保存的筛选）。 */
    data object Search : AppScreen

    /** 存储仪表盘：按格式/年份/目录看占用。 */
    data object Storage : AppScreen

    /** 操作历史（可撤销）。 */
    data object History : AppScreen

    /** 空文件夹管理。 */
    data object FolderManager : AppScreen

    /** 清理报告：本次清理的统计与建议。 */
    data object CleanupReport : AppScreen

    /** 关于：版本、开源许可、崩溃日志。 */
    data object About : AppScreen

    /** 批量操作结果报告页（C-07）。 */
    data class Report(val report: FileOpReport) : AppScreen
}
