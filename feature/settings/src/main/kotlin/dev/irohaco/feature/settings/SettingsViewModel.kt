package dev.irohaco.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.irohaco.core.application.AiBackendChoice
import dev.irohaco.core.application.AskLibrary
import dev.irohaco.core.application.BrowseLibrary
import dev.irohaco.core.application.ExportBackup
import dev.irohaco.core.application.RestoreBackup
import dev.irohaco.core.application.IndexJobs
import dev.irohaco.core.application.ManageEgress
import dev.irohaco.core.application.ManageModels
import dev.irohaco.core.application.PlanMediaIndex
import dev.irohaco.core.application.RemoteAnswering
import dev.irohaco.core.application.RemoteEmbedding
import dev.irohaco.core.application.ThemeColorSettings
import dev.irohaco.core.contracts.AiBackend
import dev.irohaco.core.contracts.BackupUnreadableException
import dev.irohaco.core.contracts.EgressGrant
import dev.irohaco.core.contracts.EmbeddingModelSettings
import dev.irohaco.core.contracts.EmbeddingSpace
import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.GpuCheck
import dev.irohaco.core.contracts.IndexSettings
import dev.irohaco.core.contracts.LibraryFolder
import dev.irohaco.core.contracts.MapSettings
import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.ModelMarks
import dev.irohaco.core.contracts.ModelInstallState
import dev.irohaco.core.contracts.ModelCapability
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.ModelOptions
import dev.irohaco.core.contracts.RemoteModel
import dev.irohaco.core.contracts.RemoteProtocol
import dev.irohaco.core.contracts.TaskType
import dev.irohaco.core.contracts.ShareLocationPolicy
import dev.irohaco.core.contracts.TimelineSettings
import dev.irohaco.core.contracts.SharingSettings
import dev.irohaco.core.contracts.SpaceState
import dev.irohaco.core.contracts.VideoQuality
import dev.irohaco.core.domain.ThemeColor
import dev.irohaco.core.domain.ThemeContrast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
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
    private val aiBackends: AiBackendChoice? = null,
    /** The model that answers questions from search results (RAG-001); the user picks among those installed. */
    private val ask: AskLibrary? = null,
    private val timeline: TimelineSettings? = null,
    private val egress: ManageEgress? = null,
    /** The remote model the next index uses; null for the model on this device (ADR-0032). */
    private val embeddingSettings: EmbeddingModelSettings? = null,
    /** Writes the encrypted backup (UC-BACKUP-01). */
    private val exportBackup: ExportBackup? = null,
    /** Opens a backup, matches its files here and applies it once the user agrees (UC-RESTORE-01). */
    private val restoreBackup: RestoreBackup? = null,
    /** The colors and pictures the user gave the models' marks. */
    private val marks: ModelMarks? = null,
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

    private var planning: Job? = null
    private val _preparing = MutableStateFlow(false)
    private val _startFailed = MutableStateFlow(false)

    /** True while the files to index are listed, before the job exists. */
    val preparing: StateFlow<Boolean> = _preparing

    /** The last start could not list the files or start the job; the user can try again. */
    val startFailed: StateFlow<Boolean> = _startFailed

    /** An index waiting for the user to agree to what it sends to a remote model (ARCHITECTURE §9.2). */
    data class PendingSend(val folders: Set<String>?, val quality: VideoQuality, val outgoing: PlanMediaIndex.Outgoing)

    private val _pendingSend = MutableStateFlow<PendingSend?>(null)

    /** Shown as a confirmation; nothing leaves the device until the user agrees. Not kept anywhere. */
    val pendingSend: StateFlow<PendingSend?> = _pendingSend

    /**
     * Plans an index of [folders] (relative paths; null for every folder), videos at [quality], and runs
     * it. With a remote model it first counts what would be sent and waits for [confirmSend].
     */
    fun startIndex(folders: Set<String>?, quality: VideoQuality) = plan {
        val outgoing = planMediaIndex?.outgoing(folders, quality)
        if (outgoing != null) {
            _pendingSend.value = PendingSend(folders, quality, outgoing)
        } else {
            planMediaIndex?.invoke(folders, quality)?.let(runIndexJob)
        }
    }

    /** The user agreed to send what [pendingSend] showed. */
    fun confirmSend() {
        val pending = _pendingSend.value ?: return
        _pendingSend.value = null
        plan { planMediaIndex?.invoke(pending.folders, pending.quality)?.let(runIndexJob) }
    }

    fun dismissSend() {
        _pendingSend.value = null
    }

    private fun plan(block: suspend () -> Unit) {
        if (planning?.isActive == true) return
        _startFailed.value = false
        planning = viewModelScope.launch {
            _preparing.value = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _startFailed.value = true // also when the system refuses the foreground service
            } finally {
                _preparing.value = false
            }
        }
    }

    /** Stops listing the files; nothing is kept, as no job was made yet. */
    fun cancelPreparing() {
        planning?.cancel()
    }

    fun dismissStartFailed() {
        _startFailed.value = false
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

    fun pauseDownload(id: String) {
        models?.pause(id)
    }

    fun cancelDownload(id: String) {
        viewModelScope.launch { models?.cancel(id) }
    }

    fun importModel(uri: String) {
        viewModelScope.launch { _importResult.value = models?.import(uri) }
    }

    fun deleteModel(id: String) {
        viewModelScope.launch { models?.delete(id) }
    }

    /** The GPU or the CPU the user chose, null before they choose (#169). */
    val aiBackend: StateFlow<AiBackend?> = (aiBackends?.backend ?: flowOf(null)).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val gpuCheck: StateFlow<GpuCheck?> = (aiBackends?.gpuCheck ?: flowOf(null)).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectAiBackend(backend: AiBackend) {
        viewModelScope.launch { aiBackends?.select(backend) }
    }

    val answerModels: List<ModelArtifact> = ask?.catalog.orEmpty()

    /** Remote models whose connection test found what answering needs (ADR-0032). */
    val remoteAnswerModels: StateFlow<List<RemoteModel>> = (egress?.models() ?: flowOf(emptyList()))
        .map { models -> models.filter { it.capabilities.containsAll(RemoteAnswering.NEEDED) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The answer model in use: the picked one while installed or registered, else the first installed one; null without any. */
    val answerModel: StateFlow<String?> = combine(ask?.chosen ?: flowOf(null), modelStates, remoteAnswerModels) { picked, states, remote ->
        val installed = answerModels.map { it.id }.filter { states[it] is ModelInstallState.Installed }
        picked?.takeIf { it in installed || remote.any { model -> model.profileId == it } } ?: installed.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setMarkColor(id: String, color: Long?) {
        viewModelScope.launch { marks?.setColor(id, color) }
    }

    /** A picture the app cannot read is left off; the mark keeps its letter. */
    fun setMarkImage(id: String, uri: String?) {
        viewModelScope.launch { runCatching { marks?.setImage(id, uri) } }
    }

    fun selectAnswerModel(id: String) {
        viewModelScope.launch {
            grantFor(RemoteRole.ANSWER, id)
            ask?.choose(id)
        }
    }

    /** Remote models that can index photos and search them by text (ADR-0032). */
    val remoteEmbeddingModels: StateFlow<List<RemoteModel>> = (egress?.models() ?: flowOf(emptyList()))
        .map { models -> models.filter { RemoteEmbedding.usable(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The remote model the next index uses while it is registered; null for the model on this device. */
    val embeddingModel: StateFlow<String?> = combine(embeddingSettings?.embeddingModel ?: flowOf(null), remoteEmbeddingModels) { picked, remote ->
        picked?.takeIf { id -> remote.any { it.profileId == id } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectEmbeddingModel(id: String?) {
        viewModelScope.launch {
            id?.let { grantFor(RemoteRole.INDEX, it) }
            embeddingSettings?.setEmbeddingModel(id)
        }
    }

    /** Picking a remote model is the consent to send it what its job needs (ADR-0032); a model on the device needs none. */
    private suspend fun grantFor(role: RemoteRole, profileId: String) {
        val manage = egress ?: return
        val connectionId = manage.models().first().firstOrNull { it.profileId == profileId }?.connectionId ?: return
        role.tasks.forEach { task -> ManageEgress.offered.getValue(task).forEach { manage.set(EgressGrant(connectionId, task, it), true) } }
    }

    /**
     * Adds a remote model for [role] in one go: the connection when it is new, then the model's test, then
     * the model is put to use. A new connection is taken back when the test fails, so retrying adds no copies.
     */
    fun addRemoteModel(role: RemoteRole, connectionId: String?, newConnection: NewConnection?, modelId: String, displayName: String) {
        val manage = egress ?: return
        _modelTest.value = ModelTest.Running
        viewModelScope.launch {
            var created: String? = null
            _modelTest.value = try {
                val id = connectionId ?: manage.add(newConnection!!.protocol, newConnection.baseUrl, newConnection.apiKey, newConnection.name).also { created = it }
                val capabilities = manage.addModel(id, modelId, displayName)
                val model = manage.models().first().last { it.connectionId == id && it.modelId == modelId.trim() }
                val usable = when (role) {
                    RemoteRole.ANSWER -> capabilities.containsAll(RemoteAnswering.NEEDED)
                    RemoteRole.INDEX -> RemoteEmbedding.usable(model)
                }
                if (usable) {
                    when (role) {
                        RemoteRole.ANSWER -> selectAnswerModel(model.profileId)
                        RemoteRole.INDEX -> selectEmbeddingModel(model.profileId)
                    }
                }
                ModelTest.Added(capabilities, usable)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                created?.let { manage.remove(it) }
                ModelTest.Failed(e)
            }
        }
    }

    private val _exportTarget = MutableStateFlow<String?>(null)

    /** The file the user picked for a backup; the passphrase is asked for next. */
    val exportTarget: StateFlow<String?> = _exportTarget

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState

    fun pickExportTarget(uri: String) {
        _exportTarget.value = uri
    }

    /** Exports to [target]; the passphrase is wiped once the export is over. */
    fun export(target: String, passphrase: CharArray) {
        val export = exportBackup ?: return
        _exportTarget.value = null
        _exportState.value = ExportState.Running
        viewModelScope.launch {
            _exportState.value = try {
                ExportState.Done(export(target, passphrase).entries.values.sumOf { it.size })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ExportState.Failed
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    fun dismissExport() {
        _exportTarget.value = null
        if (_exportState.value != ExportState.Running) _exportState.value = ExportState.Idle
    }

    private val _restoreSource = MutableStateFlow<String?>(null)

    /** The backup the user picked; its passphrase is asked for next. */
    val restoreSource: StateFlow<String?> = _restoreSource

    private val _restoreState = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restoreState: StateFlow<RestoreState> = _restoreState

    /** Held between the preview and the user's answer; dropped on dismiss. */
    private var preview: RestoreBackup.Preview? = null

    fun pickRestoreSource(uri: String) {
        _restoreSource.value = uri
    }

    /** Opens and checks [source] and matches its files; the passphrase is wiped once that is over. */
    fun openRestore(source: String, passphrase: CharArray) {
        val restore = restoreBackup ?: return
        _restoreSource.value = null
        _restoreState.value = RestoreState.Opening
        viewModelScope.launch {
            _restoreState.value = try {
                val opened = restore.preview(source, passphrase)
                preview = opened
                with(opened.matches) { RestoreState.Preview(opened.records, matched, needsReview, missing) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupUnreadableException) {
                RestoreState.Failed(unreadable = true)
            } catch (e: Exception) {
                RestoreState.Failed(unreadable = false)
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    fun applyRestore() {
        val restore = restoreBackup ?: return
        val opened = preview ?: return
        preview = null
        _restoreState.value = RestoreState.Applying
        viewModelScope.launch {
            _restoreState.value = try {
                restore.apply(opened).let { RestoreState.Done(it.added.values.sum(), it.skipped) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RestoreState.Failed(unreadable = false)
            }
        }
    }

    fun dismissRestore() {
        _restoreSource.value = null
        preview = null
        if (_restoreState.value != RestoreState.Opening && _restoreState.value != RestoreState.Applying) _restoreState.value = RestoreState.Idle
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

    /** Whether the timeline pins photos and videos that have a place. */
    val locationPin: StateFlow<Boolean> = (timeline?.locationPin ?: flowOf(true)).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setLocationPin(shown: Boolean) {
        viewModelScope.launch { timeline?.setLocationPin(shown) }
    }

    /** The remote connections and what each may get (API-007); all off until the user turns one on. */
    val remoteConnections: StateFlow<List<RemoteConnection>> =
        (egress?.connections() ?: flowOf(emptyList())).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val egressGrants: StateFlow<Set<EgressGrant>> =
        (egress?.grants() ?: flowOf(emptySet())).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setEgress(grant: EgressGrant, allowed: Boolean) {
        viewModelScope.launch { egress?.set(grant, allowed) }
    }

    private val _connectionFailed = MutableStateFlow(false)

    /** The last connection could not be added, such as when the key could not be stored; the screen says so. */
    val connectionFailed: StateFlow<Boolean> = _connectionFailed

    fun addConnection(protocol: RemoteProtocol, baseUrl: String, apiKey: String) {
        viewModelScope.launch {
            try {
                egress?.add(protocol, baseUrl, apiKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _connectionFailed.value = true
            }
        }
    }

    fun dismissConnectionFailed() {
        _connectionFailed.value = false
    }

    /** A blank [name] goes back to the suggested one. */
    fun renameConnection(id: String, name: String) {
        viewModelScope.launch { egress?.rename(id, name) }
    }

    fun removeConnection(id: String) {
        viewModelScope.launch { egress?.remove(id) }
    }

    val remoteModels: StateFlow<List<RemoteModel>> =
        (egress?.models() ?: flowOf(emptyList())).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The connection test while it runs, then what it found or why it failed; null when none was run. */
    private val _modelTest = MutableStateFlow<ModelTest?>(null)
    val modelTest: StateFlow<ModelTest?> = _modelTest

    sealed interface ModelTest {
        data object Running : ModelTest

        /** [usable] says whether the model can do the job it was added for. */
        data class Added(val capabilities: Set<ModelCapability>, val usable: Boolean = true) : ModelTest

        data class Failed(val cause: Throwable) : ModelTest
    }

    fun addModel(connectionId: String, modelId: String, displayName: String) {
        val manage = egress ?: return
        _modelTest.value = ModelTest.Running
        viewModelScope.launch {
            _modelTest.value = try {
                ModelTest.Added(manage.addModel(connectionId, modelId, displayName))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ModelTest.Failed(e)
            }
        }
    }

    fun dismissModelTest() {
        _modelTest.value = null
    }

    fun removeModel(profileId: String) {
        viewModelScope.launch { egress?.removeModel(profileId) }
    }

    fun setModelParameters(profileId: String, values: Map<String, String>) {
        viewModelScope.launch { egress?.setParameters(profileId, values) }
    }

    /** models.dev being read for one model, then what it says; null until the user asks. */
    private val _optionsLookup = MutableStateFlow<OptionsLookup?>(null)
    val optionsLookup: StateFlow<OptionsLookup?> = _optionsLookup

    sealed interface OptionsLookup {
        data object Running : OptionsLookup

        /** [options] is null when the model is not listed. */
        data class Found(val options: ModelOptions?) : OptionsLookup

        data class Failed(val cause: Throwable) : OptionsLookup
    }

    fun lookUpOptions(model: RemoteModel) {
        val manage = egress ?: return
        _optionsLookup.value = OptionsLookup.Running
        viewModelScope.launch {
            _optionsLookup.value = try {
                OptionsLookup.Found(manage.options(model))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OptionsLookup.Failed(e)
            }
        }
    }

    fun dismissOptions() {
        _optionsLookup.value = null
    }

    fun selectShareLocationPolicy(policy: ShareLocationPolicy) {
        viewModelScope.launch { sharing?.setShareLocationPolicy(policy) }
    }
}

private val LIVE = setOf(SpaceState.BUILDING, SpaceState.VERIFYING, SpaceState.READY)

/** What a remote model is added for; each job sends its own kinds of content (ManageEgress.offered). */
enum class RemoteRole(val tasks: List<TaskType>) {
    ANSWER(listOf(TaskType.RAG)),
    INDEX(listOf(TaskType.IMAGE_INDEX, TaskType.VIDEO_INDEX, TaskType.SEARCH_QUERY)),
}

/** A connection typed in while adding a model; the key goes straight to the Keystore. */
class NewConnection(val protocol: RemoteProtocol, val baseUrl: String, val apiKey: String, val name: String = "")
