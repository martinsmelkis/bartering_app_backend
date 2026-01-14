package app.bartering.features.reviews.dao

import app.bartering.features.reviews.model.*
import java.time.Instant

/**
 * Data Access Object for risk pattern tracking and analysis.
 */
interface RiskPatternDao {
    
    // ========== Device Tracking ==========
    
    /**
     * Records a device fingerprint usage.
     */
    suspend fun trackDevice(data: DeviceTrackingData): Boolean
    
    /**
     * Gets all users who have used a specific device.
     */
    suspend fun getUsersByDevice(deviceFingerprint: String): List<String>
    
    /**
     * Gets all devices used by a specific user.
     */
    suspend fun getDevicesByUser(userId: String): List<String>
    
    /**
     * Gets device pattern analysis for a fingerprint.
     */
    suspend fun analyzeDevicePattern(deviceFingerprint: String): DevicePatternAnalysis?
    
    /**
     * Checks if two users share the same device.
     */
    suspend fun shareDevice(user1Id: String, user2Id: String): Boolean
    
    // ========== IP Tracking ==========
    
    /**
     * Records an IP address usage.
     */
    suspend fun trackIp(data: IpTrackingData): Boolean
    
    /**
     * Gets all users who have used a specific IP.
     */
    suspend fun getUsersByIp(ipAddress: String): List<String>
    
    /**
     * Gets all IPs used by a specific user.
     */
    suspend fun getIpsByUser(userId: String): List<String>
    
    /**
     * Gets IP pattern analysis for an address.
     */
    suspend fun analyzeIpPattern(ipAddress: String): IpPatternAnalysis?
    
    /**
     * Checks if two users share the same IP.
     */
    suspend fun shareIp(user1Id: String, user2Id: String): Boolean
    
    /**
     * Updates IP metadata (VPN, proxy detection, geolocation).
     */
    suspend fun updateIpMetadata(
        ipAddress: String,
        isVpn: Boolean = false,
        isProxy: Boolean = false,
        isTor: Boolean = false,
        isDataCenter: Boolean = false,
        country: String? = null,
        city: String? = null,
        isp: String? = null
    ): Boolean
    
    // ========== Location Change Tracking ==========
    
    /**
     * Records a user profile location change.
     */
    suspend fun trackLocationChange(data: LocationChangeData): Boolean
    
    /**
     * Gets recent location changes for a user.
     */
    suspend fun getUserLocationChanges(userId: String, limit: Int = 100): List<LocationChange>
    
    /**
     * Analyzes location change patterns for a user.
     */
    suspend fun analyzeLocationChangePattern(userId: String): LocationChangePatternAnalysis?
    
    /**
     * Calculates distance between two users' current profile locations.
     */
    suspend fun calculateLocationDistance(user1Id: String, user2Id: String): Double?
    
    /**
     * Gets users who changed to a specific location within a time window.
     */
    suspend fun getUsersByLocationChange(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        timeRangeMinutes: Int
    ): List<String>
    
    /**
     * Detects coordinated location changes (multiple users moving to same location).
     */
    suspend fun detectCoordinatedLocationChanges(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        timeWindowMinutes: Int,
        thresholdUsers: Int
    ): List<String>
    
    // ========== Risk Pattern Detection ==========
    
    /**
     * Records a detected suspicious pattern.
     */
    suspend fun recordSuspiciousPattern(pattern: SuspiciousPattern): Boolean
    
    /**
     * Gets all active (unresolved) suspicious patterns.
     */
    suspend fun getActiveSuspiciousPatterns(): List<SuspiciousPattern>
    
    /**
     * Gets suspicious patterns for a specific user.
     */
    suspend fun getUserSuspiciousPatterns(userId: String): List<SuspiciousPattern>
    
    /**
     * Updates pattern status (resolved, false positive, etc.).
     */
    suspend fun updatePatternStatus(
        patternId: String,
        status: String,
        reviewedBy: String? = null,
        notes: String? = null
    ): Boolean
    
    // ========== Behavior Analysis ==========
    
    /**
     * Gets behavior metrics for a user.
     */
    suspend fun getBehaviorMetrics(userId: String): BehaviorAnalysisMetrics?
    
    /**
     * Gets contact information overlap between users.
     */
    suspend fun shareContactInfo(user1Id: String, user2Id: String): Boolean
    
    // ========== Data Cleanup & Maintenance ==========
    
    /**
     * Deletes device tracking records older than the specified number of days.
     * Helps maintain database performance and comply with data retention policies.
     * 
     * @param olderThanDays Delete records older than this many days
     * @return Number of records deleted
     */
    suspend fun cleanupOldDeviceTracking(olderThanDays: Int): Int
    
    /**
     * Deletes IP tracking records older than the specified number of days.
     * 
     * @param olderThanDays Delete records older than this many days
     * @return Number of records deleted
     */
    suspend fun cleanupOldIpTracking(olderThanDays: Int): Int
    
    /**
     * Deletes location change records older than the specified number of days.
     * 
     * @param olderThanDays Delete records older than this many days
     * @return Number of records deleted
     */
    suspend fun cleanupOldLocationChanges(olderThanDays: Int): Int
    
    /**
     * Deletes risk pattern records older than the specified number of days.
     * Risk patterns can be kept longer for trend analysis.
     * 
     * @param olderThanDays Delete records older than this many days
     * @return Number of records deleted
     */
    suspend fun cleanupOldRiskPatterns(olderThanDays: Int): Int
}
