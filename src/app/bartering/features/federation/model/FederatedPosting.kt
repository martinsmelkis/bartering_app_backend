package app.bartering.features.federation.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.math.BigDecimal
import java.time.Instant

/**
 * Represents a posting from a federated server.
 * Note: This is primarily used for database operations, not API serialization.
 */
data class FederatedPosting(
    val localPostingId: String?,
    val remotePostingId: String,
    val originServerId: String,
    val remoteUserId: String,
    val cachedData: Map<String, Any?>, // Database-only field (JSONB)
    val remoteUrl: String?,
    val isActive: Boolean,
    val expiresAt: Instant?,
    val lastSynced: Instant,
    val dataHash: String?,
    val createdAt: Instant
)

/**
 * Posting data that gets shared across federated servers.
 * This is what gets cached in cachedData field.
 */
@Serializable
data class FederatedPostingData(
    val postingId: String,
    val userId: String,
    val title: String,
    val description: String,
    @Serializable(with = BigDecimalSerializer::class)
    val value: BigDecimal? = null,
    val imageUrls: List<String> = emptyList(),
    val isOffer: Boolean,
    val status: String,
    val attributes: List<String>? = null, // List of attribute IDs
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantSerializer::class)
    val expiresAt: Instant? = null
)
