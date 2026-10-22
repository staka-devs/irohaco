package dev.irohaco.core.application

import dev.irohaco.core.contracts.DownloadStatus
import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.ModelDownloader
import dev.irohaco.core.contracts.ModelInstallState
import dev.irohaco.core.contracts.ModelInstallState.Failed.Reason
import dev.irohaco.core.contracts.ModelLocator
import dev.irohaco.core.contracts.ModelProfiles
import dev.irohaco.core.contracts.ModelStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

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
 * its SHA-256 matches the catalog; anything else is removed and reported as failed.
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

    /** At app start: installed files, and downloads the system kept running while the app was closed. */
    suspend fun refresh() = coroutineScope {
        for (artifact in catalog) {
            val path = storage.installed(artifact.id)
            if (path != null) set(artifact, ModelInstallState.Installed(path)) else launch { follow(artifact) }
        }
    }

    /** Starts the download and follows it until the file is checked. */
    suspend fun download(id: String) {
        val artifact = artifact(id)
        set(artifact, ModelInstallState.Downloading(0, artifact.sizeBytes))
        downloader.start(artifact, storage.partial(artifact.id))
        follow(artifact)
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
        downloader.cancel(artifact)
        storage.delete(artifact.id)
        set(artifact, ModelInstallState.NotInstalled)
    }

    private suspend fun follow(artifact: ModelArtifact) {
        downloader.status(artifact).collect { status ->
            when (status) {
                DownloadStatus.None -> if (storage.installed(artifact.id) == null) set(artifact, ModelInstallState.NotInstalled)
                is DownloadStatus.Running -> set(artifact, ModelInstallState.Downloading(status.downloadedBytes, status.totalBytes))
                is DownloadStatus.Done -> verify(artifact, status.path)
                DownloadStatus.Failed -> set(artifact, ModelInstallState.Failed(Reason.DOWNLOAD))
            }
        }
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
