package dev.irohaco.feature.timeline.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.feature.timeline.TimelinePagingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Opens the timeline at one asset and pages through its neighbours in timeline order. It owns the
 * video player, so playback survives rotation and is released when the viewer closes.
 *
 * @param startPositionMs plays the opened video from this position right away (`PlayVideoAt`).
 * @param newPlayer creates the Media3 player on first playback; the app supplies an ExoPlayer.
 */
class ViewerViewModel(
    private val getPage: GetTimelinePage,
    assetId: String,
    startPositionMs: Long? = null,
    private val newPlayer: () -> Player,
) : ViewModel() {
    sealed interface Start {
        data object Loading : Start
        data object NotFound : Start
        data class At(val index: Int) : Start
    }

    private val _start = MutableStateFlow<Start>(Start.Loading)
    val start: StateFlow<Start> = _start.asStateFlow()

    val items: Flow<PagingData<TimelineItem>> = flow {
        val index = (_start.first { it is Start.At } as Start.At).index
        emitAll(
            Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = true), initialKey = max(0, index - PAGE_SIZE / 2)) {
                TimelinePagingSource(getPage)
            }.flow,
        )
    }.cachedIn(viewModelScope)

    private var player: Player? = null

    /** The asset shown in the player, with its player; null shows the still frame. */
    private val _playing = MutableStateFlow<Pair<String, Player>?>(null)
    val playing: StateFlow<Pair<String, Player>?> = _playing.asStateFlow()

    init {
        viewModelScope.launch {
            val index = getPage.indexOf(assetId)
            _start.value = index?.let { Start.At(it) } ?: Start.NotFound
            if (index != null && startPositionMs != null) {
                getPage(index, 1).firstOrNull()?.takeIf { it.kind == MediaKind.VIDEO }?.let { play(it, startPositionMs) }
            }
        }
    }

    fun play(item: TimelineItem, fromMs: Long = 0) {
        val p = player ?: newPlayer().also { player = it }
        p.setMediaItem(MediaItem.fromUri(item.contentUri), fromMs)
        p.prepare()
        p.playWhenReady = true
        _playing.value = item.assetId to p
    }

    /** Back to the still frame, e.g. after swiping to another page. */
    fun stop() {
        player?.stop()
        _playing.value = null
    }

    /** Keeps the position and the player on screen, e.g. while the app is in the background. */
    fun pause() {
        player?.pause()
    }

    override fun onCleared() {
        player?.release()
        player = null
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
