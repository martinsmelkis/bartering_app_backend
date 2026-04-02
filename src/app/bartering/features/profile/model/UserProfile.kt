package app.bartering.features.profile.model

import app.bartering.features.reviews.model.AccountType
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
    val selfDescription: String? = null,
    val accountType: AccountType? = null,
    // Inline SVG text content for avatar icon
    val profileAvatarIcon: String? = null,
    val workReferenceImageUrls: List<String> = emptyList(),
    val activePostingIds: List<String> = emptyList(),
    val lastOnlineAt: Long? = null, // Timestamp in milliseconds when user was last online
    val preferredLanguage: String = "en"
)


