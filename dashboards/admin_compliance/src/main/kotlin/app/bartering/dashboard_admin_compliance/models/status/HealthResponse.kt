package app.bartering.dashboard_admin_compliance.models.status

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    @SerialName("status") val status: String? = null,
    @SerialName("healthy") val healthy: Boolean? = null,
    @SerialName("message") val message: String? = null
)