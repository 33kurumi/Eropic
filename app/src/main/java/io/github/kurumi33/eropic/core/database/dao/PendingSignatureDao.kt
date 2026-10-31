package io.github.kurumi33.eropic.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.kurumi33.eropic.core.database.entity.PendingSignatureEntity

/** 见 [PendingSignatureEntity]：指纹先写这里，再批量合并回 media_item。 */
@Dao
interface PendingSignatureDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PendingSignatureEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PendingSignatureEntity>)

    @Query("SELECT COUNT(*) FROM pending_signature")
    suspend fun count(): Int

    @Query("DELETE FROM pending_signature WHERE mediaId IN (:ids)")
    suspend fun deleteForMedia(ids: List<Long>)

    /** 合并的原子部分 1：把待落盘指纹写回 media_item（每条子查询都走主键，10 万条也是毫秒级）。 */
    @Query(
        "UPDATE media_item SET " +
            "dhash = (SELECT p.dhash FROM pending_signature p WHERE p.mediaId = media_item.id), " +
            "phash = (SELECT p.phash FROM pending_signature p WHERE p.mediaId = media_item.id), " +
            "blurScore = (SELECT p.blurScore FROM pending_signature p WHERE p.mediaId = media_item.id), " +
            "sha256 = COALESCE((SELECT p.sha256 FROM pending_signature p WHERE p.mediaId = media_item.id), media_item.sha256), " +
            "signatureVersion = (SELECT p.signatureVersion FROM pending_signature p WHERE p.mediaId = media_item.id) " +
            "WHERE id IN (SELECT mediaId FROM pending_signature)"
    )
    suspend fun flushIntoMedia()

    /** 合并的原子部分 2：清空待落盘表（flushIntoMedia 成功后调用）。 */
    @Query("DELETE FROM pending_signature")
    suspend fun clear()
}
