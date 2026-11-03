package dev.irohaco.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import dev.irohaco.core.contracts.ConnectionStore
import dev.irohaco.core.contracts.EgressGrant
import dev.irohaco.core.contracts.EgressGrantStore
import dev.irohaco.core.contracts.EgressPayload
import dev.irohaco.core.contracts.ModelCapability
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.RemoteModel
import dev.irohaco.core.contracts.RemoteModelStore
import dev.irohaco.core.contracts.RemoteProtocol
import dev.irohaco.core.contracts.TaskType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Dao
interface EgressDao {
    @Query("SELECT * FROM provider_connections WHERE protocol != 'LOCAL_LITERT' ORDER BY connection_id")
    fun remoteConnections(): Flow<List<ProviderConnectionsEntity>>

    @Query("SELECT * FROM egress_grants")
    fun grants(): Flow<List<EgressGrantsEntity>>

    @Query(
        """SELECT g.payload_type FROM egress_grants g JOIN model_profiles p ON p.connection_id = g.connection_id
           WHERE p.model_profile_id = :profileId AND g.task_code = :task""",
    )
    suspend fun granted(profileId: String, task: String): List<String>

    @Query("SELECT asset_id FROM asset_privacy_rules WHERE allow_remote_send = 0 AND asset_id IN (:assetIds)")
    suspend fun blocked(assetIds: List<String>): List<String>

    @Query("INSERT OR IGNORE INTO egress_grants(connection_id, task_code, payload_type, granted_at) VALUES (:connectionId, :task, :payload, :now)")
    suspend fun grant(connectionId: String, task: String, payload: String, now: Long)

    @Query("DELETE FROM egress_grants WHERE connection_id = :connectionId AND task_code = :task AND payload_type = :payload")
    suspend fun revoke(connectionId: String, task: String, payload: String)

    @Query("INSERT INTO provider_connections(connection_id, protocol, base_url, credential_ref) VALUES (:id, :protocol, :baseUrl, :credentialRef)")
    suspend fun addConnection(id: String, protocol: String, baseUrl: String, credentialRef: String)

    @Query("SELECT credential_ref FROM provider_connections WHERE connection_id = :id")
    suspend fun credentialRef(id: String): String?

    @Query("DELETE FROM egress_grants WHERE connection_id = :id")
    suspend fun revokeAll(id: String)

    @Query("DELETE FROM provider_connections WHERE connection_id = :id AND protocol != 'LOCAL_LITERT'")
    suspend fun deleteConnection(id: String)

    @Transaction
    suspend fun removeConnection(id: String): String? = credentialRef(id).also {
        revokeAll(id)
        deleteCapabilitiesOf(id)
        deleteProfilesOf(id)
        deleteConnection(id)
    }

    @Query(
        """SELECT p.model_profile_id AS profileId, p.connection_id AS connectionId, p.provider_model_id AS modelId,
             p.display_name AS displayName, GROUP_CONCAT(c.capability_code) AS capabilities
           FROM model_profiles p JOIN provider_connections pc ON pc.connection_id = p.connection_id
           LEFT JOIN model_capabilities c ON c.model_profile_id = p.model_profile_id
           WHERE pc.protocol != 'LOCAL_LITERT' GROUP BY p.model_profile_id ORDER BY p.display_name""",
    )
    fun remoteModels(): Flow<List<RemoteModelRow>>

    @Query("SELECT * FROM provider_connections WHERE connection_id = :id AND protocol != 'LOCAL_LITERT'")
    suspend fun connection(id: String): ProviderConnectionsEntity?

    @Query("INSERT INTO model_profiles(model_profile_id, connection_id, provider_model_id, artifact_revision, display_name) VALUES (:id, :connectionId, :modelId, NULL, :name)")
    suspend fun addProfile(id: String, connectionId: String, modelId: String, name: String)

    @Query("INSERT INTO model_capabilities(model_profile_id, capability_code) VALUES (:id, :code)")
    suspend fun addCapability(id: String, code: String)

    @Transaction
    suspend fun addModel(id: String, connectionId: String, modelId: String, name: String, capabilities: List<String>) {
        addProfile(id, connectionId, modelId, name)
        capabilities.forEach { addCapability(id, it) }
    }

    @Query("DELETE FROM model_capabilities WHERE model_profile_id = :id")
    suspend fun deleteCapabilities(id: String)

    @Query("DELETE FROM model_profiles WHERE model_profile_id = :id AND connection_id IN (SELECT connection_id FROM provider_connections WHERE protocol != 'LOCAL_LITERT')")
    suspend fun deleteProfile(id: String)

    @Transaction
    suspend fun removeModel(id: String) {
        deleteCapabilities(id)
        deleteProfile(id)
    }

    @Query("DELETE FROM model_capabilities WHERE model_profile_id IN (SELECT model_profile_id FROM model_profiles WHERE connection_id = :id)")
    suspend fun deleteCapabilitiesOf(id: String)

    @Query("DELETE FROM model_profiles WHERE connection_id = :id")
    suspend fun deleteProfilesOf(id: String)
}

data class RemoteModelRow(val profileId: String, val connectionId: String, val modelId: String, val displayName: String, val capabilities: String?) {
    fun toModel() = RemoteModel(profileId, connectionId, modelId, displayName, capabilities.orEmpty().split(',').filter { it.isNotEmpty() }.map(ModelCapability::valueOf).toSet())
}

class RoomEgressGrantStore(db: IrohacoDatabase, private val now: () -> Long = System::currentTimeMillis) : EgressGrantStore, ConnectionStore, RemoteModelStore {
    private val dao = db.egressDao()

    override val connections: Flow<List<RemoteConnection>> =
        dao.remoteConnections().map { rows -> rows.map { RemoteConnection(it.connectionId, RemoteProtocol.valueOf(it.protocol), it.baseUrl.orEmpty()) } }

    override val grants: Flow<Set<EgressGrant>> = dao.grants().map { rows ->
        rows.map { EgressGrant(it.connectionId, TaskType.valueOf(it.taskCode), EgressPayload.valueOf(it.payloadType)) }.toSet()
    }

    override suspend fun granted(profileId: String, task: TaskType) = dao.granted(profileId, task.name).map(EgressPayload::valueOf).toSet()

    // shortcut: one IN list, so past SQLite's 999 variables it throws; chunk it once a request carries that many assets.
    override suspend fun blocked(assetIds: Set<String>) = dao.blocked(assetIds.toList()).toSet()

    override suspend fun set(grant: EgressGrant, allowed: Boolean) = with(grant) {
        if (allowed) dao.grant(connectionId, task.name, payload.name, now()) else dao.revoke(connectionId, task.name, payload.name)
    }

    override suspend fun add(id: String, protocol: RemoteProtocol, baseUrl: String, credentialRef: String) =
        dao.addConnection(id, protocol.name, baseUrl, credentialRef)

    override suspend fun remove(id: String) = dao.removeConnection(id)

    override val models: Flow<List<RemoteModel>> = dao.remoteModels().map { rows -> rows.map { it.toModel() } }

    override suspend fun model(profileId: String) = models.first().firstOrNull { it.profileId == profileId }

    override suspend fun connection(id: String) = dao.connection(id)?.let { RemoteConnection(it.connectionId, RemoteProtocol.valueOf(it.protocol), it.baseUrl.orEmpty()) }

    override suspend fun credentialRef(connectionId: String) = dao.credentialRef(connectionId)

    override suspend fun addModel(model: RemoteModel) = with(model) { dao.addModel(profileId, connectionId, modelId, displayName, capabilities.map { it.name }) }

    override suspend fun removeModel(profileId: String) = dao.removeModel(profileId)
}
