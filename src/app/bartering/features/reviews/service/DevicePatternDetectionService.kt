package app.bartering.features.reviews.service

import app.bartering.features.reviews.dao.RiskPatternDao
import app.bartering.features.reviews.model.*
import java.time.Duration
import java.time.Instant

/**
 * Service for detecting suspicious device usage patterns.
 * Identifies multi-accounting and device sharing abuse.
 */
class DevicePatternDetectionService(
    private val riskPatternDao: RiskPatternDao
) {
    
    /**
     * Analyzes device usage patterns for a user.
     */
    suspend fun analyzeUserDevices(userId: String): List<SuspiciousPattern> {
        val patterns = mutableListOf<SuspiciousPattern>()
        val devices = riskPatternDao.getDevicesByUser(userId)
        
        // Check for excessive device switching
        if (devices.size > 5) {
            patterns.add(
                SuspiciousPattern(
                    type = PatternType.MULTIPLE_ACCOUNTS,
                    description = "User has used ${devices.size} different devices",
                    severity = PatternSeverity.MEDIUM,
                    affectedUsers = listOf(userId),
                    evidence = mapOf(
                        "device_count" to devices.size.toString(),
                        "reason" to "Excessive device switching may indicate account sharing"
                    )
                )
            )
        }
        
        // Check each device for multi-user usage
        for (device in devices) {
            val analysis = riskPatternDao.analyzeDevicePattern(device) ?: continue
            
            if (analysis.suspiciousActivity && analysis.totalAccounts > 1) {
                patterns.add(
                    SuspiciousPattern(
                        type = PatternType.DEVICE_SHARING,
                        description = "Device ${device.take(8)}... used by ${analysis.totalAccounts} accounts",
                        severity = when {
                            analysis.totalAccounts > 5 -> PatternSeverity.CRITICAL
                            analysis.totalAccounts > 3 -> PatternSeverity.HIGH
                            else -> PatternSeverity.MEDIUM
                        },
                        affectedUsers = analysis.associatedUserIds,
                        evidence = mapOf(
                            "device_fingerprint" to device,
                            "account_count" to analysis.totalAccounts.toString(),
                            "first_seen" to analysis.firstSeenAt.toString(),
                            "last_seen" to analysis.lastSeenAt.toString(),
                            "device_type" to (analysis.deviceType ?: "unknown"),
                            "os" to (analysis.operatingSystem ?: "unknown")
                        )
                    )
                )
            }
        }
        
        return patterns
    }
    
    /**
     * Checks if two users share a device (potential Sybil attack).
     */
    suspend fun checkDeviceSharing(user1Id: String, user2Id: String): SuspiciousPattern? {
        val shareDevice = riskPatternDao.shareDevice(user1Id, user2Id)
        
        if (!shareDevice) return null
        
        val sharedDevices = findSharedDevices(user1Id, user2Id)
        
        return SuspiciousPattern(
            type = PatternType.DEVICE_SHARING,
            description = "Users share ${sharedDevices.size} device(s)",
            severity = when {
                sharedDevices.size > 2 -> PatternSeverity.CRITICAL
                sharedDevices.size > 1 -> PatternSeverity.HIGH
                else -> PatternSeverity.MEDIUM
            },
            affectedUsers = listOf(user1Id, user2Id),
            evidence = mapOf(
                "shared_devices" to sharedDevices.joinToString(",") { it.take(8) },
                "device_count" to sharedDevices.size.toString(),
                "risk" to "Potential Sybil attack or wash trading"
            )
        )
    }
    
    /**
     * Detects rapid account creation from same device.
     */
    suspend fun detectRapidAccountCreation(deviceFingerprint: String): SuspiciousPattern? {
        val analysis = riskPatternDao.analyzeDevicePattern(deviceFingerprint) ?: return null
        
        if (analysis.totalAccounts < 3) return null
        
        val timeSpan = Duration.between(analysis.firstSeenAt, analysis.lastSeenAt)
        val accountsPerDay = analysis.totalAccounts.toDouble() / timeSpan.toDays().coerceAtLeast(1)
        
        // More than 1 account per day is suspicious
        if (accountsPerDay > 1.0) {
            return SuspiciousPattern(
                type = PatternType.RAPID_ACCOUNT_CREATION,
                description = "Device created ${analysis.totalAccounts} accounts in ${timeSpan.toDays()} days",
                severity = when {
                    accountsPerDay > 5 -> PatternSeverity.CRITICAL
                    accountsPerDay > 2 -> PatternSeverity.HIGH
                    else -> PatternSeverity.MEDIUM
                },
                affectedUsers = analysis.associatedUserIds,
                evidence = mapOf(
                    "device_fingerprint" to deviceFingerprint,
                    "accounts_created" to analysis.totalAccounts.toString(),
                    "time_span_days" to timeSpan.toDays().toString(),
                    "accounts_per_day" to String.format("%.2f", accountsPerDay)
                )
            )
        }
        
        return null
    }
    
    /**
     * Tracks a device usage event.
     */
    suspend fun trackDeviceUsage(
        userId: String,
        deviceFingerprint: String,
        ipAddress: String,
        userAgent: String?,
        action: String
    ) {
        riskPatternDao.trackDevice(
            DeviceTrackingData(
                deviceFingerprint = deviceFingerprint,
                userId = userId,
                ipAddress = ipAddress,
                userAgent = userAgent,
                timestamp = Instant.now(),
                action = action
            )
        )
        
        // Auto-detect patterns after tracking
        val pattern = detectRapidAccountCreation(deviceFingerprint)
        if (pattern != null) {
            riskPatternDao.recordSuspiciousPattern(pattern)
        }
    }
    
    /**
     * Calculates device risk score for a user.
     */
    suspend fun calculateDeviceRiskScore(userId: String): Double {
        val patterns = analyzeUserDevices(userId)
        
        if (patterns.isEmpty()) return 0.0
        
        // Weight patterns by severity
        val severityWeights = mapOf(
            PatternSeverity.INFO to 0.1,
            PatternSeverity.LOW to 0.2,
            PatternSeverity.MEDIUM to 0.4,
            PatternSeverity.HIGH to 0.6,
            PatternSeverity.CRITICAL to 0.9
        )
        
        val totalWeight = patterns.sumOf { severityWeights[it.severity] ?: 0.0 }
        
        // Normalize to 0-1 range
        return (totalWeight / patterns.size).coerceIn(0.0, 1.0)
    }
    
    /**
     * Finds shared devices between two users.
     */
    private suspend fun findSharedDevices(user1Id: String, user2Id: String): List<String> {
        val user1Devices = riskPatternDao.getDevicesByUser(user1Id)
        val user2Devices = riskPatternDao.getDevicesByUser(user2Id)
        return user1Devices.intersect(user2Devices.toSet()).toList()
    }
}
