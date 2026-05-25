package app.bartering.features.nearbyalerts.model

import app.bartering.features.postings.model.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class NearbyUserAlert(
    val id: String,
    val userId: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val minUserCount: Int = 5,
    val enabled: Boolean = true,
    @Serializable(with = InstantSerializer::class)
    val lastCheckedAt: Instant? = null,
    @Serializable(with = InstantSerializer::class)
    val lastNotifiedAt: Instant? = null,
    val lastNearbyUserCount: Int = 0,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant
)

@Serializable
data class UpsertNearbyUserAlertRequest(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double = 10_000.0,
    val minUserCount: Int = 5,
    val enabled: Boolean = true
)

@Serializable
data class NearbyUserAlertResponse(
    val alert: NearbyUserAlert? = null,
    val currentNearbyUserCount: Int? = null
)

@Serializable
data class NearbyUserAlertOperationResponse(
    val success: Boolean,
    val message: String,
    val alert: NearbyUserAlert? = null
)
