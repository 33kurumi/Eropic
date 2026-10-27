package io.github.kurumi33.eropic.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.kurumi33.eropic.core.database.dao.DuplicateDao
import io.github.kurumi33.eropic.core.database.dao.HashBandDao
import io.github.kurumi33.eropic.core.database.dao.MediaDao
import io.github.kurumi33.eropic.core.database.entity.DuplicateGroupEntity
import io.github.kurumi33.eropic.core.database.entity.DuplicateMemberEntity
import io.github.kurumi33.eropic.core.database.entity.HashBandEntity
import io.github.kurumi33.eropic.core.database.entity.MediaEntity

@Database(
    entities = [
        MediaEntity::class,
        HashBandEntity::class,
        DuplicateGroupEntity::class,
        DuplicateMemberEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class EropicDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun hashBandDao(): HashBandDao
    abstract fun duplicateDao(): DuplicateDao

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

        fun build(context: Context): EropicDatabase =
            Room.databaseBuilder(context, EropicDatabase::class.java, "eropic.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                // 兜底：未来新增版本忘了写迁移时，宁可清库重建也不要让 App 启动即崩。
                .fallbackToDestructiveMigration()
                .build()
    }
}
