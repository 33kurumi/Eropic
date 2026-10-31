package io.github.kurumi33.eropic.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.kurumi33.eropic.core.database.entity.IgnoredMemberEntity
import kotlinx.coroutines.flow.Flow

/** v0.9：「保留本组」标记表（[IgnoredMemberEntity]）。只增删标记，不碰用户文件。 */
@Dao
interface IgnoredDao {

    @Query("SELECT mediaId FROM ignored_member")
    suspend fun allIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<IgnoredMemberEntity>)

    @Query("SELECT COUNT(*) FROM ignored_member")
    fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM ignored_member")
    suspend fun count(): Int

    @Query("DELETE FROM ignored_member")
    suspend fun clear()
}