package app.bartering.features.compliance.model

import kotlinx.serialization.Serializable

@Serializable
data class DsarEvidenceResponse(
    val userId: String,
    val exportEvents: Int,
    val deletionEvents: Int,
    val consentEvents: Int,
    val legalHoldEvents: Int,
    val latestEvents: List<String>
)