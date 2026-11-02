package app.sleepscore.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepDao {
    @Insert
    suspend fun insertSession(s: SleepSession): Long

    @Update
    suspend fun updateSession(s: SleepSession)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpoch(e: EpochRow)

    @Query("SELECT * FROM sessions WHERE endMs IS NOT NULL ORDER BY startMs DESC")
    fun observeCompleted(): Flow<List<SleepSession>>

    @Query("SELECT * FROM sessions WHERE endMs IS NULL ORDER BY startMs DESC LIMIT 1")
    fun observeActive(): Flow<SleepSession?>

    @Query("SELECT * FROM sessions WHERE endMs IS NULL ORDER BY startMs DESC LIMIT 1")
    suspend fun getActive(): SleepSession?

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: Long): SleepSession?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeSession(id: Long): Flow<SleepSession?>

    @Query("SELECT * FROM sessions WHERE endMs IS NOT NULL")
    suspend fun getAllCompleted(): List<SleepSession>

    @Query("SELECT * FROM epochs WHERE sessionId = :id ORDER BY minuteIndex")
    suspend fun getEpochs(id: Long): List<EpochRow>

    @Query("SELECT MAX(minuteIndex) FROM epochs WHERE sessionId = :id")
    suspend fun lastMinuteIndex(id: Long): Int?

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM sessions WHERE startMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}

@Database(entities = [SleepSession::class, EpochRow::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): SleepDao

    companion object {
        fun build(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "sleepscore.db").build()
    }
}
