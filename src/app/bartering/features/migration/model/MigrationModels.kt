package app.bartering.features.migration.model

import kotlinx.serialization.Serializable

/**
 * Represents the status of a migration session.
 */
enum class MigrationSessionStatus {
    PENDING,              // Session created, waiting for target device
    AWAITING_CONFIRMATION, // Target joined, waiting for source confirmation
    TRANSFERRING,         // Data transfer in progress
    COMPLETED,            // Migration completed successfully
    EXPIRED,              // Session timed out
    CANCELLED             // Cancelled by user or system
}

/**
 * Request to register a target device for a migration session.
 * Called by the target device when user enters the migration code.
 */
@Serializable
data class RegisterMigrationTargetRequest(
    val sessionId: String,        // The 10-character session code (e.g., "X7B9K2M4P1")
    val targetDeviceId: String,   // Target device fingerprint/ID
    val targetPublicKey: String   // Target's ephemeral ECDH public key (Base64)
)

/**
 * Response after target device registers for migration.
 */
@Serializable
data class RegisterMigrationTargetResponse(
    val success: Boolean,
    val sourceDeviceId: String? = null,
    val userId: String? = null,
    val requiresConfirmation: Boolean = true,
    val errorMessage: String? = null
)

/**
 * Request to get a device's public key for signature verification.
 */
@Serializable
data class GetMigrationPublicKeyRequest(
    val sessionId: String,
    val deviceId: String
)

/**
 * Response containing a device's public key.
 */
@Serializable
data class GetMigrationPublicKeyResponse(
    val success: Boolean,
    val publicKey: String? = null,
    val errorMessage: String? = null
)

/**
 * Encrypted migration payload data structure.
 */
@Serializable
data class EncryptedMigrationPayloadData(
    val encryptedData: String,           // Base64 encrypted data (salt + IV + ciphertext)
    val ephemeralPublicKey: String,    // Source's ephemeral ECDH public key (Base64)
    val signature: String,             // ECDSA signature of the encrypted payload
    val sourceDeviceId: String,
    val targetDeviceId: String,
    val sessionId: String,
    val keyVersion: Int = 1,
    val sourceSigningPublicKey: String? = null  // Source device's main signing key (for sig verification)
)

/**
 * Request to send encrypted migration payload.
 * Called by source device after user confirms the migration.
 */
@Serializable
data class SendMigrationPayloadRequest(
    val sessionId: String,
    val encryptedPayload: EncryptedMigrationPayloadData
)

/**
 * Response after sending migration payload.
 */
@Serializable
data class SendMigrationPayloadResponse(
    val success: Boolean,
    val message: String? = null
)

/**
 * Request to confirm migration completion.
 * Called by target device after successfully receiving and decrypting data.
 */
@Serializable
data class ConfirmMigrationRequest(
    val sessionId: String,
    val targetDeviceId: String
)

/**
 * Response after confirming migration completion.
 */
@Serializable
data class ConfirmMigrationResponse(
    val success: Boolean,
    val message: String? = null
)

/**
 * Internal data class representing a migration session in the database.
 */
data class MigrationSession(
    val id: String,
    val sessionCode: String,
    val userId: String?,                    // NULL for backward compatibility (target creates first)
    val sourceDeviceId: String?,            // NULL until source sends payload
    val sourceDeviceKeyId: String?,
    val sourcePublicKey: String?,     // Ephemeral ECDH key
    val targetDeviceId: String?,
    val targetDeviceKeyId: String?,
    val targetPublicKey: String?,     // Ephemeral ECDH key
    val status: MigrationSessionStatus,
    val encryptedPayload: String?,
    val payloadCreatedAt: java.time.Instant?,
    val createdAt: java.time.Instant,
    val expiresAt: java.time.Instant,
    val completedAt: java.time.Instant?,
    val attemptCount: Int
) {
    val isExpired: Boolean
        get() = java.time.Instant.now().isAfter(expiresAt)
    
    val isActive: Boolean
        get() = status in listOf(
            MigrationSessionStatus.PENDING, 
            MigrationSessionStatus.AWAITING_CONFIRMATION, 
            MigrationSessionStatus.TRANSFERRING
        ) && !isExpired
}

/**
 * Request to initiate a migration session (from source device).
 * This is called internally by the device management service.
 */
@Serializable
data class InitiateMigrationRequest(
    val userId: String,
    val sourceDeviceId: String,
    val sourcePublicKey: String  // Ephemeral ECDH public key
)

/**
 * Response after initiating a migration session.
 */
@Serializable
data class InitiateMigrationResponse(
    val success: Boolean,
    val sessionId: String? = null,      // The 10-character code
    val expiresAt: String? = null,    // ISO 8601 timestamp
    val errorMessage: String? = null
)

/**
 * Response for getting migration session status.
 */
@Serializable
data class MigrationSessionStatusResponse(
    val success: Boolean,
    val sessionId: String? = null,
    val status: String? = null,
    val sourceDeviceId: String? = null,
    val targetDeviceId: String? = null,
    val targetPublicKey: String? = null,  // Added for source device to encrypt payload
    val createdAt: String? = null,
    val expiresAt: String? = null,
    val errorMessage: String? = null
)

/**
 * Constraints and constants for migration sessions.
 */
object MigrationConstraints {
    const val SESSION_CODE_LENGTH = 10
    const val SESSION_EXPIRY_MINUTES = 15
    const val PAYLOAD_MAX_AGE_MINUTES = 5
    const val MAX_ACTIVE_SESSIONS_PER_USER = 3
    const val MAX_ATTEMPTS_PER_SESSION = 5
    const val RATE_LIMIT_WINDOW_MINUTES = 15
}
