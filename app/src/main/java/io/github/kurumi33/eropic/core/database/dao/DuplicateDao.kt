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

    /**
     * 文件被移入回收站或永久删除后，清掉它作为重复成员的归属行。
     *
     * 不清的话分组卡会一直显示"3 张 · 可释放 12MB"，实际组里只剩 2 张，
     * 用户按提示移走的可能是想保留的那张。
     */
    @Query("DELETE FROM duplicate_member WHERE mediaId IN (:ids)")
    suspend fun deleteMembersForMedia(ids: List<Long>)

    /** 按剩余成员重算每组的张数与可释放字节（口径：保留体积最大的一张）。 */
    @Query(
        "UPDATE duplicate_group SET " +
            "memberCount = (SELECT COUNT(*) FROM duplicate_member d WHERE d.groupId = duplicate_group.groupId), " +
            "reclaimableBytes = COALESCE((SELECT SUM(m.sizeBytes) - MAX(m.sizeBytes) FROM duplicate_member d " +
            "INNER JOIN media_item m ON m.id = d.mediaId WHERE d.groupId = duplicate_group.groupId), 0)",
    )
    suspend fun recomputeGroups()

    @Query("DELETE FROM duplicate_member WHERE groupId IN (SELECT groupId FROM duplicate_group WHERE memberCount < 2)")
    suspend fun deleteMembersOfIncompleteGroups()

    @Query("DELETE FROM duplicate_group WHERE memberCount < 2")
    suspend fun deleteIncompleteGroups()

    @Query("SELECT * FROM duplicate_group ORDER BY reclaimableBytes DESC")
    fun groupsFlow(): Flow<List<DuplicateGroupEntity>>

    /** 分组总数：诊断日志里用（只报数量，不含任何路径）。 */
    @Query("SELECT COUNT(*) FROM duplicate_group")
    suspend fun count(): Int

    @Query("SELECT m.* FROM media_item m INNER JOIN duplicate_member d ON m.id = d.mediaId WHERE d.groupId = :groupId ORDER BY m.sizeBytes DESC")
    suspend fun members(groupId: Long): List<io.github.kurumi33.eropic.core.database.entity.MediaEntity>
}
