package org.barter.features.profile.model

import kotlinx.serialization.Serializable

/**
 * Represents the payload from a client to create or update a user profile.
 */
@Serializable
data class UserProfileUpdateRequest(
    val name: String? = "",
    val latitude: Double? = 0.0,
    val longitude: Double? = 0.0,
    val attributes: List<UserAttributeDto>? = emptyList(),
    val profileKeywordDataMap: Map<String, Double>? = emptyMap()
)