package org.barter.features.reviews.service

import org.barter.features.reviews.model.RiskFactor
import org.barter.features.reviews.model.TransactionRiskScore
import java.time.Duration

/**
 * Service for analyzing transaction risk to detect abuse patterns.
 */
class RiskAnalysisService {

    /**
     * Calculates risk score for a transaction between two users.
     */
    suspend fun calculateTransactionRisk(
        user1Id: String,
        user2Id: String,
        shareDeviceFingerprint: suspend (String, String) -> Boolean,
        shareSameIp: suspend (String, String) -> Boolean,
        calculateDistance: suspend (String, String) -> Double?, // Distance in meters
        getAccountAge: suspend (String) -> Duration,
        getTradingPartners: suspend (String) -> Set<String>,
        shareContactInfo: suspend (String, String) -> Boolean
    ): TransactionRiskScore {
        val riskFactors = mutableListOf<String>()

        // Check device fingerprints
        if (shareDeviceFingerprint(user1Id, user2Id)) {
            riskFactors.add(RiskFactor.SAME_DEVICE.value)
        }

        // Check IP addresses
        if (shareSameIp(user1Id, user2Id)) {
            riskFactors.add(RiskFactor.SAME_IP.value)
        }

        // Check location proximity (within 100m = likely same device)
        val distance = calculateDistance(user1Id, user2Id)
        if (distance != null && distance < 100.0) {
            riskFactors.add(RiskFactor.SAME_LOCATION.value)
        }

        // Check if both accounts are new
        val user1Age = getAccountAge(user1Id)
        val user2Age = getAccountAge(user2Id)
        if (user1Age < Duration.ofDays(30) && user2Age < Duration.ofDays(30)) {
            riskFactors.add(RiskFactor.BOTH_NEW_ACCOUNTS.value)
        }

        // Check trade diversity (do they only trade with each other?)
        val user1Partners = getTradingPartners(user1Id)
        val user2Partners = getTradingPartners(user2Id)
        if (user1Partners.size < 5 && user2Partners.size < 5) {
            // If both users have few partners and they mostly trade with each other
            val user1TradingWithUser2 = user2Id in user1Partners
            val user2TradingWithUser1 = user1Id in user2Partners
            if (user1TradingWithUser2 && user2TradingWithUser1) {
                riskFactors.add(RiskFactor.NO_OTHER_CONNECTIONS.value)
            }
        }

        // Check for shared contact information
        if (shareContactInfo(user1Id, user2Id)) {
            riskFactors.add(RiskFactor.MATCHED_CONTACT_INFO.value)
        }

        // Calculate overall risk score
        val score = when {
            riskFactors.size >= 3 -> 0.9 // Very high risk
            riskFactors.size == 2 -> 0.6 // High risk
            riskFactors.size == 1 -> 0.3 // Medium risk
            else -> 0.0 // Low risk
        }

        return TransactionRiskScore(score, riskFactors)
    }

    /**
     * Calculates trade diversity score for a user.
     * Higher score = more diverse trading partners (less likely wash trading).
     */
    suspend fun calculateTradeDiversityScore(
        userId: String,
        getCompletedTrades: suspend (String) -> List<CompletedTrade>
    ): Double {
        val allTrades = getCompletedTrades(userId)
        if (allTrades.size < 5) {
            return 0.5 // Too early to judge, neutral score
        }

        val uniquePartners = allTrades.map { it.otherUserId }.toSet().size
        val diversityRatio = uniquePartners.toDouble() / allTrades.size

        // Calculate score based on diversity
        return when {
            diversityRatio > 0.8 -> 1.0 // Great diversity
            diversityRatio > 0.5 -> 0.8 // Good diversity
            diversityRatio > 0.3 -> 0.5 // Moderate diversity
            else -> 0.2 // Poor diversity - likely wash trading
        }
    }

    /**
     * Simplified trade data for diversity calculations.
     */
    data class CompletedTrade(
        val transactionId: String,
        val otherUserId: String
    )
}
