package dev.irohaco.core.database

import androidx.room.Dao
import androidx.room.Query
import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.LibraryFolder
import dev.irohaco.core.contracts.LibraryReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface LibraryDao {
    /**
     * Available media per folder; an offline volume counts what it held when it was unplugged
     * (AC-010). Folders without media never appear, because MediaStore lists files, not folders.
     */
    @Query(
        """SELECT v.volume_id AS volumeId, v.provider_volume_name AS volumeName, v.state AS volumeState,
                  COALESCE(s.relative_path, '') AS relativePath, count(*) AS itemCount
           FROM media_sources s
           JOIN media_assets a ON a.asset_id = s.asset_id
           JOIN storage_volumes v ON v.volume_id = s.volume_id
           WHERE a.tombstoned_at IS NULL
             AND (s.access_state = 'AVAILABLE' OR (v.state = 'OFFLINE' AND s.access_state = 'OFFLINE'))
           GROUP BY v.volume_id, COALESCE(s.relative_path, '')
           ORDER BY v.provider_volume_name, relativePath""",
    )
    suspend fun folders(): List<FolderRow>
}

data class FolderRow(val volumeId: String, val volumeName: String, val volumeState: String, val relativePath: String, val itemCount: Int)

class RoomLibraryReader(private val db: IrohacoDatabase) : LibraryReader {
    override suspend fun folders(): List<LibraryFolder> = db.libraryDao().folders().map {
        LibraryFolder(FolderRef(it.volumeId, it.relativePath), it.volumeName, offline = it.volumeState == "OFFLINE", it.itemCount)
    }

    override fun changes(): Flow<Unit> =
        db.invalidationTracker.createFlow("media_sources", "media_assets", "storage_volumes", emitInitialState = true).map { }
}
