package io.github.kurumi33.eropic

import android.content.Context
import io.github.kurumi33.eropic.core.analysis.DuplicateScanner
import io.github.kurumi33.eropic.core.analysis.detectors.BlurDetector
import io.github.kurumi33.eropic.core.analysis.detectors.LargeFileDetector
import io.github.kurumi33.eropic.core.analysis.detectors.OldPhotoDetector
import io.github.kurumi33.eropic.core.analysis.detectors.ScreenshotDetector
import io.github.kurumi33.eropic.core.database.EropicDatabase
import io.github.kurumi33.eropic.core.index.IndexCoordinator
import io.github.kurumi33.eropic.core.index.SignatureGenerator
import io.github.kurumi33.eropic.core.media.MediaRepository
import io.github.kurumi33.eropic.core.settings.SettingsRepository
import io.github.kurumi33.eropic.core.thumb.ThumbnailBackfill
import io.github.kurumi33.eropic.core.thumb.ThumbnailCache

/** 手写依赖容器（M0 不引入 Hilt）。 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: EropicDatabase = EropicDatabase.build(appContext)
    val settingsRepository: SettingsRepository = SettingsRepository(appContext)
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

    /** 充电时补齐老图片的缩略图（只读照片，不改动用户文件）。 */
    fun requestThumbnailBackfill() = ThumbnailBackfill.enqueue(appContext)

    fun cancelThumbnailBackfill() = ThumbnailBackfill.cancel(appContext)
}
