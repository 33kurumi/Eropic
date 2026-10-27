package io.github.kurumi33.eropic.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.kurumi33.eropic.AppContainer
import io.github.kurumi33.eropic.core.database.entity.DuplicateGroupEntity
import io.github.kurumi33.eropic.core.database.entity.MediaEntity
import io.github.kurumi33.eropic.core.index.IndexState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EropicViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.mediaRepository
    private val duplicateDao = container.database.duplicateDao()

    val media: Flow<PagingData<MediaEntity>> = repository.pagingData().cachedIn(viewModelScope)

    val count: StateFlow<Long> = repository.countFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val totalSize: StateFlow<Long> = repository.totalSizeFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val indexState: StateFlow<IndexState> = container.indexCoordinator.state

    val groups: StateFlow<List<DuplicateGroupEntity>> = duplicateDao.groupsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun members(groupId: Long): List<MediaEntity> = duplicateDao.members(groupId)

    fun startIndex() = container.indexCoordinator.start()

    fun cancelIndex() = container.indexCoordinator.cancel()

    fun rebuildIndex() {
        viewModelScope.launch {
            repository.clear()
            container.indexCoordinator.start()
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { EropicViewModel(container) }
        }
    }
}
