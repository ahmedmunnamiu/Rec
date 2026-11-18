package com.ahmedmunna.callrecorder.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY startTime DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observe(id: Long): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings")
    suspend fun getAll(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE status = 'RECORDING'")
    suspend fun getInProgress(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE filePath = :path LIMIT 1")
    suspend fun findByPath(path: String): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE phoneNumber = :number AND ABS(startTime - :startTime) < :windowMs LIMIT 1")
    suspend fun findDuplicate(number: String, startTime: Long, windowMs: Long): RecordingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RecordingEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<RecordingEntity>)

    @Update
    suspend fun update(entity: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE recordings SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE recordings SET lastPlayPositionMs = :pos WHERE id = :id")
    suspend fun setPlayPosition(id: Long, pos: Long)
}

@Dao
interface EngineTestDao {
    @Query("SELECT * FROM engine_tests")
    fun observeAll(): Flow<List<EngineTestEntity>>

    @Query("SELECT * FROM engine_tests")
    suspend fun getAll(): List<EngineTestEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EngineTestEntity)
}

@Database(entities = [RecordingEntity::class, EngineTestEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
    abstract fun engineTests(): EngineTestDao
}
