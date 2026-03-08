package app.bartering.features.migration.model

import kotlinx.serialization.Serializable

/**
 * Migration event types for audit logging.
 */
object MigrationEventType {
    const val INITIATED = "migration_initiated"
    const val CODE_SENT = "code_sent"
    const val CODE_VERIFIED = "code_verified"
    const val TARGET_REGISTERED = "target_registered"
    const val PAYLOAD_STORED = "payload_stored"
    const val COMPLETED = "migration_completed"
    const val FAILED = "migration_failed"
    const val CANCELLED = "migration_cancelled"
}

/**
 * Migration type constants.
 */
object MigrationType {
    const val DEVICE_TO_DEVICE = "device_to_device"
    const val EMAIL_RECOVERY = "email_recovery"
}

// ============================================================================
// EMAIL RECOVERY REQUESTS/RESPONSES
// ============================================================================

@Serializable
data class InitiateRecoveryRequest(
    val userId: String,
    val email: String? = null,
    val newDeviceId: String,
    val newDevicePublicKey: String
)

@Serializable
data class InitiateRecoveryResponse(
    val success: Boolean,
    val sessionId: String? = null,
    val message: String,
    val emailMasked: String? = null,
    val expiresAt: String? = null,
    val errorMessage: String? = null
)

@Serializable
data class VerifyRecoveryCodeRequest(
    val sessionId: String,
    val recoveryCode: String,
    val newDeviceId: String,
    val newDevicePublicKey: String
)

@Serializable
data class VerifyRecoveryCodeResponse(
    val success: Boolean,
    val message: String,
    val errorMessage: String? = null
)

// ============================================================================
// DEVICE MIGRATION REQUESTS/RESPONSES
// ============================================================================

@Serializable
data class InitiateMigrationRequest(
    val userId: String,
    val sourceDeviceId: String,
    val sourcePublicKey: String
)

@Serializable
data class InitiateMigrationResponse(
    val success: Boolean,
    val sessionCode: String? = null,
    val expiresAt: String? = null,
    val errorMessage: String? = null
)

@Serializable
data class RegisterMigrationTargetRequest(
    val sessionCode: String,
    val targetDeviceId: String,
    val targetPublicKey: String
)

@Serializable
data class RegisterMigrationTargetResponse(
    val success: Boolean,
    val sessionId: String? = null,
    val sourceDeviceId: String? = null,
    val targetPublicKey: String? = null,
    val userId: String? = null,
    val errorMessage: String? = null
)

@Serializable
data class SendMigrationPayloadRequest(
    val sessionId: String,
    val encryptedPayload: EncryptedMigrationPayloadData
)

@Serializable
data class GetMigrationPayloadResponse(
    val success: Boolean,
    val encryptedPayload: EncryptedMigrationPayloadData,
    val errorMessage: String? = null
)

@Serializable
data class EncryptedMigrationPayloadData(
    val encryptedData: String,
    val ephemeralPublicKey: String,
    val signature: String,
    val sourceDeviceId: String,
    val targetDeviceId: String,
    val sessionId: String,
    val keyVersion: Int = 1,
    val sourceSigningPublicKey: String? = null
)

// ============================================================================
// COMMON REQUESTS/RESPONSES
// ============================================================================

@Serializable
data class CompleteMigrationRequest(
    val sessionId: String,
    val newDeviceId: String,
    val devicePublicKey: String,
    val deviceName: String? = null
)

@Serializable
data class CompleteMigrationResponse(
    val success: Boolean,
    val message: String,
    val userId: String? = null,
    val warning: String? = null,
    val errorMessage: String? = null
)

@Serializable
data class CancelMigrationRequest(
    val sessionId: String
)

@Serializable
data class CancelMigrationResponse(
    val success: Boolean,
    val message: String
)

@Serializable
data class MigrationStatusResponse(
    val success: Boolean,
    val sessionId: String? = null,
    val type: String? = null,
    val status: String? = null,
    val targetPublicKey: String? = null,
    val attemptsRemaining: Int? = null,
    val expiresAt: String? = null,
    val errorMessage: String? = null
)

// ============================================================================
// CONSTANTS
// ============================================================================

object MigrationConstraints {
    const val RECOVERY_CODE_LENGTH = 8
    const val RECOVERY_EXPIRY_HOURS = 24
    const val MIGRATION_EXPIRY_MINUTES = 15
    const val MAX_ACTIVE_SESSIONS = 2
    const val MAX_ATTEMPTS = 5
    const val RATE_LIMIT_WINDOW_MINUTES = 60
    const val RATE_LIMIT_MAX_ATTEMPTS = 5
}
