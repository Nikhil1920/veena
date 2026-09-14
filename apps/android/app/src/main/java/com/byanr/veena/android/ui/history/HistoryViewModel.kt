package com.byanr.veena.android.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byanr.veena.android.VeenaApplication
import com.byanr.veena.android.audio.AudioPlayer
import com.byanr.veena.android.data.HistoryEntry
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

    fun delete(entry: HistoryEntry) {
        viewModelScope.launch {
            dao.delete(entry)
            if (entry.audioPath.isNotBlank()) {
                File(entry.audioPath).delete()
            }
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
