package com.byanr.veena.android.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byanr.veena.android.VeenaApplication
import com.byanr.veena.android.audio.AudioPlayer
import com.byanr.veena.android.data.HistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as VeenaApplication).container
    private val dao = container.history

    val entries: StateFlow<List<HistoryEntry>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val player = AudioPlayer()

    /** Emits null once the row is confirmed missing (e.g. after delete). */
    fun observeEntry(id: Long): Flow<HistoryEntry?> = dao.observeById(id)

    fun delete(entry: HistoryEntry) {
        viewModelScope.launch {
            dao.delete(entry)
            if (entry.audioPath.isNotBlank()) {
                File(entry.audioPath).delete()
            }
        }
    }

    /** Removes every row and its audio file. */
    fun deleteAll() {
        viewModelScope.launch {
            val all = dao.all()
            dao.deleteAll()
            all.forEach(::deleteAudio)
        }
    }

    /**
     * Swipe-to-delete, step 1: drop the row but keep its audio so Undo can
     * bring it back. Finish with [restore] or [deleteAudio].
     */
    fun deleteRow(entry: HistoryEntry) {
        viewModelScope.launch { dao.delete(entry) }
    }

    /** Undo for [deleteRow]: re-inserts the row with its original id. */
    fun restore(entry: HistoryEntry) {
        viewModelScope.launch { dao.insert(entry) }
    }

    /** Commits a [deleteRow] once Undo is no longer offered. */
    fun deleteAudio(entry: HistoryEntry) {
        if (entry.audioPath.isNotBlank()) File(entry.audioPath).delete()
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
