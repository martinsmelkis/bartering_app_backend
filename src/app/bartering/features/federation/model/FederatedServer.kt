package app.bartering.features.federation.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Data class representing a federated server that this instance can communicate with.
 * Note: This is primarily used for database operations, not API serialization.
 */
data class FederatedServer(
    val serverId: String,
    val serverUrl: String,
    val serverName: String?,
    val publicKey: String,
    val trustLevel: TrustLevel,
    val scopePermissions: FederationScope,
    val federationAgreementHash: String?,
    val lastSyncTimestamp: Instant?,
    val serverMetadata: Map<String, String>?, // Database-only field (JSONB)
    val protocolVersion: String,
    val isActive: Boolean,
    val dataRetentionDays: Int,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * Trust levels for federated servers.
 */
enum class TrustLevel {
    /** Full trust - all scopes enabled, minimal verification */
    FULL,
    
    /** Partial trust - some scopes enabled, moderate verification */
    PARTIAL,
    
    /** Pending approval - handshake initiated but not confirmed */
    PENDING,
    
    /** Blocked - no communication allowed */
    BLOCKED
}

/**
 * Defines what data and features are shared with a federated server.
 */
@Serializable
data class FederationScope(
    val users: Boolean = false,
    val postings: Boolean = false,
    val chat: Boolean = false,
    val geolocation: Boolean = false,
    val attributes: Boolean = false
) {
    companion object {
        val NONE = FederationScope()
        val ALL = FederationScope(
            users = true,
            postings = true,
            chat = true,
            geolocation = true,
            attributes = true
        )
    }
}
