package app.bartering.features.compliance.model

import kotlinx.serialization.Serializable

@Serializable
data class ReleaseLegalHoldRequest(
    val holdId: Long,
    val reason: String? = null
)