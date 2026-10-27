package io.github.kurumi33.eropic.core.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 防抖闸门：[debounceMillis] 内的连续 [submit] 只保留最后一次，安静满这个时长后才执行 [action]。
 *
 * 单独抽成不依赖 Android 的类，是因为"一次相册变化到底触发几次同步"全由这段时序决定。
 * 用虚拟时钟的单测（DebouncerTest）可以把边界钉住，而 ContentObserver 本身在单测里没法模拟。
 *
 * 线程约束：[submit] / [cancel] 由同一线程调用（当前都是主线程的 ContentObserver 回调），因此不加锁。
 */
internal class Debouncer(
    private val scope: CoroutineScope,
    private val debounceMillis: Long,
    private val action: suspend () -> Unit,
) {
    private var pending: Job? = null

    fun submit() {
        // 相机连拍、云同步落盘时 MediaStore 会连发几十个 onChange，
        // 每个都同步等于把整库对账放大几十倍，所以后一次直接推翻前一次的计时。
        pending?.cancel()
        pending = scope.launch {
            delay(debounceMillis)
            action()
        }
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }
}
