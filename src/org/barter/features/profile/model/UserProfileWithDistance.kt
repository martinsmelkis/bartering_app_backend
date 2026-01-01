package org.barter.features.profile.model

import kotlinx.serialization.Serializable

@Serializable
 data class UserProfileWithDistance(
     val profile: UserProfile,
     val distanceKm: Double,
     val matchRelevancyScore: Double?
 )