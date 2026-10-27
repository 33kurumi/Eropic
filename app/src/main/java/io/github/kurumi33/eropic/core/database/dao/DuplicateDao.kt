package io.github.kurumi33.eropic.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.kurumi33.eropic.core.database.entity.DuplicateGroupEntity
import io.github.kurumi33.eropic.core.database.entity.DuplicateMemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DuplicateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: DuplicateGroupEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<DuplicateMemberEntity>)

    @Query("DELETE FROM duplicate_member")
    suspend fun clearMembers()

    @Query("DELETE FROM duplicate_group")
    suspend fun clearGroups()

    /** 清掉某一类（EXACT / NEAR）的分组及其成员，保留另一类结果。 */
    @Query("DELETE FROM duplicate_member WHERE groupId IN (SELECT groupId FROM duplicate_group WHERE kind = :kind)")
    suspend fun clearMembersOfKind(kind: String)

    @Query("DELETE FROM duplicate_group WHERE kind = :kind")
    suspend fun clearGroupsOfKind(kind: String)

    @Query("SELECT * FROM duplicate_group ORDER BY reclaimableBytes DESC")
    fun groupsFlow(): Flow<List<DuplicateGroupEntity>>

    @Query("SELECT m.* FROM media_item m INNER JOIN duplicate_member d ON m.id = d.mediaId WHERE d.groupId = :groupId ORDER BY m.sizeBytes DESC")
    suspend fun members(groupId: Long): List<io.github.kurumi33.eropic.core.database.entity.MediaEntity>
}
