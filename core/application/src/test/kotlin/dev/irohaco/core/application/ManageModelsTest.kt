package dev.irohaco.core.application

import dev.irohaco.core.contracts.DownloadStatus
import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.ModelDownloader
import dev.irohaco.core.contracts.ModelInstallState
import dev.irohaco.core.contracts.ModelInstallState.Failed.Reason
import dev.irohaco.core.contracts.ModelStorage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Model files are usable only once their SHA-256 matches the catalog (M2-02, MOD-001). */
class ManageModelsTest {
    private val model = ModelArtifact("m", "Model", "https://example.invalid/m", 10, "good")
    private val files = mutableMapOf<String, String>() // path -> digest
    private val installedPaths = mutableMapOf<String, String>()
    private val registered = mutableListOf<String>()
    private var downloadResult: List<DownloadStatus> = emptyList()
    private val seen = mutableListOf<ModelInstallState>()

    private val storage = object : ModelStorage {
        override fun installed(id: String) = installedPaths[id]
        override fun partial(id: String) = "/models/$id.partial"
        override suspend fun copyIn(sourceUri: String) = "/models/import.partial".also { files[it] = sourceUri.substringAfter("digest=") }
        override suspend fun sha256(path: String) = files.getValue(path)
        override suspend fun promote(path: String, id: String) = "/models/$id.litertlm".also {
            files.remove(path)
            installedPaths[id] = it
        }
        override suspend fun discard(path: String) {
            files.remove(path)
        }
        override suspend fun delete(id: String) {
            installedPaths.remove(id)
        }
    }

    private val downloader = object : ModelDownloader {
        override suspend fun start(artifact: ModelArtifact, destination: String) {}
        override fun status(artifact: ModelArtifact) = flowOf(*downloadResult.toTypedArray())
        override suspend fun cancel(artifact: ModelArtifact) {}
    }

    private val models = ManageModels(storage, downloader, { registered += it.id }, listOf(model))

    @Test
    fun `a download that matches its digest becomes usable and is registered`() = runTest {
        files["/models/m.partial"] = "good"
        downloadResult = listOf(DownloadStatus.Running(5, 10), DownloadStatus.Done("/models/m.partial"))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { models.states.collect { seen += it.getValue("m") } }
        models.download("m")
        assertEquals(ModelInstallState.Installed("/models/m.litertlm"), models.states.value["m"])
        assertEquals(listOf("m"), registered)
        assertEquals(true, ModelInstallState.Downloading(5, 10) in seen)
        assertEquals(true, ModelInstallState.Verifying in seen)
    }

    @Test
    fun `a download with another digest is removed and fails`() = runTest {
        files["/models/m.partial"] = "tampered"
        downloadResult = listOf(DownloadStatus.Done("/models/m.partial"))
        models.download("m")
        assertEquals(ModelInstallState.Failed(Reason.DIGEST_MISMATCH), models.states.value["m"])
        assertEquals(emptyMap(), files)
        assertEquals(emptyList(), registered)
    }

    @Test
    fun `a failed download says so`() = runTest {
        downloadResult = listOf(DownloadStatus.Failed)
        models.download("m")
        assertEquals(ModelInstallState.Failed(Reason.DOWNLOAD), models.states.value["m"])
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

    @Test
    fun `refresh follows a download that kept running while the app was closed`() = runTest {
        files["/models/m.partial"] = "good"
        downloadResult = listOf(DownloadStatus.Done("/models/m.partial"))
        models.refresh()
        assertEquals(ModelInstallState.Installed("/models/m.litertlm"), models.states.value["m"])
    }
}
