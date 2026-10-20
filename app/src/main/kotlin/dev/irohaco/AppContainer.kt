package dev.irohaco

import android.content.Context
import dev.irohaco.core.application.SyncMediaCatalog
import dev.irohaco.core.database.IrohacoDatabase
import dev.irohaco.core.database.IrohacoDatabaseFactory
import dev.irohaco.core.database.RoomCatalogStore
import dev.irohaco.infrastructure.media.AndroidMediaLibraryAccess
import dev.irohaco.infrastructure.media.MediaStoreCatalogSource
import java.util.UUID

/** Wires adapters to use cases (CONTRIBUTING §3.1: the app module is where dependencies are assembled). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Opens on first use; the caller must be off the main thread. */
    val database: IrohacoDatabase by lazy { IrohacoDatabaseFactory.open(appContext) }

    val syncMediaCatalog: SyncMediaCatalog by lazy {
        SyncMediaCatalog(
            source = MediaStoreCatalogSource(appContext),
            access = AndroidMediaLibraryAccess(appContext),
            store = RoomCatalogStore(database),
            newId = { UUID.randomUUID().toString() },
            now = System::currentTimeMillis,
        )
    }
}
