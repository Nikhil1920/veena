package com.byanr.veena.android.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val languageCode: String,
    val transcript: String,
    val audioPath: String,
    val durationMs: Long,
    /**
     * "mic" for live dictation, "import" for a picked audio file,
     * [SOURCE_CONVERSATION] for a Conversation transcript (no audio).
     */
    val source: String,
)

const val SOURCE_CONVERSATION = "conversation"

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun byId(id: Long): HistoryEntry?

    @Query("SELECT * FROM history WHERE id = :id")
    fun observeById(id: Long): Flow<HistoryEntry?>

    @Insert
    suspend fun insert(entry: HistoryEntry): Long

    /** Inserts [entry] or replaces the row with its id; returns the new row id, or -1 when replaced. */
    @Upsert
    suspend fun upsert(entry: HistoryEntry): Long

    @Delete
    suspend fun delete(entry: HistoryEntry)

    @Query("SELECT * FROM history")
    suspend fun all(): List<HistoryEntry>

    @Query("DELETE FROM history")
    suspend fun deleteAll()
}

@Database(entities = [HistoryEntry::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        fun build(context: Context): HistoryDatabase =
            Room.databaseBuilder(context, HistoryDatabase::class.java, "veena-history.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
