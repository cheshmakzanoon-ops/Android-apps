package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.HistoryEntry
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.port.PhotoDuelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryPage(
    val entries: List<HistoryEntry>,
    val nextCursor: String?,
    val loadingMore: Boolean = false,
    val loadMoreError: PhotoDuelError? = null,
)

/** The viewer's past and current challenges across rooms, newest first, loaded a page at a time. */
class HistoryState(
    private val scope: CoroutineScope,
    private val service: PhotoDuelService,
    private val network: NetworkStatus,
) {
    private val _state = MutableStateFlow<Loadable<HistoryPage>>(Loadable.Loading)
    val state: StateFlow<Loadable<HistoryPage>> = _state.asStateFlow()
    private var job: Job? = null

    init {
        load()
    }

    fun load() {
        job?.cancel()
        if (_state.value !is Loadable.Loaded) _state.value = Loadable.Loading
        job = scope.launch {
            when (val result = network.track(service.myHistory(null))) {
                is AppResult.Success -> _state.value = Loadable.Loaded(HistoryPage(result.value.items, result.value.nextCursor))
                is AppResult.Failure -> {
                    val current = _state.value
                    _state.value = if (current is Loadable.Loaded) {
                        current.copy(stale = true)
                    } else {
                        Loadable.Failed(result.error, reconnecting = result.error.isConnectivity())
                    }
                }
            }
        }
    }

    fun loadMore() {
        val current = _state.value as? Loadable.Loaded ?: return
        val cursor = current.value.nextCursor ?: return
        if (current.value.loadingMore) return
        _state.value = current.copy(value = current.value.copy(loadingMore = true, loadMoreError = null))
        scope.launch {
            when (val result = network.track(service.myHistory(cursor))) {
                is AppResult.Success -> _state.update { latest ->
                    val page = (latest as? Loadable.Loaded)?.value ?: return@update latest
                    val known = page.entries.map { it.challenge.id }.toSet()
                    val fresh = result.value.items.filter { it.challenge.id !in known }
                    Loadable.Loaded(HistoryPage(page.entries + fresh, result.value.nextCursor))
                }
                is AppResult.Failure -> _state.update { latest ->
                    val page = (latest as? Loadable.Loaded)?.value ?: return@update latest
                    Loadable.Loaded(page.copy(loadingMore = false, loadMoreError = result.error))
                }
            }
        }
    }
}
