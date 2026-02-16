package app.bartering.features.authentication.dao

import app.bartering.features.authentication.model.DeviceKeyInfo
import app.bartering.features.authentication.model.UserInfoDto

interface AuthenticationDao {
    suspend fun getUserInfoById(id: String): UserInfoDto?
    suspend fun deleteUserAndAllData(userId: String): Boolean
    
    // === Device Key Management (Multi-Device Support) ===
    
    /**
     * Get a specific device key by user ID and device ID.
     */
    suspend fun getDeviceKey(userId: String, deviceId: String): DeviceKeyInfo?
    
    /**
     * Get all active device keys for a user.
     * Used for signature verification when deviceId is not provided.
     */
    suspend fun getAllActiveDeviceKeys(userId: String): List<DeviceKeyInfo>
    
    /**
     * Get all device keys (active and inactive) for a user.
     */
    suspend fun getAllDeviceKeys(userId: String): List<DeviceKeyInfo>
    
    /**
     * Register a new device key for a user.
     */
    suspend fun registerDeviceKey(deviceKey: DeviceKeyInfo): Boolean
    
    /**
     * Update the last_used_at timestamp for a device.
     */
    suspend fun updateDeviceLastUsed(userId: String, deviceId: String): Boolean
    
    /**
     * Deactivate/revoke a device key.
     */
    suspend fun deactivateDeviceKey(
        userId: String, 
        deviceId: String, 
        reason: String = "user_revoked"
    ): Boolean
    
    /**
     * Reactivate a previously deactivated device key.
     */
    suspend fun reactivateDeviceKey(userId: String, deviceId: String): Boolean
    
    /**
     * Update device information (name, etc.).
     */
    suspend fun updateDeviceInfo(userId: String, deviceId: String, deviceName: String?): Boolean
    
    /**
     * Get count of active devices for a user.
     */
    suspend fun getActiveDeviceCount(userId: String): Int
    
    /**
     * Check if a device is registered and active.
     */
    suspend fun isDeviceActive(userId: String, deviceId: String): Boolean
    
    /**
     * Migrate device key from source to target device (used in device migration).
     * Deactivates source device and creates new key for target.
     */
    suspend fun migrateDeviceKey(
        userId: String,
        sourceDeviceId: String,
        targetDeviceId: String,
        targetPublicKey: String,
        targetDeviceName: String? = null,
        targetDeviceType: String? = null,
        targetPlatform: String? = null
    ): Boolean
    
    /**
     * Log device activity for audit purposes.
     */
    suspend fun logDeviceActivity(
        userId: String,
        deviceId: String,
        activityType: String,
        ipAddress: String? = null
    )
}