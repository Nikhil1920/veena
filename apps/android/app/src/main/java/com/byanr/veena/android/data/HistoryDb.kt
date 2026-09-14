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
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val languageCode: String,
    val transcript: String,
    val audioPath: String,
    val durationMs: Long,
    /** "mic" for live dictation, "import" for a picked audio file. */
    val source: String,
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun byId(id: Long): HistoryEntry?

    @Insert
    suspend fun insert(entry: HistoryEntry): Long

    @Delete
    suspend fun delete(entry: HistoryEntry)
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
