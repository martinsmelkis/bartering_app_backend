package app.bartering.features.profile.model

import kotlinx.serialization.Serializable

/**
 * Represents the payload from a client to create or update a user profile,
 * including privacy/consent preference updates.
 */
@Serializable
data class UserProfileUpdateRequest(
    val name: String? = "",
    val latitude: Double? = 0.0,
    val longitude: Double? = 0.0,
    val attributes: List<UserAttributeDto>? = emptyList(),
    val profileKeywordDataMap: Map<String, Double>? = emptyMap(),
    val preferredLanguage: String? = null, // ISO 639-1 code: "en", "fr", "lv", etc.
    val locationConsent: Boolean? = null,
    val aiProcessingConsent: Boolean? = null,
    val analyticsCookiesConsent: Boolean? = null,
    val federationConsent: Boolean? = null,
    val privacyPolicyVersion: String? = null
)