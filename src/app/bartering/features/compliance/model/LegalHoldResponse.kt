package app.bartering.features.compliance.model

import kotlinx.serialization.Serializable

@Serializable
data class LegalHoldResponse(
    val id: Long,
    val userId: String,
    val reason: String,
    val scope: String,
    val imposedBy: String,
    val imposedAt: String,
    val expiresAt: String?
)