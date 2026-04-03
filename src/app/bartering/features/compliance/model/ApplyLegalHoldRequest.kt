package app.bartering.features.compliance.model

import kotlinx.serialization.Serializable

@Serializable
data class ApplyLegalHoldRequest(
    val userId: String,
    val reason: String,
    val scope: String = "all",
    val expiresAt: String? = null
)