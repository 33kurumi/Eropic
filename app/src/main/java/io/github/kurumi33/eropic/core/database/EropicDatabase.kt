package io.github.kurumi33.eropic.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.kurumi33.eropic.core.analysis.detectors.DetectionDao
import io.github.kurumi33.eropic.core.database.dao.DuplicateDao
import io.github.kurumi33.eropic.core.database.dao.HashBandDao
import io.github.kurumi33.eropic.core.database.dao.MediaDao
import io.github.kurumi33.eropic.core.database.dao.OperationLogDao
import io.github.kurumi33.eropic.core.database.dao.SavedFilterDao
import io.github.kurumi33.eropic.core.database.dao.TrashDao
import io.github.kurumi33.eropic.core.database.entity.DuplicateGroupEntity
import io.github.kurumi33.eropic.core.database.entity.DuplicateMemberEntity
import io.github.kurumi33.eropic.core.database.entity.HashBandEntity
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.database.entity.OperationLogEntity
import io.github.kurumi33.eropic.core.database.entity.SavedFilterEntity
import io.github.kurumi33.eropic.core.database.entity.TrashItemEntity

@Database(
    entities = [
        MediaEntity::class,
        HashBandEntity::class,
        DuplicateGroupEntity::class,
        DuplicateMemberEntity::class,
        TrashItemEntity::class,
        OperationLogEntity::class,
        SavedFilterEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class EropicDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun hashBandDao(): HashBandDao
    abstract fun duplicateDao(): DuplicateDao
    abstract fun detectionDao(): DetectionDao
    abstract fun trashDao(): TrashDao
    abstract fun operationLogDao(): OperationLogDao
    abstract fun savedFilterDao(): SavedFilterDao

    companion object {
        /**
         * v1 -> v2：新增 App 内收藏标记（[io.github.kurumi33.eropic.core.database.entity.MediaEntity.localFavorite]）
         * 与 relativePath 索引（文件夹页按目录前缀查询）。
         *
         * 必须显式迁移：v1 已经可能在用户手机上建立了 10 万级索引，直接 destructive 迁移
         * 会让用户重新扫一遍库，代价是几分钟到几十分钟。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `media_item` ADD COLUMN `localFavorite` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_item_relativePath` " +
                        "ON `media_item` (`relativePath`)",
                )
            }
        }

        /**
         * v2 -> v3：M3 新增回收站表（trash_item）与写操作日志表（operation_log）。
         * 只是新增空表，不碰已有索引，因此用户不需要重新扫描 10 万张图。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trash_item` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`mediaId` INTEGER NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`trashPath` TEXT NOT NULL, " +
                        "`originalPath` TEXT NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`mimeType` TEXT NOT NULL, " +
                        "`trashedAtMs` INTEGER NOT NULL, " +
                        "`expiresAtMs` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `operation_log` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`summary` TEXT NOT NULL, " +
                        "`payloadJson` TEXT NOT NULL, " +
                        "`createdAtMs` INTEGER NOT NULL, " +
                        "`undone` INTEGER NOT NULL)",
                )
            }
        }

        /**
         * v3 -> v4：M4 新增"保存的筛选条件"表（saved_filter）；同样是纯新增空表，不触发重扫。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `saved_filter` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`filterJson` TEXT NOT NULL, " +
                        "`createdAtMs` INTEGER NOT NULL)",
                )
            }
        }

        fun build(context: Context): EropicDatabase =
            Room.databaseBuilder(context, EropicDatabase::class.java, "eropic.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                // 兜底：未来新增版本忘了写迁移时，宁可清库重建也不要让 App 启动即崩。
                .fallbackToDestructiveMigration()
                .build()
    }
}