package dev.irohaco.core.contracts

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
