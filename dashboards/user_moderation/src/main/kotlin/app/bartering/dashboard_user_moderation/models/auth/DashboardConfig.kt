package app.bartering.dashboard_user_moderation.models.auth

data class DashboardConfig(
    val backendBaseUrl: String,
    val adminCredentials: String,
    val secureCookies: Boolean,
    val adminUserId: String,
    val adminPrivateKeyHex: String,
    val sessionEncryptionKeyB64: String,
    val sessionSigningKeyB64: String,
    val sessionTtlSeconds: Long
)
