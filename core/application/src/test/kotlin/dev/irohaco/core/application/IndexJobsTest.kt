package dev.irohaco.core.application

import dev.irohaco.core.contracts.IndexItem
import dev.irohaco.core.contracts.IndexJobKind
import dev.irohaco.core.contracts.IndexJobProgress
import dev.irohaco.core.contracts.IndexJobState
import dev.irohaco.core.contracts.IndexJobStore
import dev.irohaco.core.contracts.ModelNotInstalledException
import dev.irohaco.core.contracts.PauseReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Jobs start only on the user's request and resume from their last checkpoint without duplicates (M2-03). */
class IndexJobsTest {
    private class Job(var state: IndexJobState, val items: LinkedHashMap<IndexItem, String>, val attempts: MutableMap<IndexItem, Int>)

    private val jobs = mutableMapOf<String, Job>()
    private val vectors = mutableListOf<IndexItem>()

    private val store = object : IndexJobStore {
        override suspend fun create(jobId: String, spaceId: String, kind: IndexJobKind, requestedByUser: Boolean, requestedAt: Long, items: List<IndexItem>) {
            jobs[jobId] = Job(IndexJobState.PLANNED, LinkedHashMap(items.associateWith { "PENDING" }), mutableMapOf())
        }
        override suspend fun state(jobId: String) = jobs[jobId]?.state
        override suspend fun transition(jobId: String, from: Set<IndexJobState>, to: IndexJobState): Boolean {
            val job = jobs.getValue(jobId)
            return (job.state in from).also { if (it) job.state = to }
        }
        override suspend fun nextPending(jobId: String) = jobs.getValue(jobId).items.entries.firstOrNull { it.value == "PENDING" }?.key
        override suspend fun begin(jobId: String, item: IndexItem) {
            val job = jobs.getValue(jobId)
            job.items[item] = "RUNNING"
            job.attempts[item] = (job.attempts[item] ?: 0) + 1
        }
        override suspend fun commit(jobId: String, item: IndexItem, vectorId: String, vector: FloatArray, createdAt: Long) {
            if (item !in vectors) vectors += item
            jobs.getValue(jobId).items[item] = "COMMITTED"
        }
        override suspend fun fail(jobId: String, item: IndexItem) {
            jobs.getValue(jobId).items[item] = "FAILED"
        }
        override suspend fun skip(jobId: String, item: IndexItem) {
            jobs.getValue(jobId).items[item] = "SKIPPED"
        }
        override suspend fun requeueRunning(jobId: String?, maxAttempts: Int) {
            jobs.filterKeys { jobId == null || it == jobId }.values.forEach { job ->
                job.items.replaceAll { item, s -> if (s != "RUNNING") s else if (job.attempts.getValue(item) >= maxAttempts) "FAILED" else "PENDING" }
            }
        }
        override suspend fun failUnstored(jobId: String) = 0
        override suspend fun retryFailed(jobId: String) {
            jobs.getValue(jobId).items.replaceAll { _, s -> if (s == "FAILED") "PENDING" else s }
        }
        override suspend fun pauseInterrupted() {
            jobs.values.filter { it.state in setOf(IndexJobState.RUNNING, IndexJobState.PAUSING, IndexJobState.VERIFYING) }.forEach { it.state = IndexJobState.PAUSED }
        }
        override fun latest(): Flow<String?> = flowOf(jobs.keys.lastOrNull())
        override fun failedNames(jobId: String, limit: Int) = flowOf(emptyList<String>())
        override fun progress(jobId: String): Flow<IndexJobProgress?> {
            val s = jobs.getValue(jobId).items.values
            return flowOf(IndexJobProgress(jobs.getValue(jobId).state, s.count { it == "PENDING" || it == "RUNNING" }, s.count { it == "COMMITTED" }, s.count { it == "FAILED" }, 0))
        }
    }

    private val items = (1..5).map { IndexItem("t$it", "r") }
    private var hold: PauseReason? = null
    private var embed: suspend (IndexItem) -> FloatArray = { floatArrayOf(1f) }
    private var ids = 0
    private val indexJobs = IndexJobs(store, { embed(it) }, { hold }, { "id${ids++}" }, { 0 })

    @Test
    fun `the first index needs the user's request`() = runTest {
        assertFailsWith<IllegalArgumentException> { indexJobs.start("s", IndexJobKind.INITIAL, items, requestedByUser = false) }
        assertEquals(emptyMap(), jobs)
    }

    @Test
    fun `a job commits every item and succeeds`() = runTest {
        val id = indexJobs.start("s", IndexJobKind.INITIAL, items, requestedByUser = true)
        assertEquals(IndexJobState.SUCCEEDED, indexJobs.run(id))
        assertEquals(items, vectors)
    }

    @Test
    fun `pausing stops after the current item and resuming carries on without duplicates`() = runTest {
        val id = indexJobs.start("s", IndexJobKind.INITIAL, items, requestedByUser = true)
        embed = { if (it == items[1]) indexJobs.pause(id); floatArrayOf(1f) }
        assertEquals(IndexJobState.PAUSED, indexJobs.run(id))
        assertEquals(items.take(2), vectors)
        embed = { floatArrayOf(1f) }
        assertEquals(IndexJobState.SUCCEEDED, indexJobs.run(id))
        assertEquals(items, vectors)
    }

    @Test
    fun `heat or a low battery pauses at a checkpoint`() = runTest {
        val id = indexJobs.start("s", IndexJobKind.INITIAL, items, requestedByUser = true)
        embed = { if (it == items[2]) hold = PauseReason.THERMAL; floatArrayOf(1f) }
        assertEquals(IndexJobState.PAUSED, indexJobs.run(id))
        assertEquals(PauseReason.THERMAL, indexJobs.pauseReason.value)
        assertEquals(3, vectors.size)
    }

    @Test
    fun `a stop by the system puts the item in progress back and pauses`() = runTest {
        val id = indexJobs.start("s", IndexJobKind.INITIAL, items, requestedByUser = true)
        val reached = CompletableDeferred<Unit>()
        embed = { if (it == items[1]) { reached.complete(Unit); CompletableDeferred<Unit>().await() }; floatArrayOf(1f) }
        val run = async { indexJobs.run(id) }
        reached.await()
        run.cancel()
        run.join()
        assertEquals(IndexJobState.PAUSED, jobs.getValue(id).state)
        assertEquals("PENDING", jobs.getValue(id).items[items[1]])
        embed = { floatArrayOf(1f) }
        assertEquals(IndexJobState.SUCCEEDED, indexJobs.run(id))
        assertEquals(items, vectors)
    }

    @Test
    fun `a killed process leaves a job that pauses, and an item that keeps crashing fails`() = runTest {
        store.create("j", "s", IndexJobKind.INITIAL, true, 0, items)
        jobs.getValue("j").state = IndexJobState.RUNNING
        jobs.getValue("j").items[items[0]] = "RUNNING"
        jobs.getValue("j").attempts[items[0]] = 3
        jobs.getValue("j").items[items[1]] = "RUNNING"
        jobs.getValue("j").attempts[items[1]] = 1
        indexJobs.recover()
        assertEquals(IndexJobState.PAUSED, jobs.getValue("j").state)
        assertEquals(listOf("FAILED", "PENDING"), listOf(jobs.getValue("j").items[items[0]], jobs.getValue("j").items[items[1]]))
        assertEquals(IndexJobState.PARTIAL, indexJobs.run("j"))
    }

    @Test
    fun `failed items are listed and a retry processes only them`() = runTest {
        val id = indexJobs.start("s", IndexJobKind.INITIAL, items, requestedByUser = true)
        embed = { if (it == items[3]) error("unreadable"); floatArrayOf(1f) }
        assertEquals(IndexJobState.PARTIAL, indexJobs.run(id))
        assertEquals(1, jobs.getValue(id).items.values.count { it == "FAILED" })
        embed = { check(it == items[3]); floatArrayOf(1f) }
        indexJobs.retryFailed(id)
        assertEquals(IndexJobState.SUCCEEDED, indexJobs.run(id))
    }

    @Test
    fun `a missing model fails the job without blaming the item`() = runTest {
        val id = indexJobs.start("s", IndexJobKind.INITIAL, items, requestedByUser = true)
        embed = { throw ModelNotInstalledException() }
        assertEquals(IndexJobState.FAILED, indexJobs.run(id))
        assertEquals(setOf("PENDING"), jobs.getValue(id).items.values.toSet())
    }

    @Test
    fun `a cancelled job does not run again`() = runTest {
        val id = indexJobs.start("s", IndexJobKind.INITIAL, items, requestedByUser = true)
        indexJobs.cancel(id)
        assertEquals(IndexJobState.CANCELLED, indexJobs.run(id))
        assertEquals(emptyList(), vectors)
    }
}
