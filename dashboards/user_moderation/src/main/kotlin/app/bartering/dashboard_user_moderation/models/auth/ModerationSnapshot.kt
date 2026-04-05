package app.bartering.dashboard_user_moderation.models.auth

import app.bartering.dashboard_user_moderation.models.moderation.UserModerationRow

data class ModerationSnapshot(
    val connected: Boolean,
    val backendStatus: String,
    val rows: List<UserModerationRow>,
    val connectionError: String?
)
