package dev.irohaco

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import dev.irohaco.core.application.AiResourceManager
import dev.irohaco.core.application.BrowseLibrary
import dev.irohaco.core.application.BrowseMap
import dev.irohaco.core.application.BrowseTrash
import dev.irohaco.core.application.EmbedMediaItem
import dev.irohaco.core.application.GetLibraryAccess
import dev.irohaco.core.application.GetMediaDetails
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.application.IndexJobs
import dev.irohaco.core.application.IndexLocations
import dev.irohaco.core.application.ManageCollections
import dev.irohaco.core.application.ManageModels
import dev.irohaco.core.application.MarkMedia
import dev.irohaco.core.application.OrganizeMedia
import dev.irohaco.core.application.PlanMediaIndex
import dev.irohaco.core.application.PrepareShare
import dev.irohaco.core.application.ReconcileIdentity
import dev.irohaco.core.application.SyncMediaCatalog
import dev.irohaco.core.application.ThemeColorSettings
import dev.irohaco.core.contracts.AiPriority
import dev.irohaco.core.database.IrohacoDatabase
import dev.irohaco.core.database.IrohacoDatabaseFactory
import dev.irohaco.core.database.RoomCatalogStore
import dev.irohaco.core.database.RoomCollectionsStore
import dev.irohaco.core.database.RoomIdentityStore
import dev.irohaco.core.database.RoomMediaIndexStore
import dev.irohaco.core.database.RoomIndexJobStore
import dev.irohaco.core.database.RoomLibraryReader
import dev.irohaco.core.database.RoomLocationStore
import dev.irohaco.core.database.RoomMarksStore
import dev.irohaco.core.database.RoomModelProfiles
import dev.irohaco.core.database.RoomTimelineReader
import dev.irohaco.infrastructure.ai.AndroidResourcePolicy
import dev.irohaco.infrastructure.ai.AndroidModelDownloader
import dev.irohaco.infrastructure.ai.AndroidModelStorage
import dev.irohaco.infrastructure.ai.LiteRtEmbeddingRuntime
import dev.irohaco.infrastructure.media.AndroidIndexImageReader
import dev.irohaco.infrastructure.media.AndroidMediaDetailsReader
import dev.irohaco.infrastructure.media.AndroidMediaFiles
import dev.irohaco.infrastructure.media.AndroidMediaFormatProbe
import dev.irohaco.infrastructure.media.AndroidMediaLibraryAccess
import dev.irohaco.infrastructure.media.AndroidPlaceNames
import dev.irohaco.infrastructure.media.AndroidShareFiles
import dev.irohaco.infrastructure.media.AndroidTrashReader
import dev.irohaco.infrastructure.media.MediaStoreCatalogSource
import dev.irohaco.infrastructure.preferences.DataStoreAppearanceSettings
import dev.irohaco.infrastructure.preferences.DataStoreLibrarySettings
import dev.irohaco.infrastructure.preferences.DataStoreMapSettings
import dev.irohaco.infrastructure.preferences.DataStoreSharingSettings
import dev.irohaco.infrastructure.preferences.DataStoreTimelineSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Wires adapters to use cases (CONTRIBUTING §3.1: the app module is where dependencies are assembled). */
class AppContainer(context: Context, private val databaseName: String = IrohacoDatabaseFactory.NAME) {
    private val appContext = context.applicationContext

    /** Opens on first use; the caller must be off the main thread. */
    val database: IrohacoDatabase by lazy { IrohacoDatabaseFactory.open(appContext, databaseName) }

    private val libraryAccess by lazy { AndroidMediaLibraryAccess(appContext) }

    val getLibraryAccess by lazy { GetLibraryAccess(libraryAccess) }

    /** One DataStore per file for the whole process, as DataStore requires. */
    private val settingsStore by lazy { PreferenceDataStoreFactory.create { appContext.preferencesDataStoreFile("settings") } }

    val themeColorSettings by lazy { ThemeColorSettings(DataStoreAppearanceSettings(settingsStore)) }

    val timelineSettings by lazy { DataStoreTimelineSettings(settingsStore) }

    val sharingSettings by lazy { DataStoreSharingSettings(settingsStore) }

    val browseLibrary by lazy { BrowseLibrary(RoomLibraryReader(database), DataStoreLibrarySettings(settingsStore)) }

    /**
     * One player per open viewer; the viewer's ViewModel releases it. Decoder fallback retries with
     * another decoder when one fails to start. The emulator's goldfish H.264 decoder fails on CI's
     * software GPU, so emulator-only goldfish codecs go last; real devices have none.
     */
    @OptIn(UnstableApi::class)
    fun newVideoPlayer(): Player {
        val selector = MediaCodecSelector { mimeType, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling).sortedBy { it.name.startsWith("c2.goldfish.") }
        }
        val renderers = DefaultRenderersFactory(appContext).setMediaCodecSelector(selector).setEnableDecoderFallback(true)
        return ExoPlayer.Builder(appContext, renderers).build()
    }

    val mediaFormatProbe by lazy { AndroidMediaFormatProbe(appContext) }

    val getMediaDetails by lazy { GetMediaDetails(AndroidMediaDetailsReader(appContext)) }

    val prepareShare by lazy { PrepareShare(AndroidMediaDetailsReader(appContext), AndroidShareFiles(appContext)) }

    val browseTrash by lazy { BrowseTrash(AndroidTrashReader(appContext)) }

    val mapSettings by lazy { DataStoreMapSettings(settingsStore) }

    private val locationStore by lazy { RoomLocationStore(database) }

    val browseMap by lazy { BrowseMap(locationStore, mapSettings, AndroidPlaceNames(appContext)) }

    /** Reads the GPS of files not read yet; cancelling it (the app leaving the screen) pauses it (GAL-017). */
    suspend fun indexLocations() {
        try {
            withContext(Dispatchers.IO) { IndexLocations(locationStore, AndroidMediaDetailsReader(appContext))() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The next resume carries on from where this stopped.
            Log.w("Irohaco", "Location indexing failed", e)
        }
    }

    val getTimelinePage by lazy { GetTimelinePage(RoomTimelineReader(database)) }

    val markMedia by lazy { MarkMedia(RoomMarksStore(database)) }

    val manageCollections by lazy { ManageCollections(RoomCollectionsStore(database)) }

    val organizeMedia by lazy { OrganizeMedia(AndroidMediaFiles(appContext)) }

    val syncMediaCatalog: SyncMediaCatalog by lazy {
        SyncMediaCatalog(
            source = MediaStoreCatalogSource(appContext),
            access = libraryAccess,
            store = RoomCatalogStore(database),
            newId = { UUID.randomUUID().toString() },
            now = System::currentTimeMillis,
        )
    }

    /** Background work that outlives a screen, such as freeing the model when it sits unused. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Downloads, imports and checks model files (M2-02). */
    val manageModels by lazy {
        ManageModels(AndroidModelStorage(appContext), AndroidModelDownloader(appContext), RoomModelProfiles(database))
    }

    /** Starts a model download that keeps being followed after the screen that asked for it closes. */
    fun downloadModel(id: String) {
        scope.launch(Dispatchers.IO) { manageModels.download(id) }
    }

    /** Picks up downloads the system kept running while the app was closed. */
    fun resumeModelDownloads() {
        scope.launch(Dispatchers.IO) { manageModels.refresh() }
    }

    private val aiLazy = lazy {
        AiResourceManager(
            locator = manageModels.embeddingLocator,
            runtime = LiteRtEmbeddingRuntime(appContext.cacheDir.path),
            io = Dispatchers.IO,
            scope = scope,
        )
    }

    /** The local model's one owner; it loads the model only when inference is first asked for (MOD-004). */
    val aiResourceManager by aiLazy

    private val mediaIndexStore by lazy { RoomMediaIndexStore(database) }

    /** Indexing jobs (M2-03); [IndexingService] runs them, one at a time. */
    val indexJobs by lazy {
        IndexJobs(
            store = RoomIndexJobStore(database),
            processor = EmbedMediaItem(mediaIndexStore, AndroidIndexImageReader(appContext)) { bytes ->
                aiResourceManager.withEmbedding(AiPriority.BACKGROUND_INDEX) { it.embedImage(bytes) }
            },
            policy = AndroidResourcePolicy(appContext),
            newId = { UUID.randomUUID().toString() },
            now = System::currentTimeMillis,
        )
    }

    /** Plans an index of images and video frames the user asked for (M2-05, M2-06); the indexing screen (M2-04) starts the job. */
    val planMediaIndex by lazy { PlanMediaIndex(mediaIndexStore, indexJobs, { UUID.randomUUID().toString() }, System::currentTimeMillis) }

    /** Frees the model when the system runs short of memory; nothing to do if it never loaded. */
    fun trimMemory() {
        if (aiLazy.isInitialized()) scope.launch { aiResourceManager.release() }
    }

    val reconcileIdentity by lazy { ReconcileIdentity(RoomIdentityStore(database), ::syncCatalog, System::currentTimeMillis) }

    /** Runs on resume and after the app changes files itself; new files are then matched against missing ones. */
    suspend fun syncCatalog() {
        try {
            withContext(Dispatchers.IO) {
                syncMediaCatalog()
                RoomIdentityStore(database).findCandidates()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed sync leaves the catalog as it was; the gallery keeps working and the next resume retries.
            Log.w("Irohaco", "Catalog sync failed", e)
        }
    }
}
