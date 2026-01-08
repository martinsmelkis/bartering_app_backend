package org.barter.features.reviews.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Risk level classification.
 */
enum class RiskLevel {
    MINIMAL,    // 0.0 - 0.2
    LOW,        // 0.2 - 0.4
    MEDIUM,     // 0.4 - 0.6
    HIGH,       // 0.6 - 0.8
    CRITICAL    // 0.8 - 1.0
}

/**
 * Device fingerprint pattern analysis result.
 */
data class DevicePatternAnalysis(
    val deviceFingerprint: String,
    val associatedUserIds: List<String>,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val totalAccounts: Int,
    val suspiciousActivity: Boolean,
    val deviceType: String? = null,
    val operatingSystem: String? = null,
    val browser: String? = null
)

/**
 * IP address pattern analysis result.
 */
data class IpPatternAnalysis(
    val ipAddress: String,
    val associatedUserIds: List<String>,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val totalAccounts: Int,
    val isVpn: Boolean,
    val isProxy: Boolean,
    val isTor: Boolean,
    val isDataCenter: Boolean,
    val country: String? = null,
    val city: String? = null,
    val isp: String? = null,
    val suspiciousActivity: Boolean
)

/**
 * Device tracking data.
 */
data class DeviceTrackingData(
    val deviceFingerprint: String,
    val userId: String,
    val ipAddress: String,
    val userAgent: String? = null,
    val timestamp: Instant,
    val action: String
)

/**
 * IP address tracking data.
 */
data class IpTrackingData(
    val ipAddress: String,
    val userId: String,
    val timestamp: Instant,
    val action: String
)

/**
 * Profile location change data.
 */
data class LocationChangeData(
    val userId: String,
    val oldLatitude: Double?,
    val oldLongitude: Double?,
    val newLatitude: Double,
    val newLongitude: Double,
    val changedAt: Instant
)

/**
 * Location change record.
 */
data class LocationChange(
    val id: String,
    val userId: String,
    val oldLatitude: Double?,
    val oldLongitude: Double?,
    val newLatitude: Double,
    val newLongitude: Double,
    val changedAt: Instant
)

/**
 * Location change pattern analysis result.
 */
data class LocationChangePatternAnalysis(
    val userId: String,
    val changes: List<LocationChange>,
    val suspiciousPatterns: List<String>,
    val averageDistanceBetweenChanges: Double?, // in kilometers
    val maxDistanceBetweenConsecutiveChanges: Double?, // in kilometers
    val locationHoppingDetected: Boolean,
    val impossibleMovementDetected: Boolean,
    val frequentChangesDetected: Boolean
)

/**
 * Comprehensive risk analysis report.
 */
@Serializable
data class RiskAnalysisReport(
    val transactionId: String,
    val user1Id: String,
    val user2Id: String,
    val overallRiskScore: Double,
    val riskLevel: String,
    val deviceRiskScore: Double,
    val ipRiskScore: Double,
    val locationRiskScore: Double,
    val behaviorRiskScore: Double,
    val detectedPatterns: List<String>,
    val recommendations: List<String>,
    val requiresManualReview: Boolean,
    val analysisTimestamp: Long
)

/**
 * Suspicious pattern detection result.
 */
data class SuspiciousPattern(
    val type: PatternType,
    val description: String,
    val severity: PatternSeverity,
    val affectedUsers: List<String>,
    val evidence: Map<String, String>
)

/**
 * Pattern types for abuse detection.
 */
enum class PatternType {
    DEVICE_SHARING,
    IP_SHARING,
    LOCATION_SPOOFING,
    LOCATION_HOPPING,
    COORDINATED_LOCATION_CHANGE,
    RAPID_ACCOUNT_CREATION,
    WASH_TRADING,
    COORDINATED_REVIEWS,
    VPN_ABUSE,
    MULTIPLE_ACCOUNTS
}

/**
 * Pattern severity levels.
 */
enum class PatternSeverity {
    INFO,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * Behavior analysis metrics.
 */
data class BehaviorAnalysisMetrics(
    val userId: String,
    val accountAge: Long, // in days
    val totalTransactions: Int,
    val completedTransactions: Int,
    val cancelledTransactions: Int,
    val averageReviewsReceived: Double,
    val diversityScore: Double,
    val suspiciousBehaviorFlags: List<String>
)
