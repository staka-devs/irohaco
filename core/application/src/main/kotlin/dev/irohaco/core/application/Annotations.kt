package dev.irohaco.core.application

import dev.irohaco.core.contracts.Annotation
import dev.irohaco.core.contracts.AnnotationAuthor
import dev.irohaco.core.contracts.AnnotationKind
import dev.irohaco.core.contracts.AnnotationRevision
import dev.irohaco.core.contracts.AnnotationStore
import dev.irohaco.core.contracts.IndexSource
import dev.irohaco.core.contracts.MediaIndexStore
import dev.irohaco.core.contracts.ModelLoadException
import dev.irohaco.core.contracts.ModelNotInstalledException
import kotlinx.coroutines.flow.Flow

/**
 * Search memos the user writes on photos and videos (ANN-001〜003). Every edit is a new revision;
 * [onChanged] then refreshes that memo's vectors only, never the photo's (AC-007).
 */
class ManageAnnotations(
    private val store: AnnotationStore,
    private val newId: () -> String,
    private val now: () -> Long,
    private val onChanged: suspend (annotationId: String) -> Unit,
) {
    fun annotations(assetId: String): Flow<List<Annotation>> = store.annotations(assetId)

    suspend fun add(assetId: String, kind: AnnotationKind, text: String): String {
        require(text.isNotBlank()) { "A memo has text" }
        return newId().also {
            store.create(it, assetId, kind, text.trim(), AnnotationAuthor.USER, null, now())
            onChanged(it)
        }
    }

    suspend fun edit(annotationId: String, text: String) {
        require(text.isNotBlank()) { "A memo has text" }
        store.revise(annotationId, text.trim(), AnnotationAuthor.USER, null, now())
        onChanged(annotationId)
    }

    suspend fun setSearchable(annotationId: String, searchable: Boolean) {
        store.setSearchable(annotationId, searchable)
        if (searchable) onChanged(annotationId)
    }

    suspend fun history(annotationId: String): List<AnnotationRevision> = store.history(annotationId)

    suspend fun delete(annotationId: String) = store.delete(annotationId)
}

/**
 * Embeds one memo's current text into every space the local model can search, right after it is
 * written (AC-007). Without a model or a space there is nothing to do; the next index catches up.
 */
class EmbedMemo(
    private val media: MediaIndexStore,
    private val meaning: SearchByMeaning,
    /** Runs the model at the priority of an edit the user is waiting on. */
    private val embedText: suspend (String) -> FloatArray,
    private val newId: () -> String,
    private val now: () -> Long,
) {
    suspend operator fun invoke(annotationId: String) {
        val item = media.memoItem(annotationId) ?: return
        val text = (media.source(item.targetId) as? IndexSource.Text)?.text ?: return
        val vectors = mutableMapOf<String?, FloatArray>()
        try {
            for (space in meaning.searchableSpaces()) {
                val vector = vectors.getOrPut(space.corpusPrefix) { embedText((space.corpusPrefix ?: "") + text) }
                media.putVector(space.spaceId, item, newId(), vector, now())
            }
        } catch (e: ModelNotInstalledException) {
            // No model yet: indexing embeds the memo once there is one.
        } catch (e: ModelLoadException) {
            // The model failed to load; the next index tries again.
        }
    }
}
