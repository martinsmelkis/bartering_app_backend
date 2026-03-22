package app.bartering.features.profile.model

import kotlinx.serialization.Serializable

/**
 * Dedicated request model for GDPR/privacy consent updates.
 */
@Serializable
data class UserConsentUpdateRequest(
    val userId: String,
    val locationConsent: Boolean? = null,
    val aiProcessingConsent: Boolean? = null,
    val analyticsCookiesConsent: Boolean? = null,
    val federationConsent: Boolean? = null,
    val privacyPolicyVersion: String? = null
)
