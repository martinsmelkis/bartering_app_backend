package org.barter.features.reviews.service

import org.barter.features.reviews.model.*
import java.time.Duration

/**
 * Enhanced service for analyzing transaction risk to detect abuse patterns.
 * Integrates device, IP, and location pattern detection.
 */
class RiskAnalysisService(
    private val devicePatternService: DevicePatternDetectionService,
    private val ipPatternService: IpPatternDetectionService,
    private val locationPatternService: LocationPatternDetectionService
) {

    /**
     * Performs comprehensive risk analysis for a transaction.
     */
    suspend fun analyzeTransactionRisk(
        transactionId: String,
        user1Id: String,
        user2Id: String,
        getAccountAge: suspend (String) -> Duration,
        getTradingPartners: suspend (String) -> Set<String>
    ): RiskAnalysisReport {
        val detectedPatterns = mutableListOf<String>()
        val recommendations = mutableListOf<String>()
        
        // Device pattern analysis
        val deviceRiskScore = analyzeDeviceRisk(user1Id, user2Id, detectedPatterns)
        
        // IP pattern analysis
        val ipRiskScore = analyzeIpRisk(user1Id, user2Id, detectedPatterns)
        
        // Location pattern analysis
        val locationRiskScore = analyzeLocationRisk(user1Id, user2Id, detectedPatterns)
        
        // Behavior analysis
        val behaviorRiskScore = analyzeBehaviorRisk(
            user1Id, user2Id, getAccountAge, getTradingPartners, detectedPatterns
        )
        
        // Calculate weighted overall risk score
        val overallRiskScore = calculateWeightedRiskScore(
            deviceRiskScore, ipRiskScore, locationRiskScore, behaviorRiskScore
        )
        
        // Determine risk level
        val riskLevel = when {
            overallRiskScore >= 0.8 -> RiskLevel.CRITICAL
            overallRiskScore >= 0.6 -> RiskLevel.HIGH
            overallRiskScore >= 0.4 -> RiskLevel.MEDIUM
            overallRiskScore >= 0.2 -> RiskLevel.LOW
            else -> RiskLevel.MINIMAL
        }
        
        // Generate recommendations based on risk level
        when (riskLevel) {
            RiskLevel.CRITICAL -> {
                recommendations.add("Block transaction immediately")
                recommendations.add("Flag both accounts for manual review")
                recommendations.add("Investigate for coordinated fraud")
            }
            RiskLevel.HIGH -> {
                recommendations.add("Require additional verification")
                recommendations.add("Delay review posting by 48 hours")
                recommendations.add("Monitor for wash trading patterns")
            }
            RiskLevel.MEDIUM -> {
                recommendations.add("Apply reduced review weight")
                recommendations.add("Monitor transaction completion")
            }
            RiskLevel.LOW -> {
                recommendations.add("Standard monitoring sufficient")
            }
            RiskLevel.MINIMAL -> {
                // No specific recommendations
            }
        }
        
        return RiskAnalysisReport(
            transactionId = transactionId,
            user1Id = user1Id,
            user2Id = user2Id,
            overallRiskScore = overallRiskScore,
            riskLevel = riskLevel.name,
            deviceRiskScore = deviceRiskScore,
            ipRiskScore = ipRiskScore,
            locationRiskScore = locationRiskScore,
            behaviorRiskScore = behaviorRiskScore,
            detectedPatterns = detectedPatterns,
            recommendations = recommendations,
            requiresManualReview = riskLevel >= RiskLevel.HIGH,
            analysisTimestamp = System.currentTimeMillis()
        )
    }

    /**
     * Analyzes device-related risks.
     */
    private suspend fun analyzeDeviceRisk(
        user1Id: String,
        user2Id: String,
        detectedPatterns: MutableList<String>
    ): Double {
        // Check if users share devices
        val deviceSharing = devicePatternService.checkDeviceSharing(user1Id, user2Id)
        if (deviceSharing != null) {
            detectedPatterns.add(deviceSharing.description)
        }
        
        // Get individual device risk scores
        val user1Score = devicePatternService.calculateDeviceRiskScore(user1Id)
        val user2Score = devicePatternService.calculateDeviceRiskScore(user2Id)
        
        // Return highest score
        return maxOf(user1Score, user2Score, deviceSharing?.severity?.let { 
            when(it) {
                PatternSeverity.CRITICAL -> 0.9
                PatternSeverity.HIGH -> 0.7
                PatternSeverity.MEDIUM -> 0.5
                PatternSeverity.LOW -> 0.3
                PatternSeverity.INFO -> 0.1
            }
        } ?: 0.0)
    }

    /**
     * Analyzes IP-related risks.
     */
    private suspend fun analyzeIpRisk(
        user1Id: String,
        user2Id: String,
        detectedPatterns: MutableList<String>
    ): Double {
        // Check if users share IPs
        val ipSharing = ipPatternService.checkIpSharing(user1Id, user2Id)
        if (ipSharing != null) {
            detectedPatterns.add(ipSharing.description)
        }
        
        // Get individual IP risk scores
        val user1Score = ipPatternService.calculateIpRiskScore(user1Id)
        val user2Score = ipPatternService.calculateIpRiskScore(user2Id)
        
        return maxOf(user1Score, user2Score, ipSharing?.severity?.let { 
            when(it) {
                PatternSeverity.CRITICAL -> 0.9
                PatternSeverity.HIGH -> 0.7
                PatternSeverity.MEDIUM -> 0.5
                PatternSeverity.LOW -> 0.3
                PatternSeverity.INFO -> 0.1
            }
        } ?: 0.0)
    }

    /**
     * Analyzes location-related risks.
     */
    private suspend fun analyzeLocationRisk(
        user1Id: String,
        user2Id: String,
        detectedPatterns: MutableList<String>
    ): Double {
        // Check if users are in same location
        val locationProximity = locationPatternService.checkLocationProximity(user1Id, user2Id)
        if (locationProximity != null) {
            detectedPatterns.add(locationProximity.description)
        }
        
        // Get individual location risk scores
        val user1Score = locationPatternService.calculateLocationRiskScore(user1Id)
        val user2Score = locationPatternService.calculateLocationRiskScore(user2Id)
        
        return maxOf(user1Score, user2Score, locationProximity?.severity?.let { 
            when(it) {
                PatternSeverity.CRITICAL -> 0.9
                PatternSeverity.HIGH -> 0.7
                PatternSeverity.MEDIUM -> 0.5
                PatternSeverity.LOW -> 0.3
                PatternSeverity.INFO -> 0.1
            }
        } ?: 0.0)
    }

    /**
     * Analyzes behavioral risks.
     */
    private suspend fun analyzeBehaviorRisk(
        user1Id: String,
        user2Id: String,
        getAccountAge: suspend (String) -> Duration,
        getTradingPartners: suspend (String) -> Set<String>,
        detectedPatterns: MutableList<String>
    ): Double {
        var riskScore = 0.0
        
        // Check account ages
        val user1Age = getAccountAge(user1Id)
        val user2Age = getAccountAge(user2Id)
        
        if (user1Age < Duration.ofDays(7) && user2Age < Duration.ofDays(7)) {
            riskScore += 0.4
            detectedPatterns.add("Both accounts created within last 7 days")
        } else if (user1Age < Duration.ofDays(30) && user2Age < Duration.ofDays(30)) {
            riskScore += 0.2
            detectedPatterns.add("Both accounts less than 30 days old")
        }
        
        // Check trading diversity
        val user1Partners = getTradingPartners(user1Id)
        val user2Partners = getTradingPartners(user2Id)
        
        if (user1Partners.size < 3 && user2Partners.size < 3) {
            if (user2Id in user1Partners && user1Id in user2Partners) {
                riskScore += 0.3
                detectedPatterns.add("Limited trading partners, primarily trade with each other")
            }
        }
        
        return riskScore.coerceIn(0.0, 1.0)
    }

    /**
     * Calculates weighted overall risk score.
     */
    private fun calculateWeightedRiskScore(
        deviceScore: Double,
        ipScore: Double,
        locationScore: Double,
        behaviorScore: Double
    ): Double {
        // Weight each component
        val weights = mapOf(
            "device" to 0.3,
            "ip" to 0.25,
            "location" to 0.25,
            "behavior" to 0.2
        )
        
        return (deviceScore * weights["device"]!! +
                ipScore * weights["ip"]!! +
                locationScore * weights["location"]!! +
                behaviorScore * weights["behavior"]!!).coerceIn(0.0, 1.0)
    }

    /**
     * Calculates trade diversity score for a user.
     */
    suspend fun calculateTradeDiversityScore(
        userId: String,
        getCompletedTrades: suspend (String) -> List<CompletedTrade>
    ): Double {
        val allTrades = getCompletedTrades(userId)
        if (allTrades.size < 5) {
            return 0.5
        }

        val uniquePartners = allTrades.map { it.otherUserId }.toSet().size
        val diversityRatio = uniquePartners.toDouble() / allTrades.size

        return when {
            diversityRatio > 0.8 -> 1.0
            diversityRatio > 0.5 -> 0.8
            diversityRatio > 0.3 -> 0.5
            else -> 0.2
        }
    }

    /**
     * Simplified trade data for diversity calculations.
     */
    data class CompletedTrade(
        val transactionId: String,
        val otherUserId: String,
        val initiatedAt: Long? = null,  // Epoch millis - optional for backward compatibility
        val completedAt: Long? = null   // Epoch millis - optional for backward compatibility
    )
}
