package app.bartering.features.authentication.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Represents a device key stored for multi-device authentication.
 */
@Serializable
data class DeviceKeyInfo(
    val id: String,
    val userId: String,
    val deviceId: String,
    val publicKey: String,
    val deviceName: String? = null,
    val deviceType: String? = null,
    val platform: String? = null,
    val isActive: Boolean = true,
    val lastUsedAt: String? = null,
    val createdAt: String,
    val deactivatedAt: String? = null,
    val deactivatedReason: String? = null
)

/**
 * Request to register a new device key.
 */
@Serializable
data class RegisterDeviceRequest(
    val userId: String,
    val deviceId: String,
    val publicKey: String,
    val deviceName: String? = null,
    val deviceType: String? = null,
    val platform: String? = null
)

/**
 * Response after registering a device.
 */
@Serializable
data class RegisterDeviceResponse(
    val success: Boolean,
    val deviceKeyId: String? = null,
    val message: String,
    val deviceLimitReached: Boolean = false
)

/**
 * Request to revoke/deactivate a device.
 */
@Serializable
data class RevokeDeviceRequest(
    val deviceId: String,
    val reason: String? = "user_revoked"
)

/**
 * Response after revoking a device.
 */
@Serializable
data class RevokeDeviceResponse(
    val success: Boolean,
    val message: String
)

/**
 * Response listing all devices for a user.
 */
@Serializable
data class UserDevicesResponse(
    val success: Boolean,
    val devices: List<DeviceKeyInfo>,
    val activeDeviceCount: Int,
    val totalDeviceCount: Int
)

/**
 * Request to update device information (name, etc.).
 */
@Serializable
data class UpdateDeviceRequest(
    val deviceId: String,
    val deviceName: String? = null
)

/**
 * Response after updating device.
 */
@Serializable
data class UpdateDeviceResponse(
    val success: Boolean,
    val message: String
)

/**
 * Request to migrate device keys (used during device migration).
 * Transfers device registration from source to target device.
 */
@Serializable
data class MigrateDeviceRequest(
    val sourceDeviceId: String,
    val targetDeviceId: String,
    val targetPublicKey: String,
    val targetDeviceName: String? = null,
    val targetDeviceType: String? = null,
    val targetPlatform: String? = null,
    val sessionId: String? = null  // Migration session ID for verification
)

/**
 * Response after device migration.
 */
@Serializable
data class MigrateDeviceResponse(
    val success: Boolean,
    val newDeviceKeyId: String? = null,
    val sourceDeviceDeactivated: Boolean,
    val message: String
)

/**
 * Device activity audit log entry.
 */
@Serializable
data class DeviceActivityLog(
    val id: String,
    val userId: String,
    val deviceId: String,
    val activityType: String,
    val timestamp: String,
    val ipAddress: String? = null
)

/**
 * Constraints and constants for device management.
 */
object DeviceKeyConstraints {
    const val MAX_ACTIVE_DEVICES = 10
    const val MAX_DEVICES_PER_USER = 20  // Including inactive
    
    val VALID_DEVICE_TYPES = setOf("mobile", "tablet", "desktop", "web", "wearable", "other")
    val VALID_PLATFORMS = setOf("ios", "android", "windows", "macos", "linux", "web", "other")
    val VALID_DEACTIVATION_REASONS = setOf(
        "user_revoked", 
        "logout", 
        "security_concern", 
        "device_lost", 
        "migration",
        "admin_action",
        "key_compromise"
    )
}
