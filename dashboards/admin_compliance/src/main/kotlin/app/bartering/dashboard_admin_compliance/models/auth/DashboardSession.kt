package app.bartering.dashboard_admin_compliance.models.auth

import kotlinx.serialization.Serializable

@Serializable
data class DashboardSession(
    val username: String,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long
)