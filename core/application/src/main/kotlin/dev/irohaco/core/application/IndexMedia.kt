package dev.irohaco.core.application

import dev.irohaco.core.contracts.EmbeddingSpaceSpec
import dev.irohaco.core.contracts.IndexImageReader
import dev.irohaco.core.contracts.IndexItem
import dev.irohaco.core.contracts.IndexItemProcessor
import dev.irohaco.core.contracts.IndexItemSkipped
import dev.irohaco.core.contracts.IndexJobKind
import dev.irohaco.core.contracts.IndexSource
import dev.irohaco.core.contracts.MediaIndexStore
import dev.irohaco.core.contracts.TextIndexStore
import dev.irohaco.core.contracts.TextMatch
import dev.irohaco.core.contracts.TextRecognizer
import dev.irohaco.core.contracts.VideoQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

/** The space photos go into with the local model (ARCHITECTURE §4.1); queries carry the model card's prefix. */
object ImageSpaces {
    val LOCAL = EmbeddingSpaceSpec(
        modelProfileId = ModelCatalog.EMBEDDING.id,
        modelDigest = ModelCatalog.EMBEDDING.sha256,
        dimension = 768,
        normalization = "L2",
        queryPrefix = "task: search result | query: ",
        corpusPrefix = null,
        preprocessingSignature = "image:mediastore-thumbnail-768:jpeg-90",
    )

    /** Any change to the model file, size, prefixes or preprocessing gives another space (ADR-0003). */
    fun signature(spec: EmbeddingSpaceSpec): String {
        val text = with(spec) { listOf(modelProfileId, modelDigest, dimension, normalization, queryPrefix, corpusPrefix, preprocessingSignature).joinToString("\u0000") }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Plans an index the user asked for (IDX-001, IDX-003, IDX-004): every available image, and frames of
 * every available video at [VideoQuality], in the chosen folders, that have no vector of their current
 * revision yet. The first job of a space is INITIAL.
 */
class PlanMediaIndex(
    private val store: MediaIndexStore,
    private val jobs: IndexJobs,
    private val newId: () -> String,
    private val now: () -> Long,
    private val spec: EmbeddingSpaceSpec = ImageSpaces.LOCAL,
) {
    /** Returns the planned job's id; folders are relative paths, null meaning every folder. */
    suspend operator fun invoke(folders: Set<String>? = null, quality: VideoQuality = VideoQuality.BALANCED): String {
        val signature = ImageSpaces.signature(spec)
        val spaceId = store.ensureSpace(newId(), spec, signature)
        val kind = if (store.hasVectors(spaceId)) IndexJobKind.INCREMENTAL else IndexJobKind.INITIAL
        return jobs.start(spaceId, kind, store.plan(spaceId, folders, quality, now()), requestedByUser = true)
    }

    /** Videos not covered to their end, for the low coverage notice. */
    fun lowCoverageVideos(): Flow<Int> = store.lowCoverageVideos()
}

/**
 * Embeds one image or video frame and reads its text (INDEX_STATE_MACHINE.md §4 steps 3–4, IDX-003).
 * A file that is gone or changed is skipped, both before and after the model runs, so a vector or text
 * never lands on a newer revision. Text that cannot be read leaves the vector in place.
 */
class EmbedMediaItem(
    private val store: MediaIndexStore,
    private val reader: IndexImageReader,
    private val recognizer: TextRecognizer?,
    private val texts: TextIndexStore?,
    /** Turns a search memo into a vector of the same space (the local model has no corpus prefix). */
    private val embedText: suspend (String) -> FloatArray,
    /** Runs the model at background priority; searches by the user go first. */
    private val embed: suspend (ByteArray) -> FloatArray,
) : IndexItemProcessor {
    override suspend fun embed(item: IndexItem): FloatArray {
        val bytes = when (val source = current(item)) {
            is IndexSource.Image -> reader.read(source.contentUri)
            is IndexSource.Frame -> reader.readFrame(source.contentUri, source.timestampMs)
            is IndexSource.Text -> return embedText(source.text).also { current(item) }
        }
        val vector = embed(bytes)
        val text = recognizer?.let { recognize(it, bytes) }
        current(item)
        if (text != null && recognizer != null) texts?.recordOcr(item.targetId, item.sourceRevisionDigest, text, recognizer.engineRevision)
        return vector
    }

    private suspend fun recognize(recognizer: TextRecognizer, bytes: ByteArray): String? = try {
        recognizer.recognize(bytes)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null // the vector still counts; the text is read again when the file is indexed next
    }

    private suspend fun current(item: IndexItem) = store.source(item.targetId)
        ?.takeIf { it.revisionDigest == item.sourceRevisionDigest }
        ?: throw IndexItemSkipped("${item.targetId} is gone or has changed")
}

/** Finds files by the text in them (IDX-003); the search screen (M2-09) shows the matches. */
class SearchText(private val store: TextIndexStore) {
    suspend operator fun invoke(query: String): List<TextMatch> = if (query.isBlank()) emptyList() else store.search(query.trim(), LIMIT)

    private companion object {
        const val LIMIT = 200
    }
}
