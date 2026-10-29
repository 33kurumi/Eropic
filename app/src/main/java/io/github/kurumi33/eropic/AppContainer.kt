package io.github.kurumi33.eropic

import android.content.Context
import io.github.kurumi33.eropic.core.analysis.DuplicateScanner
import io.github.kurumi33.eropic.core.analysis.detectors.BlurDetector
import io.github.kurumi33.eropic.core.analysis.detectors.BurstDetector
import io.github.kurumi33.eropic.core.analysis.detectors.LargeFileDetector
import io.github.kurumi33.eropic.core.analysis.detectors.LargeImageDetector
import io.github.kurumi33.eropic.core.analysis.detectors.OldPhotoDetector
import io.github.kurumi33.eropic.core.analysis.detectors.ScreenshotDetector
import io.github.kurumi33.eropic.core.analysis.detectors.SmallImageDetector
import io.github.kurumi33.eropic.core.database.EropicDatabase
import io.github.kurumi33.eropic.core.fileops.BatchController
import io.github.kurumi33.eropic.core.fileops.CopyRepository
import io.github.kurumi33.eropic.core.fileops.FolderOps
import io.github.kurumi33.eropic.core.fileops.MoveRepository
import io.github.kurumi33.eropic.core.fileops.OperationHistoryRepository
import io.github.kurumi33.eropic.core.fileops.RenameRepository
import io.github.kurumi33.eropic.core.folders.FolderRepository
import io.github.kurumi33.eropic.core.index.IndexCoordinator
import io.github.kurumi33.eropic.core.index.SignatureGenerator
import io.github.kurumi33.eropic.core.notify.TrashCleanupScheduler
import io.github.kurumi33.eropic.core.trash.TrashRepository
import io.github.kurumi33.eropic.core.media.MediaRepository
import io.github.kurumi33.eropic.core.search.SearchHistoryStore
import io.github.kurumi33.eropic.core.settings.SettingsRepository
import io.github.kurumi33.eropic.core.thumb.ThumbnailBackfill
import io.github.kurumi33.eropic.core.thumb.ThumbnailCache

/** 手写依赖容器（M0 不引入 Hilt）。 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val database: EropicDatabase = EropicDatabase.build(appContext)
    val settingsRepository: SettingsRepository = SettingsRepository(appContext)

    /** v0.6：图库/文件夹搜索历史（独立 DataStore，最多 50 条）。 */
    val searchHistoryStore: SearchHistoryStore = SearchHistoryStore(appContext)
    val thumbnailCache: ThumbnailCache = ThumbnailCache(appContext, appContext.contentResolver)
    val mediaRepository: MediaRepository = MediaRepository(appContext, database)
    val signatureGenerator: SignatureGenerator = SignatureGenerator(appContext.contentResolver, thumbnailCache)
    val duplicateScanner: DuplicateScanner = DuplicateScanner(
        contentResolver = appContext.contentResolver,
        mediaDao = database.mediaDao(),
        duplicateDao = database.duplicateDao(),
    )
    val indexCoordinator: IndexCoordinator = IndexCoordinator(
        context = appContext,
        repository = mediaRepository,
        signatureGenerator = signatureGenerator,
        duplicateScanner = duplicateScanner,
        database = database,
        thumbnailCache = thumbnailCache,
    )

    // ---- M2 检测器（全部只读） ----

    private val detectionDao = database.detectionDao()
    val largeFileDetector: LargeFileDetector = LargeFileDetector(detectionDao)
    val oldPhotoDetector: OldPhotoDetector = OldPhotoDetector(detectionDao)
    val screenshotDetector: ScreenshotDetector = ScreenshotDetector(detectionDao)
    val blurDetector: BlurDetector = BlurDetector(detectionDao)
    val burstDetector: BurstDetector = BurstDetector(detectionDao)
    val smallImageDetector: SmallImageDetector = SmallImageDetector(detectionDao)
    val largeImageDetector: LargeImageDetector = LargeImageDetector(detectionDao)

    // ---- M3 写操作（真实移动 / 回收站；需要 MANAGE_EXTERNAL_STORAGE） ----

    val trashRepository: TrashRepository = TrashRepository(appContext, database, settingsRepository)
    val moveRepository: MoveRepository = MoveRepository(appContext, database)

    // ---- M4 批量写操作：进度/暂停/取消 + 复制 + 重命名 + 文件夹 + 操作历史/撤销 ----

    /** 全局唯一的批量任务控制器：进度条、暂停、取消都读它。 */
    val batchController: BatchController = BatchController()
    val copyRepository: CopyRepository = CopyRepository(appContext, database)
    val renameRepository: RenameRepository = RenameRepository(appContext, database)
    val folderOps: FolderOps = FolderOps(appContext, database)
    val operationHistoryRepository: OperationHistoryRepository =
        OperationHistoryRepository(appContext, database, trashRepository)

    // ---- v0.5 文件夹级操作（真实重命名 / 整目录移入回收站 / 置顶 / 封面） ----

    val folderRepository: FolderRepository =
        FolderRepository(appContext, database, settingsRepository, trashRepository)

    /** 立即跑一次回收站过期清理（设置里把保留期调小后调用，让新策略马上生效）。 */
    fun requestTrashCleanup() = TrashCleanupScheduler.requestImmediate(appContext)

    /** 补齐老图片的缩略图；requiresCharging 由设置决定。 */
    fun requestThumbnailBackfill(requiresCharging: Boolean) =
        ThumbnailBackfill.enqueue(appContext, requiresCharging)

    fun cancelThumbnailBackfill() = ThumbnailBackfill.cancel(appContext)
}