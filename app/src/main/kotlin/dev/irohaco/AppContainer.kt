package dev.irohaco

import android.content.Context
import android.util.Log
import dev.irohaco.core.application.OrganizeMedia
import dev.irohaco.infrastructure.media.AndroidMediaFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import dev.irohaco.core.application.GetLibraryAccess
import dev.irohaco.core.application.GetMediaDetails
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.application.SyncMediaCatalog
import dev.irohaco.core.application.ThemeColorSettings
import dev.irohaco.core.database.IrohacoDatabase
import dev.irohaco.core.database.IrohacoDatabaseFactory
import dev.irohaco.core.database.RoomCatalogStore
import dev.irohaco.core.database.RoomTimelineReader
import dev.irohaco.infrastructure.media.AndroidMediaDetailsReader
import dev.irohaco.infrastructure.media.AndroidMediaLibraryAccess
import dev.irohaco.infrastructure.media.MediaStoreCatalogSource
import dev.irohaco.infrastructure.preferences.DataStoreAppearanceSettings
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import java.util.UUID

/** Wires adapters to use cases (CONTRIBUTING §3.1: the app module is where dependencies are assembled). */
class AppContainer(context: Context, private val databaseName: String = IrohacoDatabaseFactory.NAME) {
    private val appContext = context.applicationContext

    /** Opens on first use; the caller must be off the main thread. */
    val database: IrohacoDatabase by lazy { IrohacoDatabaseFactory.open(appContext, databaseName) }

    private val libraryAccess by lazy { AndroidMediaLibraryAccess(appContext) }

    val getLibraryAccess by lazy { GetLibraryAccess(libraryAccess) }

    /** One DataStore per file for the whole process, as DataStore requires. */
    val themeColorSettings by lazy {
        ThemeColorSettings(DataStoreAppearanceSettings(PreferenceDataStoreFactory.create { appContext.preferencesDataStoreFile("settings") }))
    }

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

    val getMediaDetails by lazy { GetMediaDetails(AndroidMediaDetailsReader(appContext)) }

    val getTimelinePage by lazy { GetTimelinePage(RoomTimelineReader(database)) }

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

    /** Runs on resume and after the app changes files itself. */
    suspend fun syncCatalog() {
        try {
            withContext(Dispatchers.IO) { syncMediaCatalog() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed sync leaves the catalog as it was; the gallery keeps working and the next resume retries.
            Log.w("Irohaco", "Catalog sync failed", e)
        }
    }
}
