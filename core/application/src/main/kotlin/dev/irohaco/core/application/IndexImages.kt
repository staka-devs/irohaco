package dev.irohaco.core.application

import dev.irohaco.core.contracts.EmbeddingSpaceSpec
import dev.irohaco.core.contracts.ImageIndexStore
import dev.irohaco.core.contracts.IndexImageReader
import dev.irohaco.core.contracts.IndexItem
import dev.irohaco.core.contracts.IndexItemProcessor
import dev.irohaco.core.contracts.IndexItemSkipped
import dev.irohaco.core.contracts.IndexJobKind
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
 * Plans an image index the user asked for (IDX-001, IDX-003): every available image in the chosen
 * folders that has no vector of its current revision yet. The first job of a space is INITIAL.
 */
class PlanImageIndex(
    private val store: ImageIndexStore,
    private val jobs: IndexJobs,
    private val newId: () -> String,
    private val spec: EmbeddingSpaceSpec = ImageSpaces.LOCAL,
) {
    /** Returns the planned job's id; folders are relative paths, null meaning every folder. */
    suspend operator fun invoke(folders: Set<String>? = null): String {
        val spaceId = store.ensureSpace(newId(), spec, ImageSpaces.signature(spec))
        val kind = if (store.hasVectors(spaceId)) IndexJobKind.INCREMENTAL else IndexJobKind.INITIAL
        return jobs.start(spaceId, kind, store.planImages(spaceId, folders), requestedByUser = true)
    }
}

/**
 * Embeds one image target (INDEX_STATE_MACHINE.md §4 steps 3–4). A file that is gone or changed is
 * skipped, both before and after the model runs, so a vector never lands on a newer revision.
 */
class EmbedImageItem(
    private val store: ImageIndexStore,
    private val reader: IndexImageReader,
    /** Runs the model at background priority; searches by the user go first. */
    private val embed: suspend (ByteArray) -> FloatArray,
) : IndexItemProcessor {
    override suspend fun embed(item: IndexItem): FloatArray {
        val source = current(item)
        val vector = embed(reader.read(source.contentUri))
        current(item)
        return vector
    }

    private suspend fun current(item: IndexItem) = store.imageSource(item.targetId)
        ?.takeIf { it.revisionDigest == item.sourceRevisionDigest }
        ?: throw IndexItemSkipped("${item.targetId} is gone or has changed")
}
