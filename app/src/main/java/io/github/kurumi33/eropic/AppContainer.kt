package io.github.kurumi33.eropic

import android.content.Context
import io.github.kurumi33.eropic.core.analysis.DuplicateScanner
import io.github.kurumi33.eropic.core.database.EropicDatabase
import io.github.kurumi33.eropic.core.index.IndexCoordinator
import io.github.kurumi33.eropic.core.index.SignatureGenerator
import io.github.kurumi33.eropic.core.media.MediaRepository

/** 手写依赖容器（M0 不引入 Hilt）。 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: EropicDatabase = EropicDatabase.build(appContext)
    val mediaRepository: MediaRepository = MediaRepository(appContext, database)
    val signatureGenerator: SignatureGenerator = SignatureGenerator(appContext.contentResolver)
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
    )
}
