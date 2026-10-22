package dev.irohaco.core.application

import dev.irohaco.core.contracts.EmbeddingSpace
import dev.irohaco.core.contracts.EmbeddingSpaceSpec
import dev.irohaco.core.contracts.IndexItem
import dev.irohaco.core.contracts.IndexJobKind
import dev.irohaco.core.contracts.IndexJobProgress
import dev.irohaco.core.contracts.IndexJobState
import dev.irohaco.core.contracts.IndexJobStore
import dev.irohaco.core.contracts.IndexSettings
import dev.irohaco.core.contracts.MediaIndexStore
import dev.irohaco.core.contracts.VectorHit
import dev.irohaco.core.contracts.VectorSearchStore
import dev.irohaco.core.contracts.VideoQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Changes are indexed by themselves only when turned on, after the first index, and never over an open job (M2-12). */
class IndexChangesTest {
    private class Job(val kind: IndexJobKind, val byUser: Boolean, var state: IndexJobState, val items: List<IndexItem>)

    private val jobs = linkedMapOf<String, Job>()
    private var hasVectors = true
    private var changed = listOf(IndexItem("asset:new", "r"))
    private val auto = MutableStateFlow(true)
    private var allowed = true
    private val started = mutableListOf<String>()

    private val media = object : MediaIndexStore {
        override suspend fun ensureSpace(newSpaceId: String, spec: EmbeddingSpaceSpec, signature: String) = "s"
        override suspend fun findSpace(signature: String) = "s"
        override suspend fun hasVectors(spaceId: String) = hasVectors
        override suspend fun plan(spaceId: String, folders: Set<String>?, quality: VideoQuality, now: Long) = changed
        override suspend fun source(targetId: String) = null
        override fun lowCoverageVideos() = flowOf(0)
        override suspend fun memoItem(annotationId: String) = null
        override suspend fun pendingCount(spaceId: String) = changed.size
        override suspend fun putVector(spaceId: String, item: IndexItem, vectorId: String, vector: FloatArray, createdAt: Long) {}
    }

    private val jobStore = object : IndexJobStore {
        override suspend fun create(jobId: String, spaceId: String, kind: IndexJobKind, requestedByUser: Boolean, requestedAt: Long, items: List<IndexItem>) {
            jobs[jobId] = Job(kind, requestedByUser, IndexJobState.PLANNED, items)
        }
        override suspend fun state(jobId: String) = jobs[jobId]?.state
        override suspend fun transition(jobId: String, from: Set<IndexJobState>, to: IndexJobState): Boolean {
            val job = jobs.getValue(jobId)
            return (job.state in from).also { if (it) job.state = to }
        }
        override suspend fun nextPending(jobId: String) = null
        override suspend fun begin(jobId: String, item: IndexItem) {}
        override suspend fun commit(jobId: String, item: IndexItem, vectorId: String, vector: FloatArray, createdAt: Long) {}
        override suspend fun fail(jobId: String, item: IndexItem) {}
        override suspend fun skip(jobId: String, item: IndexItem) {}
        override suspend fun requeueRunning(jobId: String?, maxAttempts: Int) {}
        override suspend fun failUnstored(jobId: String) = 0
        override suspend fun retryFailed(jobId: String) {}
        override suspend fun pauseInterrupted() {}
        override suspend fun markSpaceReady(jobId: String) {}
        override fun progress(jobId: String): Flow<IndexJobProgress?> = flowOf(jobs[jobId]?.let { IndexJobProgress(it.state, it.items.size, 0, 0, 0) })
        override fun latest(): Flow<String?> = flowOf(jobs.keys.lastOrNull())
        override fun failedNames(jobId: String, limit: Int) = flowOf(emptyList<String>())
    }

    private val vectors = object : VectorSearchStore {
        override fun spaces() = flowOf(emptyList<EmbeddingSpace>())
        override suspend fun space(spaceId: String) = null
        override suspend fun setDefault(spaceId: String) = false
        override suspend fun markStale(spaceIds: List<String>) {}
        override suspend fun nearest(spaceId: String, query: FloatArray, limit: Int) = emptyList<VectorHit>()
        override suspend fun coverage(spaceId: String) = 1f
    }

    private var ids = 0
    private val indexJobs = IndexJobs(jobStore, { floatArrayOf() }, { null }, { "job${ids++}" }, { 0 })
    private val plan = PlanMediaIndex(media, indexJobs, { "id" }, { 0 })
    private val settings = object : IndexSettings {
        override val autoIndex = auto
        override suspend fun setAutoIndex(enabled: Boolean) {
            auto.value = enabled
        }
    }
    private val indexChanges = IndexChanges(plan, indexJobs, SearchByMeaning(vectors, { floatArrayOf() }), settings) { started += it; allowed }

    @Test
    fun `changes are indexed by an incremental job the user did not start`() = runTest {
        indexChanges()
        assertEquals(listOf("job0"), started)
        val job = jobs.getValue("job0")
        assertEquals(IndexJobKind.INCREMENTAL to false, job.kind to job.byUser)
    }

    @Test
    fun `nothing starts when turned off, before the first index, or with nothing changed`() = runTest {
        auto.value = false
        indexChanges()
        auto.value = true
        hasVectors = false
        indexChanges()
        hasVectors = true
        changed = emptyList()
        indexChanges()
        assertEquals(emptyList(), started)
        assertEquals(emptyMap(), jobs.mapValues { it.value.state })
    }

    @Test
    fun `an open job is left alone`() = runTest {
        indexChanges()
        indexChanges()
        assertEquals(listOf("job0"), started)
    }

    @Test
    fun `a job the system does not let start waits paused`() = runTest {
        allowed = false
        indexChanges()
        assertEquals(IndexJobState.PAUSED, jobs.getValue("job0").state)
    }

    @Test
    fun `the count covers what is missing`() = runTest {
        assertEquals(1, plan.pendingCount())
    }
}
