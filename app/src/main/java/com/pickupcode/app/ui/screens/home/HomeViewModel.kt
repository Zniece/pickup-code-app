package com.pickupcode.app.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.pickupcode.app.data.CodeHistory
import com.pickupcode.app.data.CodeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(repo: CodeRepository) : ViewModel() {

    val activeHistory: StateFlow<List<CodeHistory>> = repo.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val trashHistory: StateFlow<List<CodeHistory>> = repo.observeTrash()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var dedupCount by mutableIntStateOf(0)
        private set

    fun refreshDedupCount(repo: CodeRepository) {
        viewModelScope.launch(Dispatchers.IO) {
            dedupCount = repo.countDuplicateGroups()
        }
    }

    fun markAsDone(item: CodeHistory, repo: CodeRepository, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.markDoneByCodeAndType(item.code, item.type)
                onSuccess()
            } catch (e: Exception) {
                Log.e("HomeViewModel", "标记已取失败", e)
                onError("操作失败，请重试")
            }
        }
    }

    fun undoDone(item: CodeHistory, trashItems: List<CodeHistory>, repo: CodeRepository) {
        viewModelScope.launch(Dispatchers.IO) {
            trashItems.filter { it.code == item.code && it.type == item.type }
                .forEach { repo.restore(it.id) }
        }
    }

    fun cleanExpired(repo: CodeRepository) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val oneDayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000
                repo.cleanExpired(oneDayAgo) { path ->
                    try { java.io.File(path).delete() } catch (e: Exception) { Log.w("HomeViewModel", "截图清理失败: $path", e) }
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "回收站清理失败", e)
            }
        }
    }
}
