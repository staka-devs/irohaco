package dev.irohaco.core.contracts

import kotlinx.coroutines.flow.Flow

/** Where the local embedding model stands (ARCHITECTURE §10.1). */
enum class ModelState { NOT_INSTALLED, READY, LOADING, LOADED, FAILED }

/** Who asks for inference; when several wait, the earlier entry goes first (ARCHITECTURE §10.1). */
enum class AiPriority { USER_SEARCH, RAG, INTERACTIVE_EDIT, BACKGROUND_INDEX }

/** Local backends only; nothing falls back to a remote service (MOD-003). */
enum class AiBackend { GPU, CPU }

/** The installed embedding model's file, or null while none is installed (M2-02 manages it). */
fun interface ModelLocator {
    suspend fun embeddingModel(): String?
}

/** A loaded embedding engine. Only one is kept at a time; closing it frees its memory. */
interface EmbeddingSession : AutoCloseable {
    val backend: AiBackend

    fun embedText(text: String): FloatArray

    fun embedImage(bytes: ByteArray): FloatArray
}

/** Loads a model on one backend; throws when that backend cannot run it. */
fun interface EmbeddingRuntime {
    fun load(modelPath: String, backend: AiBackend): EmbeddingSession
}

class ModelNotInstalledException : IllegalStateException("No embedding model is installed")

class ModelLoadException(cause: Throwable?) : IllegalStateException("The embedding model failed to load on every local backend", cause)

/** A model the app can fetch and check (MOD-002); its URL points at one fixed revision. */
data class ModelArtifact(val id: String, val displayName: String, val url: String, val sizeBytes: Long, val sha256: String)

/** Where one model stands on this device (M2-02). */
sealed interface ModelInstallState {
    data object NotInstalled : ModelInstallState

    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelInstallState

    /** Stopped by the user or by the system with part of the file kept; a download resumes from there. */
    data class Paused(val downloadedBytes: Long, val totalBytes: Long) : ModelInstallState

    data object Verifying : ModelInstallState

    data class Installed(val path: String) : ModelInstallState

    data class Failed(val reason: Reason) : ModelInstallState {
        enum class Reason { DOWNLOAD, DIGEST_MISMATCH, UNKNOWN_MODEL, STORAGE }
    }
}

/** Model files in the app's own storage. Paths are plain file paths. */
interface ModelStorage {
    /** The checked file of [id], or null while it is not installed. */
    fun installed(id: String): String?

    /** Where a download of [id] lands before it is checked. */
    fun partial(id: String): String

    /** How much of [id]'s download is kept, 0 when none. */
    fun partialBytes(id: String): Long

    /** Copies a file the user picked into storage, unchecked, and returns its path. */
    suspend fun copyIn(sourceUri: String): String

    suspend fun sha256(path: String): String

    /** Moves a checked file into place as [id]'s model and returns the new path. */
    suspend fun promote(path: String, id: String): String

    suspend fun discard(path: String)

    suspend fun delete(id: String)
}

/** Fetches a model file over HTTPS (MOD-002). */
interface ModelDownloader {
    /**
     * Appends to [destination] from its current length until the whole file is there, so a paused or
     * dropped download resumes; [onProgress] gets the bytes so far and the total. Cancelling leaves the
     * part already written. Throws [java.io.IOException] when the network or the server fails.
     */
    suspend fun fetch(artifact: ModelArtifact, destination: String, onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit)
}

/** The models that indexes and searches refer to (`model_profiles`). */
fun interface ModelProfiles {
    suspend fun register(artifact: ModelArtifact)
}
