package io.github.kurumi33.eropic.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = true,
)
abstract class EropicDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun hashBandDao(): HashBandDao
    abstract fun duplicateDao(): DuplicateDao

    companion object {
        fun build(context: Context): EropicDatabase =
            Room.databaseBuilder(context, EropicDatabase::class.java, "eropic.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration()
                .build()
    }
}
