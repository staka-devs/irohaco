package dev.irohaco.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.irohaco.core.application.BrowseLibrary
import dev.irohaco.core.application.IndexJobs
import dev.irohaco.core.application.ManageModels
import dev.irohaco.core.application.PlanMediaIndex
import dev.irohaco.core.application.ThemeColorSettings
import dev.irohaco.core.contracts.EmbeddingSpace
import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.IndexSettings
import dev.irohaco.core.contracts.LibraryFolder
import dev.irohaco.core.contracts.MapSettings
import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.ModelInstallState
import dev.irohaco.core.contracts.ShareLocationPolicy
import dev.irohaco.core.contracts.SharingSettings
import dev.irohaco.core.contracts.SpaceState
import dev.irohaco.core.contracts.VideoQuality
import dev.irohaco.core.domain.ThemeColor
import dev.irohaco.core.domain.ThemeContrast
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: ThemeColorSettings,
    private val sharing: SharingSettings? = null,
    private val map: MapSettings? = null,
    private val library: BrowseLibrary? = null,
    private val models: ManageModels? = null,
    /** Starts a model download that outlives this screen, so the file is checked even if the user leaves. */
    private val startDownload: (String) -> Unit = {},
    private val indexJobs: IndexJobs? = null,
    private val planMediaIndex: PlanMediaIndex? = null,
    private val indexSettings: IndexSettings? = null,
    private val spaces: Flow<List<EmbeddingSpace>> = flowOf(emptyList()),
    /** Runs a job in the foreground service, so it carries on after the user leaves this screen. */
    private val runIndexJob: (String) -> Unit = {},
) : ViewModel() {
    /** The job requested last and where it stands (IDX-002, M2-04); null before the first one. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val indexing: StateFlow<IndexingUi?> = (indexJobs?.latest() ?: flowOf(null))
        .flatMapLatest { id ->
            if (id == null || indexJobs == null) {
                flowOf(null)
            } else {
                combine(indexJobs.progress(id).filterNotNull(), indexJobs.failedNames(id), indexJobs.pauseReason) { progress, failed, reason ->
                    IndexingUi(id, progress, failed, reason)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Folders the user can index, the SD cards that are out left aside (IDX-001). */
    val indexFolders: StateFlow<List<LibraryFolder>> = (library?.folders() ?: flowOf(emptyList()))
        .map { folders -> folders.filterNot { it.offline } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Videos whose frames stop before their end (PRD §5.2). */
    /** Indexes changes by itself after a sync (IDX-005). */
    val autoIndex: StateFlow<Boolean> = (indexSettings?.autoIndex ?: flowOf(false))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setAutoIndex(enabled: Boolean) {
        viewModelScope.launch { indexSettings?.setAutoIndex(enabled) }
    }

    /** Files and memos the index lacks or has at an older revision; counted again whenever a job changes state. */
    val pendingCount: StateFlow<Int?> = indexing.map { it?.progress?.state }.distinctUntilChanged()
        .map { planMediaIndex?.pendingCount() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The model file changed: the old index is STALE and none of the new one exists yet (ADR-0003). */
    val needsRebuild: StateFlow<Boolean> = spaces
        .map { list -> list.any { it.state == SpaceState.STALE } && list.none { it.state in LIVE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val lowCoverageVideos: StateFlow<Int> = (planMediaIndex?.lowCoverageVideos() ?: flowOf(0))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Plans an index of [folders] (relative paths; null for every folder), videos at [quality], and runs it. */
    fun startIndex(folders: Set<String>?, quality: VideoQuality) {
        viewModelScope.launch { planMediaIndex?.invoke(folders, quality)?.let(runIndexJob) }
    }

    fun pauseIndex(jobId: String) {
        viewModelScope.launch { indexJobs?.pause(jobId) }
    }

    fun resumeIndex(jobId: String) = runIndexJob(jobId)

    fun cancelIndex(jobId: String) {
        viewModelScope.launch { indexJobs?.cancel(jobId) }
    }

    fun retryIndex(jobId: String) {
        viewModelScope.launch {
            indexJobs?.retryFailed(jobId)
            runIndexJob(jobId)
        }
    }

    /** The models the app knows, and where each stands (M2-02). */
    val modelCatalog: List<ModelArtifact> = models?.catalog.orEmpty()

    val modelStates: StateFlow<Map<String, ModelInstallState>> = models?.states ?: MutableStateFlow(emptyMap())

    private val _importResult = MutableStateFlow<ModelInstallState?>(null)

    /** What the last import ended in, for one line under the import button. */
    val importResult: StateFlow<ModelInstallState?> = _importResult

    fun downloadModel(id: String) = startDownload(id)

    fun importModel(uri: String) {
        viewModelScope.launch { _importResult.value = models?.import(uri) }
    }

    fun deleteModel(id: String) {
        viewModelScope.launch { models?.delete(id) }
    }

    /** Folders hidden from the Photos tab, by path, for the Library page (GAL-002). */
    val excludedFolders: StateFlow<List<FolderRef>> = (library?.excludedFolders() ?: flowOf(emptySet()))
        .map { folders -> folders.sortedWith(compareBy({ it.volumeId }, { it.relativePath })) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun showFolder(folder: FolderRef) {
        viewModelScope.launch { library?.setExcluded(folder, false) }
    }

    val themeColor: StateFlow<ThemeColor> =
        settings.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeColor.Device)

    val contrast: StateFlow<ThemeContrast> =
        settings.observeContrast().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeContrast.DEVICE)

    /** What to do with a shared photo's GPS by default (PRD MET-003). */
    val shareLocationPolicy: StateFlow<ShareLocationPolicy> =
        (sharing?.shareLocationPolicy ?: flowOf(ShareLocationPolicy.ASK))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShareLocationPolicy.ASK)

    fun select(color: ThemeColor) {
        viewModelScope.launch { settings.set(color) }
    }

    fun selectContrast(contrast: ThemeContrast) {
        viewModelScope.launch { settings.setContrast(contrast) }
    }

    /** Whether the online map is allowed (ADR-0011); off, the map asks again before it next loads. */
    val mapAllowed: StateFlow<Boolean> = (map?.allowed ?: flowOf(false)).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setMapAllowed(allowed: Boolean) {
        viewModelScope.launch { map?.setAllowed(allowed) }
    }

    fun selectShareLocationPolicy(policy: ShareLocationPolicy) {
        viewModelScope.launch { sharing?.setShareLocationPolicy(policy) }
    }
}

private val LIVE = setOf(SpaceState.BUILDING, SpaceState.VERIFYING, SpaceState.READY)
