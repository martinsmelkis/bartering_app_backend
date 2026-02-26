package app.bartering.features.federation.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Federation protocol message types and DTOs for server-to-server communication.
 */

/**
 * Handshake request to establish federation with another server.
 */
@Serializable
data class FederationHandshakeRequest(
    val serverId: String,
    val serverUrl: String,
    val serverName: String,
    val publicKey: String,
    val protocolVersion: String,
    val proposedScopes: FederationScope,
    val timestamp: Long,
    val signature: String // Signature of the request to verify authenticity
)

/**
 * Handshake response from the receiving server.
 */
@Serializable
data class FederationHandshakeResponse(
    val accepted: Boolean,
    val serverId: String,
    val serverUrl: String,
    val serverName: String,
    val publicKey: String,
    val protocolVersion: String,
    val acceptedScopes: FederationScope,
    val agreementHash: String,
    val timestamp: Long,
    val signature: String,
    val reason: String? = null // If rejected, explanation why
)

/**
 * Request to sync users from a federated server.
 */
@Serializable
data class UserSyncRequest(
    val requestingServerId: String,
    val page: Int = 0,
    val pageSize: Int = 50,
    @Serializable(with = InstantSerializer::class)
    val updatedSince: Instant?, // Only fetch users updated since this timestamp
    val timestamp: Long,
    val signature: String
)

/**
 * Response containing synced users.
 */
@Serializable
data class UserSyncResponse(
    val users: List<FederatedUserProfile>,
    val totalCount: Int,
    val page: Int,
    val hasMore: Boolean
)

/**
 * Request to search for users near a location.
 */
@Serializable
data class UserSearchRequest(
    val requestingServerId: String,
    val lat: Double,
    val lon: Double,
    val radiusKm: Double,
    val limit: Int = 50,
    val timestamp: Long,
    val signature: String
)

/**
 * Response with nearby users.
 */
@Serializable
data class UserSearchResponse(
    val users: List<FederatedUserProfile>,
    val count: Int
)

/**
 * Request to relay a message to a user on another server.
 */
@Serializable
data class MessageRelayRequest(
    val requestingServerId: String,
    val senderUserId: String,
    val recipientUserId: String,
    val encryptedPayload: String, // E2E encrypted message
    val senderPublicKey: String?, // Sender's public key for recipient to verify/decrypt
    val timestamp: Long,
    val signature: String
)

/**
 * Response to message relay.
 */
@Serializable
data class MessageRelayResponse(
    val delivered: Boolean,
    val messageId: String? = null,
    val reason: String? = null // If not delivered, why
)

/**
 * Response to posting search request.
 */
@Serializable
data class PostingSearchResponse(
    val postings: List<FederatedPostingData>,
    val count: Int,
    val hasMore: Boolean = false // Indicates if there are more results available
)

/**
 * Generic federation API response wrapper.
 */
@Serializable
data class FederationApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val timestamp: Long
)
