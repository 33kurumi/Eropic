package io.github.kurumi33.eropic.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import io.github.kurumi33.eropic.core.database.entity.SavedFilterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedFilterDao {

    @Insert
    suspend fun insert(filter: SavedFilterEntity): Long

    @Delete
    suspend fun delete(filter: SavedFilterEntity)

    @Query("DELETE FROM saved_filter WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM saved_filter ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<SavedFilterEntity>>

    @Query("SELECT * FROM saved_filter ORDER BY createdAtMs DESC")
    suspend fun all(): List<SavedFilterEntity>

    @Query("SELECT * FROM saved_filter WHERE id = :id")
    suspend fun byId(id: Long): SavedFilterEntity?
}
