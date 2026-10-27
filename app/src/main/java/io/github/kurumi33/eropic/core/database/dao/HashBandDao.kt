package io.github.kurumi33.eropic.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.kurumi33.eropic.core.database.entity.HashBandEntity

@Dao
interface HashBandDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bands: List<HashBandEntity>)

    @Query("DELETE FROM hash_band WHERE mediaId = :mediaId")
    suspend fun deleteForMedia(mediaId: Long)

    @Query("SELECT DISTINCT mediaId FROM hash_band WHERE bandNo = :bandNo AND bandVal IN (:values)")
    suspend fun candidates(bandNo: Int, values: List<Int>): List<Long>

    @Query("DELETE FROM hash_band")
    suspend fun clear()
}
