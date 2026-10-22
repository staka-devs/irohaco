package dev.irohaco.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.ModelProfiles

/** The on-device models, under one local connection (M2-02). Rows are kept: indexes refer to them. */
@Dao
interface ModelProfileDao {
    @Query("INSERT OR IGNORE INTO provider_connections(connection_id, protocol, base_url, credential_ref) VALUES ('$LOCAL', 'LOCAL_LITERT', NULL, NULL)")
    suspend fun ensureLocalConnection()

    // Not INSERT OR REPLACE: replacing deletes the row first, which embedding spaces' RESTRICT forbids.
    @Query("INSERT OR IGNORE INTO model_profiles(model_profile_id, connection_id, provider_model_id, artifact_revision, display_name) VALUES (:id, '$LOCAL', :id, :revision, :name)")
    suspend fun insert(id: String, revision: String, name: String)

    @Query("UPDATE model_profiles SET artifact_revision = :revision, display_name = :name WHERE model_profile_id = :id")
    suspend fun update(id: String, revision: String, name: String)

    @Query("SELECT artifact_revision FROM model_profiles WHERE model_profile_id = :id")
    suspend fun revision(id: String): String?

    @Transaction
    suspend fun register(id: String, revision: String, name: String) {
        ensureLocalConnection()
        insert(id, revision, name)
        update(id, revision, name)
    }
}

private const val LOCAL = "local-litert"

class RoomModelProfiles(private val db: IrohacoDatabase) : ModelProfiles {
    override suspend fun register(artifact: ModelArtifact) = db.modelProfileDao().register(artifact.id, artifact.sha256, artifact.displayName)
}
