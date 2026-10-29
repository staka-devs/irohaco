package dev.irohaco.feature.timeline.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.irohaco.core.application.GetMediaDetails
import dev.irohaco.core.application.BrowseMap
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.contracts.Annotation
import dev.irohaco.core.contracts.AnnotationKind
import dev.irohaco.core.contracts.AnnotationRevision
import dev.irohaco.core.contracts.AssetMarks
import dev.irohaco.core.application.MarkMedia
import dev.irohaco.core.application.EditMetadata
import dev.irohaco.core.contracts.FieldEdit
import dev.irohaco.core.contracts.MetadataEdits
import dev.irohaco.core.contracts.MetadataField
import dev.irohaco.core.application.ManageAnnotations
import dev.irohaco.core.application.ManageCollections
import dev.irohaco.core.contracts.Tag
import dev.irohaco.core.contracts.MediaFormatProbe
import dev.irohaco.core.domain.MediaFormat
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import dev.irohaco.core.domain.Detail
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.core.domain.MediaDetails
import dev.irohaco.core.domain.MediaDetailsResult
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.feature.timeline.TimelinePagingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val getDetails: GetMediaDetails? = null,
    /** The list the viewer was opened from: the timeline's choice, or one folder of the library. */
    private val query: TimelineQuery = getPage.query.value,
    private val mark: MarkMedia? = null,
    private val collections: ManageCollections? = null,
    private val formatProbe: MediaFormatProbe? = null,
    private val map: BrowseMap? = null,
    private val annotations: ManageAnnotations? = null,
    private val metadata: EditMetadata? = null,
) : ViewModel() {
    private val currentItem = MutableStateFlow<TimelineItem?>(null)
    private val current = currentItem.map { it?.assetId }.distinctUntilChanged()

    /** RAW, HDR or Motion Photo of the page on screen, for its label (PRD GAL-009). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val format: StateFlow<MediaFormat> = currentItem
        .flatMapLatest { item -> flow { emit(if (item == null || formatProbe == null) MediaFormat() else formatProbe.probe(item.contentUri, item.kind)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaFormat())

    /** The user's edits of the page on screen, kept in the DB (PRD MET-002). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val edits: StateFlow<MetadataEdits> = current
        .flatMapLatest { id -> if (id == null || metadata == null) flowOf(MetadataEdits()) else metadata.edits(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MetadataEdits())

    /** GPS of the page on screen, read the same way as the details sheet (PRD GAL-013), unless the user edited it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val location: StateFlow<Detail<GeoPoint>> = currentItem
        .flatMapLatest { item ->
            flow {
                emit(
                    if (item == null || getDetails == null) {
                        Detail.Missing
                    } else {
                        when (val result = getDetails(item)) {
                            is MediaDetailsResult.Read -> {
                                fileRead = item.assetId to result.details
                                metadata?.compareWithFile(item.assetId, result.details)
                                result.details.location
                            }
                            MediaDetailsResult.FileUnavailable -> Detail.Missing
                        }
                    },
                )
            }
        }
        .combine(edits) { file, edits ->
            when (val edit = edits.location) {
                null -> file
                FieldEdit.Removed -> Detail.Missing
                is FieldEdit.Set -> Detail.Value(edit.value)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Detail.Missing)

    /** The place name of the page on screen, once the user allowed lookups (GAL-017, ADR-0011). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val placeName: StateFlow<String?> = combine(currentItem, location) { item, location -> item to location }
        .flatMapLatest { (item, location) ->
            flow { emit(if (item == null || map == null || location !is Detail.Value) null else map.placeName(item.assetId, location.value)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Favorite and rating of the page on screen (PRD GAL-007). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val marks: StateFlow<AssetMarks> = current
        .flatMapLatest { id -> if (id == null || mark == null) flowOf(AssetMarks()) else mark.marks(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetMarks())

    @OptIn(ExperimentalCoroutinesApi::class)
    val tags: StateFlow<List<Tag>> = current
        .flatMapLatest { id -> if (id == null || collections == null) flowOf(emptyList()) else collections.tagsOf(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allTags: StateFlow<List<Tag>> =
        (collections?.tags() ?: flowOf(emptyList())).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addTag(item: TimelineItem, name: String) {
        viewModelScope.launch { collections?.addTag(listOf(item), name) }
    }

    fun removeTag(item: TimelineItem, tagId: String) {
        viewModelScope.launch { collections?.removeTag(item.assetId, tagId) }
    }

    /** Search memos of the page on screen (ANN-001). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val memos: StateFlow<List<Annotation>> = current
        .flatMapLatest { id -> if (id == null || annotations == null) flowOf(emptyList()) else annotations.annotations(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sceneNotIndexed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** A memo for the playing scene could not be added: that scene is not indexed yet (#155). */
    val sceneNotIndexed: SharedFlow<Unit> = _sceneNotIndexed.asSharedFlow()

    /** [atScene]: on the scene of the video playing now, rather than on the whole file. */
    fun addMemo(item: TimelineItem, kind: AnnotationKind, text: String, atScene: Boolean = false) {
        // The player is read on the main thread, where it lives.
        val at = playing.value?.takeIf { it.first == item.assetId }?.second?.currentPosition ?: 0
        viewModelScope.launch {
            if (!atScene) {
                annotations?.add(item.assetId, kind, text)
            } else if (annotations?.addToScene(item.assetId, at, kind, text) == false) {
                _sceneNotIndexed.tryEmit(Unit)
            }
        }
    }

    fun editMemo(annotationId: String, text: String) {
        viewModelScope.launch { annotations?.edit(annotationId, text) }
    }

    fun setMemoSearchable(annotationId: String, searchable: Boolean) {
        viewModelScope.launch { annotations?.setSearchable(annotationId, searchable) }
    }

    fun deleteMemo(annotationId: String) {
        viewModelScope.launch { annotations?.delete(annotationId) }
    }

    suspend fun memoHistory(annotationId: String): List<AnnotationRevision> = annotations?.history(annotationId).orEmpty()

    fun showing(item: TimelineItem?) {
        currentItem.value = item
    }

    fun setFavorite(item: TimelineItem, favorite: Boolean) {
        viewModelScope.launch { mark?.setFavorite(listOf(item), favorite) }
    }

    // The page's file as last read, so a new edit records the value it was made against (ADR-0007).
    private var fileRead: Pair<String, MediaDetails>? = null

    private fun applyEdit(item: TimelineItem, change: suspend EditMetadata.() -> Unit) {
        val metadata = metadata ?: return
        viewModelScope.launch {
            metadata.change()
            fileRead?.takeIf { it.first == item.assetId }?.let { metadata.compareWithFile(item.assetId, it.second) }
        }
    }

    /** null goes back to the file's value. */
    fun setTakenAt(item: TimelineItem, takenAtMs: Long?) = applyEdit(item) { setTakenAt(item.assetId, takenAtMs) }

    fun setLocation(item: TimelineItem, edit: FieldEdit<GeoPoint>?) = applyEdit(item) { setLocation(item.assetId, edit) }

    fun setDescription(item: TimelineItem, edit: FieldEdit<String>?) = applyEdit(item) { setDescription(item.assetId, edit) }

    /** Keeps the user's value over the one another app wrote into the file since (INT-002). */
    fun keepEdit(item: TimelineItem, field: MetadataField) = applyEdit(item) { keepEdit(item.assetId, field) }

    fun setRating(item: TimelineItem, rating: Int?) {
        viewModelScope.launch { mark?.setRating(item.assetId, rating) }
    }

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
                TimelinePagingSource(getPage, query)
            }.flow,
        )
    }.cachedIn(viewModelScope)

    private var player: Player? = null

    /** The asset shown in the player, with its player; null shows the still frame. */
    private val _playing = MutableStateFlow<Pair<String, Player>?>(null)
    val playing: StateFlow<Pair<String, Player>?> = _playing.asStateFlow()

    init {
        viewModelScope.launch {
            val index = getPage.indexOf(assetId, query)
            _start.value = index?.let { Start.At(it) } ?: Start.NotFound
            if (index != null && startPositionMs != null) {
                getPage(index, 1, query).firstOrNull()?.takeIf { it.kind == MediaKind.VIDEO }?.let { play(it, startPositionMs) }
            }
        }
    }

    sealed interface Details {
        data object Hidden : Details
        data object Loading : Details
        data class Shown(val result: MediaDetailsResult) : Details
    }

    private val _details = MutableStateFlow<Details>(Details.Hidden)
    val details: StateFlow<Details> = _details.asStateFlow()

    /** Reads the file each time, so a newly granted location permission shows up (PRD MET-001). */
    fun showDetails(item: TimelineItem) {
        val reader = getDetails ?: return
        _details.value = Details.Loading
        viewModelScope.launch { _details.value = Details.Shown(reader(item)) }
    }

    fun hideDetails() {
        _details.value = Details.Hidden
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
