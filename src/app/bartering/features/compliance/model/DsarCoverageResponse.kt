package app.bartering.features.compliance.model

import kotlinx.serialization.Serializable

@Serializable
data class DsarCoverageResponse(
    val generatedAt: String,
    val userId: String,
    val coverage: List<DsarCoverageItem>
)

@Serializable
data class DsarCoverageItem(
    val domain: String,
    val table: String,
    val exportIncluded: Boolean,
    val deletionGuaranteedByCascade: Boolean,
    val retentionControlled: Boolean,
    val notes: String
)
