package app.bartering.features.profile.model

import kotlinx.serialization.Serializable

/**
 * Data Transfer Object representing a user's complete profile for client responses.
 */
@Serializable
data class UserProfile(
    val userId: String,
    val name: String,
    var latitude: Double?,
    var longitude: Double?,
    val attributes: List<UserAttributeDto>,
    val profileKeywordDataMap: Map<String, Double>?,
    val activePostingIds: List<String> = emptyList(),
    val lastOnlineAt: Long? = null, // Timestamp in milliseconds when user was last online
    val preferredLanguage: String = "en"
)


