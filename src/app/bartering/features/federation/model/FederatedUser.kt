package app.bartering.features.federation.model

import kotlinx.serialization.Serializable
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
    val cachedProfileData: CachedFederatedProfileData?, // Database-only field (JSONB)
    val publicKey: String?,
    val federationEnabled: Boolean,
    val lastUpdated: Instant,
    val lastOnline: Instant?,
    val expiresAt: Instant?,
    val createdAt: Instant
)

/**
 * Cached profile data structure for database storage.
 * Serializable version of FederatedUserProfile for JSONB storage.
 */
@Serializable
data class CachedFederatedProfileData(
    val userId: String,
    val name: String? = null,
    val bio: String? = null,
    val profileImageUrl: String? = null,
    val location: FederatedLocation? = null,
    val attributes: List<FederatedAttribute>? = null,
    val lastOnline: String? = null, // ISO-8601 string format
    val publicKey: String? = null
)

/**
 * Lightweight user profile data for federation.
 * This is what gets cached in cachedProfileData field.
 */
@Serializable
data class FederatedUserProfile(
    val userId: String,
    val name: String? = null,
    val bio: String? = null,
    val profileImageUrl: String? = null,
    val location: FederatedLocation? = null,
    val attributes: List<FederatedAttribute>? = null, // List of attributes with type info
    @Serializable(with = InstantSerializer::class)
    val lastOnline: Instant? = null,
    val publicKey: String? = null // Public key for E2E encrypted chat
)

/**
 * Location data for federated users (if geolocation scope is enabled).
 */
@Serializable
data class FederatedLocation(
    val lat: Double,
    val lon: Double,
    val city: String? = null,
    val country: String? = null
)

/**
 * Federated attribute with type information.
 */
@Serializable
data class FederatedAttribute(
    val attributeId: String,
    val type: Int, // 0 = SEEKING, 1 = PROVIDING
    val relevancy: Double = 0.5
)
