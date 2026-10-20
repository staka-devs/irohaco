package dev.irohaco.core.application

import dev.irohaco.core.contracts.IdentityCandidate
import dev.irohaco.core.contracts.IdentityStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReconcileIdentityTest {
    private val calls = mutableListOf<String>()

    private val store = object : IdentityStore {
        override suspend fun findCandidates(): Int = 1.also { calls += "find" }
        override fun pending(): Flow<List<IdentityCandidate>> = flowOf(emptyList())
        override suspend fun link(discoveryId: String, missingAssetId: String, atMs: Long) {
            calls += "link $discoveryId $missingAssetId $atMs"
        }
        override suspend fun reject(discoveryId: String, missingAssetId: String) {
            calls += "reject $discoveryId $missingAssetId"
        }
    }

    private val reconcile = ReconcileIdentity(store, sync = { calls += "sync" }, now = { 7 })
    private val candidate = IdentityCandidate("d", "old", "new", "a.jpg", "DCIM/", "Pictures/", "content://1")

    @Test
    fun `recheck syncs first, then looks for candidates`() = runTest {
        reconcile.recheckMissing()
        assertEquals(listOf("sync", "find"), calls)
    }

    @Test
    fun `the user's answer links or rejects that pair only`() = runTest {
        reconcile.sameFile(candidate)
        reconcile.differentFile(candidate)
        assertEquals(listOf("link d old 7", "reject d old"), calls)
    }
}
