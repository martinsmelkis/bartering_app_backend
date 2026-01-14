package app.bartering.features.federation.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Instant

/**
 * Represents a user from a federated server.
 * Note: This is primarily used for database operations, not API serialization.
 */
data class FederatedUser(
    val localUserId: String?,
    val remoteUserId: String,
    val originServerId: String,
    val federatedUserId: String, // Full address like "user123@barter.example.com"
    val cachedProfileData: Map<String, Any?>?, // Database-only field (JSONB)
    val publicKey: String?,
    val federationEnabled: Boolean,
    val lastUpdated: Instant,
    val lastOnline: Instant?,
    val expiresAt: Instant?,
    val createdAt: Instant
)

/**
 * Lightweight user profile data for federation.
 * This is what gets cached in cachedProfileData field.
 */
@Serializable
data class FederatedUserProfile(
    val userId: String,
    val name: String?,
    val bio: String?,
    val profileImageUrl: String?,
    val location: FederatedLocation?,
    val attributes: List<String>?, // List of attribute IDs the user has
    @Serializable(with = InstantSerializer::class)
    val lastOnline: Instant?
)

/**
 * Location data for federated users (if geolocation scope is enabled).
 */
@Serializable
data class FederatedLocation(
    val lat: Double,
    val lon: Double,
    val city: String?,
    val country: String?
)
