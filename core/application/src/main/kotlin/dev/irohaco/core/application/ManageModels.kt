package dev.irohaco.core.application

import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.ModelDownloader
import dev.irohaco.core.contracts.ModelInstallState
import dev.irohaco.core.contracts.ModelInstallState.Failed.Reason
import dev.irohaco.core.contracts.ModelLocator
import dev.irohaco.core.contracts.ModelProfiles
import dev.irohaco.core.contracts.ModelStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** The models the app knows (MOD-002), each pinned to one revision and checked by its SHA-256. */
object ModelCatalog {
    val EMBEDDING = ModelArtifact(
        id = "embeddinggemma-2-text-vision-440m",
        displayName = "EmbeddingGemma 2 Text-Vision 440M",
        url = "https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/" +
            "e301f74d5551b0c2641bd5cb4652a76239d5c5f8/embeddinggemma-2-text-vision-440m.litertlm",
        sizeBytes = 387_710_976,
        sha256 = "92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0",
    )

    // shortcut: the embedding model only; Gemma 4 joins with RAG in M3.
    val ALL = listOf(EMBEDDING)
}

/**
 * Downloads, imports, checks and deletes model files (MOD-001, M2-02). A file is usable only after
 * its SHA-256 matches the catalog; anything else is removed and reported as failed. A download can be
 * paused, resumed from the part already written, or cancelled, which removes that part.
 */
class ManageModels(
    private val storage: ModelStorage,
    private val downloader: ModelDownloader,
    private val profiles: ModelProfiles,
    val catalog: List<ModelArtifact> = ModelCatalog.ALL,
) {
    private val _states = MutableStateFlow(catalog.associate { it.id to (ModelInstallState.NotInstalled as ModelInstallState) })
    val states: StateFlow<Map<String, ModelInstallState>> = _states.asStateFlow()

    /** Reads the embedding model's checked file; the AI resource manager loads it from here. */
    val embeddingLocator = ModelLocator { storage.installed(ModelCatalog.EMBEDDING.id) }

    /** Running downloads, so a pause or a cancel can stop them; and why each one was stopped. */
    private val running = ConcurrentHashMap<String, Job>()
    private val stopping = ConcurrentHashMap<String, Stop>()

    private enum class Stop { PAUSE, CANCEL }

    /** At app start: installed files, and downloads a stopped process left part of, shown as paused. */
    fun refresh() {
        for (artifact in catalog) {
            if (running.containsKey(artifact.id)) continue // still downloading in this process
            val path = storage.installed(artifact.id)
            val kept = storage.partialBytes(artifact.id)
            set(
                artifact,
                when {
                    path != null -> ModelInstallState.Installed(path)
                    kept > 0 -> ModelInstallState.Paused(kept, artifact.sizeBytes)
                    else -> ModelInstallState.NotInstalled
                },
            )
        }
    }

    /**
     * Downloads [id], from the part already written if any, and checks it. Returns at once when it is
     * already downloading. A dropped connection or a server error leaves the part for a retry.
     */
    suspend fun download(id: String) {
        val artifact = artifact(id)
        val job = currentCoroutineContext()[Job]
        if (job != null && running.putIfAbsent(id, job) != null) return
        stopping.remove(id)
        val partial = storage.partial(artifact.id)
        try {
            set(artifact, ModelInstallState.Downloading(storage.partialBytes(artifact.id), artifact.sizeBytes))
            downloader.fetch(artifact, partial) { done, total -> set(artifact, ModelInstallState.Downloading(done, total)) }
            verify(artifact, partial)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { stopped(artifact, partial) }
            throw e
        } catch (e: IOException) {
            set(artifact, ModelInstallState.Failed(Reason.DOWNLOAD))
        } finally {
            running.remove(id, job)
        }
    }

    /** Stops the download and keeps what it wrote; [download] resumes it. */
    fun pause(id: String) {
        stopping[id] = Stop.PAUSE
        running[id]?.cancel()
    }

    /** Stops the download and removes what it wrote. */
    suspend fun cancel(id: String) {
        val artifact = artifact(id)
        stopping[id] = Stop.CANCEL
        val job = running[id]
        if (job != null) {
            job.cancel()
        } else {
            storage.discard(storage.partial(artifact.id))
            set(artifact, ModelInstallState.NotInstalled)
        }
    }

    private suspend fun stopped(artifact: ModelArtifact, partial: String) {
        if (stopping.remove(artifact.id) == Stop.CANCEL) {
            storage.discard(partial)
            set(artifact, ModelInstallState.NotInstalled)
        } else {
            // A pause, or the system stopping the work: the part stays for a resume.
            set(artifact, ModelInstallState.Paused(storage.partialBytes(artifact.id), artifact.sizeBytes))
        }
    }

    /** A file the user picked; it must be one of the catalog's models, told apart by its SHA-256. */
    suspend fun import(sourceUri: String): ModelInstallState {
        val path = try {
            storage.copyIn(sourceUri)
        } catch (e: IOException) {
            return ModelInstallState.Failed(Reason.STORAGE)
        }
        val digest = storage.sha256(path)
        val artifact = catalog.firstOrNull { it.sha256 == digest } ?: run {
            storage.discard(path)
            return ModelInstallState.Failed(Reason.UNKNOWN_MODEL)
        }
        return install(artifact, path)
    }

    suspend fun delete(id: String) {
        val artifact = artifact(id)
        stopping[id] = Stop.CANCEL
        running[id]?.cancel()
        storage.delete(artifact.id)
        set(artifact, ModelInstallState.NotInstalled)
    }

    private suspend fun verify(artifact: ModelArtifact, path: String) {
        set(artifact, ModelInstallState.Verifying)
        if (storage.sha256(path) != artifact.sha256) {
            storage.discard(path)
            set(artifact, ModelInstallState.Failed(Reason.DIGEST_MISMATCH))
            return
        }
        install(artifact, path)
    }

    private suspend fun install(artifact: ModelArtifact, path: String): ModelInstallState {
        val state = try {
            ModelInstallState.Installed(storage.promote(path, artifact.id)).also { profiles.register(artifact) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            storage.discard(path)
            ModelInstallState.Failed(Reason.STORAGE)
        }
        set(artifact, state)
        return state
    }

    private fun artifact(id: String) = catalog.first { it.id == id }

    private fun set(artifact: ModelArtifact, state: ModelInstallState) = _states.update { it + (artifact.id to state) }
}
