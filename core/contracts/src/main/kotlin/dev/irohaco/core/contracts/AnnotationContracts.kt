package dev.irohaco.core.contracts

import kotlinx.coroutines.flow.Flow

/** `semantic_annotations.annotation_kind` (PRD §7). AI_CAPTION is written by a model, the others by the user. */
enum class AnnotationKind { CONTEXT, VISUAL_DESCRIPTION, KEYWORDS, AI_CAPTION }

/** Who wrote a revision (ANN-003); an AI revision names its model. */
enum class AnnotationAuthor { USER, AI }

/** One version of a memo's text; revisions are only ever added (ANN-003). */
data class AnnotationRevision(
    val revisionNo: Int,
    val author: AnnotationAuthor,
    val modelProfileId: String?,
    val text: String,
    val modifiedAt: Long,
)

/** A search memo on a photo or video, with its latest revision (ANN-001). */
data class Annotation(
    val annotationId: String,
    val kind: AnnotationKind,
    val searchable: Boolean,
    val createdAt: Long,
    val current: AnnotationRevision,
    /** The scene of a video the memo is on, from start until end in ms; null for a memo on the whole file (#155). */
    val scene: LongRange? = null,
)

/**
 * Search memos in `semantic_annotations` and their revisions, and `annotation_fts`, a derived full-text
 * index over the searchable ones' current text (M2-11).
 */
interface AnnotationStore {
    /** The memos on [assetId] and, for a video, on its scenes. */
    fun annotations(assetId: String): Flow<List<Annotation>>

    suspend fun create(annotationId: String, assetId: String, kind: AnnotationKind, text: String, author: AnnotationAuthor, modelProfileId: String?, now: Long)

    /**
     * A memo on the indexed scene of video [assetId] that holds [atMs], the shortest when qualities overlap.
     * False when the video has no indexed scene there yet; nothing is written then.
     */
    suspend fun createOnScene(annotationId: String, assetId: String, atMs: Long, kind: AnnotationKind, text: String, author: AnnotationAuthor, modelProfileId: String?, now: Long): Boolean

    /** Adds a revision; the full-text index follows the new text. */
    suspend fun revise(annotationId: String, text: String, author: AnnotationAuthor, modelProfileId: String?, now: Long)

    /** A memo left out of search keeps its text and history; it leaves the full-text index and vector search. */
    suspend fun setSearchable(annotationId: String, searchable: Boolean)

    /** Every revision, newest first. */
    suspend fun history(annotationId: String): List<AnnotationRevision>

    /** Removes the memo, its history, its vectors and its full-text entry. */
    suspend fun delete(annotationId: String)
}
