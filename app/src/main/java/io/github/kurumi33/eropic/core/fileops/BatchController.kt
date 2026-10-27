package io.github.kurumi33.eropic.core.fileops

import io.github.kurumi33.eropic.core.model.Bytes

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 批量写任务的进度快照；UI 顶部进度条只读它。 */
data class BatchState(
    val active: Boolean = false,
    val label: String = "",
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val currentName: String = "",
    val paused: Boolean = false,
    val cancelled: Boolean = false,
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    val summary: String
        get() = if (total <= 0) label else "$label $done/$total" + if (failed > 0) " · 失败 $failed" else ""
}

/**
 * 批量操作（移动/复制/重命名/删除）的进度 + 暂停 + 取消（对应规划 C-06）。
 *
 * 单个实例全局共享：同一时刻只允许一个批量任务在跑，Repository 在开始前 [begin]，
 * 每处理完一项 [step] 或 [fail]，结束时 [finish]。暂停采用协作式等待（[gate]），
 * 不打断正在写的那一个文件，避免产生半截文件。
 */
class BatchController {

    private val _state = MutableStateFlow(BatchState())
    val state: StateFlow<BatchState> = _state.asStateFlow()

    fun begin(label: String, total: Int) {
        _state.value = BatchState(active = true, label = label, total = total)
    }

    fun pause() {
        val current = _state.value
        if (current.active && !current.cancelled) _state.value = current.copy(paused = true)
    }

    fun resume() {
        val current = _state.value
        if (current.active) _state.value = current.copy(paused = false)
    }

    fun cancel() {
        val current = _state.value
        if (current.active) _state.value = current.copy(cancelled = true, paused = false)
    }

    /** 暂停时挂起等待；返回 false 表示已被取消，调用方应停止循环。 */
    suspend fun gate(): Boolean {
        while (true) {
            val current = _state.value
            if (current.cancelled) return false
            if (!current.paused) return true
            delay(120)
        }
    }

    fun step(currentName: String = "") {
        val current = _state.value
        _state.value = current.copy(done = current.done + 1, currentName = currentName)
    }

    fun fail(currentName: String = "") {
        val current = _state.value
        _state.value = current.copy(done = current.done + 1, failed = current.failed + 1, currentName = currentName)
    }

    fun finish() {
        val current = _state.value
        _state.value = current.copy(active = false, paused = false)
    }

    fun reset() {
        _state.value = BatchState()
    }
}

/** 一次写操作的结果报告（C-07）：成功/失败数、释放或搬动的字节、逐条失败原因。 */
data class FileOpReport(
    /** MOVE / COPY / RENAME / TRASH / RESTORE / DELETE / EMPTY_TRASH / FOLDER */
    val type: String,
    val title: String,
    val requested: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val cancelled: Boolean = false,
    val bytes: Long = 0,
    val failures: List<OpFailure> = emptyList(),
    /** 对应 operation_log 的 id，用于撤销/定位 */
    val logId: Long = 0,
    /** 逐项记录（from/to），撤销时按它把文件搬回去 */
    val items: List<OpItem> = emptyList(),
) {
    val message: String
        get() {
            val builder = StringBuilder()
            builder.append(if (succeeded > 0) "$title 成功 $succeeded 项" else "$title 未完成")
            if (failed > 0) builder.append("，失败 $failed 项")
            if (cancelled) builder.append("（已取消）")
            if (bytes > 0) builder.append(" · ").append(Bytes.human(bytes))
            return builder.toString()
        }
}