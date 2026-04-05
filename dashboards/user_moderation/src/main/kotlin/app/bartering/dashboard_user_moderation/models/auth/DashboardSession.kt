package app.bartering.dashboard_user_moderation.models.auth

import kotlinx.serialization.Serializable

@Serializable
data class DashboardSession(
    val username: String,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long
)
