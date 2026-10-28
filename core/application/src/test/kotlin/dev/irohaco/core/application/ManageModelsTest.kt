package dev.irohaco.core.application

import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.ModelDownloader
import dev.irohaco.core.contracts.ModelInstallState
import dev.irohaco.core.contracts.ModelInstallState.Failed.Reason
import dev.irohaco.core.contracts.ModelStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/** Model files are usable only once their SHA-256 matches the catalog (M2-02, MOD-001); downloads pause, resume and cancel. */
class ManageModelsTest {
    private val model = ModelArtifact("m", "Model", "https://example.invalid/m", 10, "good")
    private val files = mutableMapOf<String, String>() // path -> digest
    private val partialBytes = mutableMapOf<String, Long>()
    private val installedPaths = mutableMapOf<String, String>()
    private val registered = mutableListOf<String>()
    private val seen = mutableListOf<ModelInstallState>()

    private val storage = object : ModelStorage {
        override fun installed(id: String) = installedPaths[id]
        override fun partial(id: String) = "/models/$id.partial"
        override fun partialBytes(id: String) = partialBytes[partial(id)] ?: 0
        override suspend fun copyIn(sourceUri: String) = "/models/import.partial".also { files[it] = sourceUri.substringAfter("digest=") }
        override suspend fun sha256(path: String) = files.getValue(path)
        override suspend fun promote(path: String, id: String) = "/models/$id.litertlm".also {
            files.remove(path)
            partialBytes.remove(path)
            installedPaths[id] = it
        }
        override suspend fun discard(path: String) {
            files.remove(path)
            partialBytes.remove(path)
        }
        override suspend fun delete(id: String) {
            installedPaths.remove(id)
        }
    }

    /** What the next fetch does with the partial file: by default the whole file arrives with [digest]. */
    private var fetch: suspend (destination: String, onProgress: (Long, Long) -> Unit) -> Unit = { destination, onProgress ->
        onProgress(5, 10)
        partialBytes[destination] = 10
        files[destination] = digest
    }
    private var digest = "good"
    private val fetchedFrom = mutableListOf<Long>()

    private val downloader = object : ModelDownloader {
        override suspend fun fetch(artifact: ModelArtifact, destination: String, onProgress: (Long, Long) -> Unit) {
            fetchedFrom += partialBytes[destination] ?: 0
            fetch(destination, onProgress)
        }
    }

    private val models = ManageModels(storage, downloader, { registered += it.id }, listOf(model))

    @Test
    fun `a download that matches its digest becomes usable and is registered`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { models.states.collect { seen += it.getValue("m") } }
        models.download("m")
        assertEquals(ModelInstallState.Installed("/models/m.litertlm"), models.states.value["m"])
        assertEquals(listOf("m"), registered)
        assertEquals(true, ModelInstallState.Downloading(5, 10) in seen)
        assertEquals(true, ModelInstallState.Verifying in seen)
    }

    @Test
    fun `a download with another digest is removed and fails`() = runTest {
        digest = "tampered"
        models.download("m")
        assertEquals(ModelInstallState.Failed(Reason.DIGEST_MISMATCH), models.states.value["m"])
        assertEquals(emptyMap(), files)
        assertEquals(emptyList(), registered)
    }

    @Test
    fun `a dropped download fails, keeps its part, and the retry resumes from there`() = runTest {
        fetch = { destination, _ ->
            partialBytes[destination] = 4
            throw IOException("connection reset")
        }
        models.download("m")
        assertEquals(ModelInstallState.Failed(Reason.DOWNLOAD), models.states.value["m"])
        assertEquals(4L, storage.partialBytes("m"))

        fetch = { destination, _ ->
            partialBytes[destination] = 10
            files[destination] = "good"
        }
        models.download("m")
        assertEquals(listOf(0L, 4L), fetchedFrom)
        assertEquals(ModelInstallState.Installed("/models/m.litertlm"), models.states.value["m"])
    }

    @Test
    fun `a paused download keeps its part and resumes from it`() = runTest {
        val written = CompletableDeferred<Unit>()
        fetch = { destination, _ ->
            partialBytes[destination] = 6
            written.complete(Unit)
            CompletableDeferred<Unit>().await() // until paused
        }
        val running = launch { models.download("m") }
        written.await()
        models.pause("m")
        running.join()
        assertEquals(ModelInstallState.Paused(6, 10), models.states.value["m"])

        fetch = { destination, _ ->
            partialBytes[destination] = 10
            files[destination] = "good"
        }
        models.download("m")
        assertEquals(listOf(0L, 6L), fetchedFrom)
        assertEquals(ModelInstallState.Installed("/models/m.litertlm"), models.states.value["m"])
    }

    @Test
    fun `a cancelled download removes its part`() = runTest {
        val written = CompletableDeferred<Unit>()
        fetch = { destination, _ ->
            partialBytes[destination] = 6
            written.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        val running = launch { models.download("m") }
        written.await()
        models.cancel("m")
        running.join()
        assertEquals(ModelInstallState.NotInstalled, models.states.value["m"])
        assertEquals(0L, storage.partialBytes("m"))
    }

    @Test
    fun `cancelling a paused download removes its part`() = runTest {
        partialBytes["/models/m.partial"] = 6
        models.refresh()
        assertEquals(ModelInstallState.Paused(6, 10), models.states.value["m"])
        models.cancel("m")
        assertEquals(ModelInstallState.NotInstalled, models.states.value["m"])
        assertEquals(0L, storage.partialBytes("m"))
    }

    @Test
    fun `an imported file is installed as the model whose digest it has`() = runTest {
        assertEquals(ModelInstallState.Installed("/models/m.litertlm"), models.import("content://picked?digest=good"))
        assertEquals(listOf("m"), registered)
    }

    @Test
    fun `an imported file that is no known model is removed`() = runTest {
        assertEquals(ModelInstallState.Failed(Reason.UNKNOWN_MODEL), models.import("content://picked?digest=other"))
        assertEquals(emptyMap(), files)
        assertEquals(ModelInstallState.NotInstalled, models.states.value["m"])
    }

    @Test
    fun `refresh finds installed files and deleting removes them`() = runTest {
        installedPaths["m"] = "/models/m.litertlm"
        models.refresh()
        assertEquals(ModelInstallState.Installed("/models/m.litertlm"), models.states.value["m"])
        models.delete("m")
        assertEquals(ModelInstallState.NotInstalled, models.states.value["m"])
        assertEquals(null, storage.installed("m"))
    }
}
