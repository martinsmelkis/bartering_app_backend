package app.bartering.features.compliance.model

import kotlinx.serialization.Serializable

@Serializable
data class RetentionStatusResponse(
    val orchestratorIntervalHours: Long,
    val retentionDays: Map<String, Int>
)