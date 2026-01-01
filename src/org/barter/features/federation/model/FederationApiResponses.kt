package org.barter.features.federation.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Server initialization response
 */
@Serializable
data class InitializeServerResponse(
    val success: Boolean,
    val serverId: String? = null,
    val serverUrl: String? = null,
    val serverName: String? = null,
    val message: String? = null,
    val error: String? = null
)

/**
 * Federated server info for list responses
 */
@Serializable
data class FederatedServerInfo(
    val serverId: String,
    val serverUrl: String,
    val serverName: String?,
    val trustLevel: String,
    val scopePermissions: FederationScope,
    val protocolVersion: String,
    val isActive: Boolean,
    @Serializable(with = InstantSerializer::class) val lastSyncTimestamp: Instant?,
    val federationAgreementHash: String?,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant
)

/**
 * List of federated servers response
 */
@Serializable
data class FederatedServersListResponse(
    val success: Boolean,
    val count: Int,
    val servers: List<FederatedServerInfo>,
    val error: String? = null
)

/**
 * Trust level update response
 */
@Serializable
data class UpdateTrustLevelResponse(
    val success: Boolean,
    val serverId: String? = null,
    val trustLevel: String? = null,
    val message: String? = null,
    val error: String? = null
)

/**
 * Handshake initiation response
 */
@Serializable
data class InitiateHandshakeResponse(
    val success: Boolean,
    val response: FederationHandshakeResponse? = null,
    val message: String? = null,
    val error: String? = null
)

/**
 * Initialize server request
 */
@Serializable
data class InitializeServerRequest(
    val serverUrl: String,
    val serverName: String,
    val adminContact: String? = null,
    val description: String? = null,
    val locationHint: String? = null
)

/**
 * Update trust level request
 */
@Serializable
data class UpdateTrustLevelRequest(
    val trustLevel: String
)

/**
 * Initiate handshake request
 */
@Serializable
data class InitiateHandshakeRequest(
    val targetServerUrl: String,
    val proposedScopes: FederationScope
)

/**
 * Generic admin error response
 */
@Serializable
data class AdminErrorResponse(
    val success: Boolean = false,
    val error: String
)