package io.github.kurumi33.eropic.core.index

import android.content.Context
import io.github.kurumi33.eropic.core.analysis.DuplicateScanner
import io.github.kurumi33.eropic.core.database.EropicDatabase
import io.github.kurumi33.eropic.core.media.MediaRepository
import io.github.kurumi33.eropic.core.settings.SimilarityLevel
import io.github.kurumi33.eropic.core.thumb.ThumbnailCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class IndexPhase { IDLE, SYNCING, HASHING, DEDUPING, DONE, ERROR }

data class IndexState(
    val phase: IndexPhase = IndexPhase.IDLE,
    val scanned: Int = 0,
    val changed: Int = 0,
    val signatureTotal: Int = 0,
    val signatureDone: Int = 0,
    val exactGroups: Int = 0,
    val nearGroups: Int = 0,
    val reclaimableBytes: Long = 0L,
    val message: String = "",
    val startedAtMs: Long = 0L,
    val finishedAtMs: Long = 0L,
    /** 无法解码而跳过的张数（解码失败的图不会反复重算） */
    val skippedCount: Int = 0,
    /**
     * 是否处于"暂停"状态（v0.7）。
     *
     * 暂停不等于取消：已经算完的指纹在暂停时会被合并回主表，进度保留；
     * [isRunning] 仍然为 true，避免图库把它当成"没在跑"再允许开一次。
     */
    val paused: Boolean = false,
) {
    val isRunning: Boolean
        get() = phase == IndexPhase.SYNCING || phase == IndexPhase.HASHING || phase == IndexPhase.DEDUPING

    /** 已耗时（运行中按当前时间，结束后按结束时间）。 */
    val elapsedMs: Long
        get() = when {
            startedAtMs <= 0L -> 0L
            finishedAtMs > 0L -> finishedAtMs - startedAtMs
            else -> System.currentTimeMillis() - startedAtMs
        }
}

/** M0 的索引编排：扫描 → 指纹 → 重复分组。串行执行，状态可观察。 */
class IndexCoordinator(
    private val context: Context,
    private val repository: MediaRepository,
    private val signatureGenerator: SignatureGenerator,
    private val duplicateScanner: DuplicateScanner,
    database: EropicDatabase,
    private val thumbnailCache: ThumbnailCache,
) {
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(IndexState())
    val state: StateFlow<IndexState> = _state.asStateFlow()
    private var job: Job? = null

    /**
     * 暂停标记。
     *
     * 用 @Volatile 而不是 StateFlow：只有计算循环会读它，暂停/继续按钮的反馈靠 _state。
     */
    @Volatile
    private var paused = false

    /**
     * 开始（或续算）一次索引。
     *
     * [similarity] 决定近重复的判定档位（设置里可调），[scope] 决定扫描哪些目录。
     * 两者只在本次任务内生效，中途改设置不会影响已经在跑的任务。
     */
    fun start(
        similarity: SimilarityLevel = SimilarityLevel.STANDARD,
        scope: ScanScope = ScanScope.Default,
    ) {
        if (_state.value.isRunning) return
        paused = false
        job = workerScope.launch {
            val started = System.currentTimeMillis()
            try {
                // 上次可能被暂停/杀进程时留下一批"已算完但没合并"的指纹，先合并再统计待算数量，
                // 否则它们会被当成没算过而重复解码。
                runCatching { repository.flushPendingSignatures() }
                _state.value = _state.value.copy(
                    phase = IndexPhase.SYNCING,
                    scanned = 0,
                    changed = 0,
                    startedAtMs = started,
                    finishedAtMs = 0L,
                    skippedCount = 0,
                    paused = false,
                    message = "正在读取系统相册索引（${scope.describe()}）…",
                )
                val sync = repository.syncFromMediaStore(scope) { scanned, changed ->
                    _state.value = _state.value.copy(scanned = scanned, changed = changed)
                }
                _state.value = _state.value.copy(
                    phase = IndexPhase.HASHING,
                    scanned = sync.scanned,
                    changed = sync.changed,
                    signatureTotal = repository.countNeedingSignature(),
                    signatureDone = 0,
                    message = "正在计算指纹…",
                )

                var done = 0
                var skipped = 0
                while (true) {
                    // 暂停：原地挂起（不退出任务），已算的结果仍在待落盘表里。
                    while (paused) {
                        delay(PAUSE_POLL_MS)
                    }
                    val batch = repository.itemsNeedingSignature(HASH_BATCH)
                    if (batch.isEmpty()) break
                    val failedIds = ArrayList<Long>()
                    coroutineScope {
                        batch.chunked(HASH_PARALLELISM).forEach { chunk ->
                            val results = chunk.map { item ->
                                async(Dispatchers.IO) {
                                    val generated = runCatching { signatureGenerator.generate(item) }.getOrNull()
                                    val ok = generated != null && runCatching {
                                        repository.saveSignature(
                                            id = item.id,
                                            dhash = generated.dhash,
                                            phash = generated.phash,
                                            blurScore = generated.blurScore,
                                            bands = generated.bands,
                                        )
                                    }.isSuccess
                                    item.id to ok
                                }
                            }.awaitAll()
                            results.filter { !it.second }.forEach { (id, _) -> failedIds += id }
                        }
                    }
                    // 必须落一个终态，否则这些行下次仍被选出来，while(true) 永远出不去。
                    if (failedIds.isNotEmpty()) {
                        repository.markSignatureUnsupported(failedIds)
                        skipped += failedIds.size
                    }
                    done += batch.size
                    _state.value = _state.value.copy(signatureDone = done)
                }

                // 指纹合并回主表：查重与识图都读 media_item，不合并就查不到 sha / dHash。
                // 放在这里也顺便把图库的刷新次数压到"整轮一次"。
                runCatching { repository.flushPendingSignatures() }
                _state.value = _state.value.copy(
                    phase = IndexPhase.DEDUPING,
                    paused = false,
                    message = "正在查找精确重复…",
                )
                val exact = duplicateScanner.scanExact()
                _state.value = _state.value.copy(
                    exactGroups = exact.groups,
                    message = "正在查找相似图片…",
                )
                val near = duplicateScanner.scanNear(
                    dThreshold = similarity.dhashThreshold,
                    pThreshold = similarity.phashThreshold,
                )
                // 索引期间写了缩略图，结束顺手按上限裁掉最旧的（失败不影响索引结果）。
                runCatching { thumbnailCache.trim() }
                _state.value = _state.value.copy(
                    phase = IndexPhase.DONE,
                    paused = false,
                    exactGroups = exact.groups,
                    nearGroups = near.groups,
                    reclaimableBytes = exact.reclaimableBytes + near.reclaimableBytes,
                    message = buildString {
                        append("完成：精确重复 ${exact.groups} 组 · 相似 ${near.groups} 组")
                        if (skipped > 0) append(" · 跳过 ${skipped} 张（无法解码）")
                    },
                    skippedCount = skipped,
                    finishedAtMs = System.currentTimeMillis(),
                )
            } catch (e: CancellationException) {
                // 用户点"取消"（或进程回收）时的正常退出：不能落成 ERROR，
                // 否则 cancel() 刚写好的"已取消"会被覆盖掉。
                throw e
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    phase = IndexPhase.ERROR,
                    message = "出错：${t.message ?: t::class.java.simpleName}",
                    finishedAtMs = System.currentTimeMillis(),
                )
            }
        }
    }

    /**
     * 暂停指纹计算（v0.7）。
     *
     * 只是让计算循环停在原地；已算好的指纹立刻合并回主表，"暂停再继续"不会白算。
     * 非运行状态调用无效果。
     */
    fun pause() {
        if (!_state.value.isRunning || paused) return
        paused = true
        _state.value = _state.value.copy(
            paused = true,
            message = "指纹计算已暂停（已算的结果已保存）",
        )
        workerScope.launch { runCatching { repository.flushPendingSignatures() } }
    }

    /** 继续被暂停的计算。 */
    fun resume() {
        if (!paused) return
        paused = false
        _state.value = _state.value.copy(
            paused = false,
            message = when (_state.value.phase) {
                IndexPhase.SYNCING -> "正在读取系统相册索引…"
                IndexPhase.DEDUPING -> "正在查找重复…"
                else -> "正在计算指纹…"
            },
        )
    }

    fun cancel() {
        paused = false
        job?.cancel()
        job = null
        // 取消也要把已算好的指纹合并回主表：否则下次重扫会把它们再解码一遍（白算）。
        workerScope.launch { runCatching { repository.flushPendingSignatures() } }
        _state.value = _state.value.copy(phase = IndexPhase.IDLE, paused = false, message = "已取消（已算的结果已保存）")
    }

    companion object {
        private const val HASH_BATCH = 400
        private const val HASH_PARALLELISM = 4

        /** 暂停期间轮询间隔：够省电，又不会让"继续"明显迟钝。 */
        private const val PAUSE_POLL_MS = 150L
    }
}