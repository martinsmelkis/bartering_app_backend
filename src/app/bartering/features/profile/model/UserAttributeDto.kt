package app.bartering.features.profile.model

import kotlinx.serialization.Serializable

/**
 * Data Transfer Object representing a single user attribute for client communication.
 */
@Serializable
data class UserAttributeDto(
    val attributeId: String,
    val type: Int, // Mapped to UserAttributeType enum
    val relevancy: Double,
    val description: String? = null,
    val uiStyleHint: String? = null
)