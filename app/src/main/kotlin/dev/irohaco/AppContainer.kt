package dev.irohaco

import android.content.Context
import dev.irohaco.core.application.GetLibraryAccess
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.application.SyncMediaCatalog
import dev.irohaco.core.application.ThemeColorSettings
import dev.irohaco.core.database.IrohacoDatabase
import dev.irohaco.core.database.IrohacoDatabaseFactory
import dev.irohaco.core.database.RoomCatalogStore
import dev.irohaco.core.database.RoomTimelineReader
import dev.irohaco.infrastructure.media.AndroidMediaLibraryAccess
import dev.irohaco.infrastructure.media.MediaStoreCatalogSource
import dev.irohaco.infrastructure.preferences.DataStoreAppearanceSettings
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import java.util.UUID

/** Wires adapters to use cases (CONTRIBUTING §3.1: the app module is where dependencies are assembled). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Opens on first use; the caller must be off the main thread. */
    val database: IrohacoDatabase by lazy { IrohacoDatabaseFactory.open(appContext) }

    private val libraryAccess by lazy { AndroidMediaLibraryAccess(appContext) }

    val getLibraryAccess by lazy { GetLibraryAccess(libraryAccess) }

    /** One DataStore per file for the whole process, as DataStore requires. */
    val themeColorSettings by lazy {
        ThemeColorSettings(DataStoreAppearanceSettings(PreferenceDataStoreFactory.create { appContext.preferencesDataStoreFile("settings") }))
    }

    val getTimelinePage by lazy { GetTimelinePage(RoomTimelineReader(database)) }

    val syncMediaCatalog: SyncMediaCatalog by lazy {
        SyncMediaCatalog(
            source = MediaStoreCatalogSource(appContext),
            access = libraryAccess,
            store = RoomCatalogStore(database),
            newId = { UUID.randomUUID().toString() },
            now = System::currentTimeMillis,
        )
    }
}
