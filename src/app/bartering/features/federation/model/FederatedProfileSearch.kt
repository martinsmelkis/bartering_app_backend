package app.bartering.features.federation.model

import kotlinx.serialization.Serializable

/**
 * Request to search for users by keyword on a federated server.
 */
@Serializable
data class ProfileSearchRequest(
    val requestingServerId: String,
    val query: String,
    val limit: Int = 20,
    val timestamp: Long,
    val signature: String
)

/**
 * Response with matching user profiles from federated search.
 */
@Serializable
data class ProfileSearchResponse(
    val users: List<FederatedUserProfile>,
    val count: Int,
    val serverId: String,  // Source server
    val serverName: String?
)
