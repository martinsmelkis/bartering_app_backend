package app.bartering.features.federation.dao

import app.bartering.features.federation.model.*

/**
 * Data Access Object interface for federation operations.
 * Handles all database interactions for federation entities.
 */
interface FederationDao {

    // Local Server Identity Operations

    suspend fun getLocalServerIdentity(): LocalServerIdentity?

    suspend fun saveLocalServerIdentity(identity: LocalServerIdentity): LocalServerIdentity

    suspend fun updateLocalServerIdentity(identity: LocalServerIdentity): LocalServerIdentity

    // Federated Server Operations

    suspend fun getFederatedServer(serverId: String): FederatedServer?

    suspend fun listFederatedServers(trustLevel: TrustLevel? = null): List<FederatedServer>

    suspend fun createFederatedServer(server: FederatedServer): FederatedServer

    suspend fun updateFederatedServer(serverId: String, updates: Map<String, Any?>): Boolean

    suspend fun deleteFederatedServer(serverId: String): Boolean

    suspend fun updateServerTrustLevel(serverId: String, trustLevel: TrustLevel): Boolean

    suspend fun updateServerScopes(serverId: String, scopes: FederationScope): Boolean

    suspend fun updateServerLastSync(serverId: String): Boolean

    // Federation Audit Log Operations

    suspend fun logFederationEvent(
        eventType: FederationEventType,
        serverId: String?,
        action: String,
        outcome: FederationOutcome,
        details: Map<String, Any?>?,
        errorMessage: String?,
        durationMs: Long?,
        remoteIp: String? = null
    ): FederationAuditLog

    suspend fun getAuditLogs(
        serverId: String? = null,
        eventType: FederationEventType? = null,
        limit: Int = 100
    ): List<FederationAuditLog>
}