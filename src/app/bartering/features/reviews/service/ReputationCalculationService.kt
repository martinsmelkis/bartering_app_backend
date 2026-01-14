package app.bartering.features.reviews.service

import app.bartering.features.reviews.model.ReputationBadge
import app.bartering.features.reviews.model.ReputationScore
import app.bartering.features.reviews.model.TrustLevel
import java.time.Instant

/**
 * Service for calculating and updating user reputation scores.
 */
class ReputationCalculationService(
    private val riskAnalysisService: RiskAnalysisService
) {

    /**
     * Calculates comprehensive reputation score for a user.
     */
    suspend fun calculateReputationScore(
        userId: String,
        getWeightedReviews: suspend (String) -> List<WeightedReview>,
        getVerifiedReviewCount: suspend (String) -> Int,
        getCompletedTrades: suspend (String) -> List<RiskAnalysisService.CompletedTrade>,
        getBadges: suspend (String) -> List<ReputationBadge>
    ): ReputationScore {
        val reviews = getWeightedReviews(userId)
        val verifiedReviews = getVerifiedReviewCount(userId)

        // Calculate weighted average rating
        val totalWeight = reviews.sumOf { it.weight }
        val weightedSum = reviews.sumOf { it.rating * it.weight }
        val averageRating = if (totalWeight > 0) weightedSum / totalWeight else 0.0

        // Calculate trade diversity score
        val tradeDiversityScore = riskAnalysisService.calculateTradeDiversityScore(
            userId,
            getCompletedTrades
        )

        // Determine trust level
        val trustLevel = calculateTrustLevel(reviews.size, tradeDiversityScore, verifiedReviews > 0)

        // Get earned badges
        val badges = getBadges(userId)

        return ReputationScore(
            userId = userId,
            averageRating = averageRating,
            totalReviews = reviews.size,
            verifiedReviews = verifiedReviews,
            tradeDiversityScore = tradeDiversityScore,
            trustLevel = trustLevel,
            badges = badges,
            lastUpdated = Instant.now().toEpochMilli()
        )
    }

    /**
     * Determines trust level based on review count and diversity.
     */
    private fun calculateTrustLevel(
        totalReviews: Int,
        tradeDiversityScore: Double,
        hasIdentityVerified: Boolean
    ): TrustLevel {
        return when {
            totalReviews >= 100 && tradeDiversityScore > 0.7 && hasIdentityVerified -> TrustLevel.VERIFIED
            totalReviews >= 100 && tradeDiversityScore > 0.7 -> TrustLevel.TRUSTED
            totalReviews >= 20 -> TrustLevel.ESTABLISHED
            totalReviews >= 5 -> TrustLevel.EMERGING
            else -> TrustLevel.NEW
        }
    }

    /**
     * Checks if a user qualifies for a specific badge.
     */
    suspend fun checkBadgeEligibility(
        userId: String,
        badge: ReputationBadge,
        reputation: ReputationScore,
        hasIdentityVerified: suspend (String) -> Boolean,
        hasBusinessVerified: suspend (String) -> Boolean,
        getAverageResponseTime: suspend (String) -> Long?, // in hours
        hasDisputedTransactions: suspend (String) -> Boolean,
        getAverageTradeCompletionTime: suspend (String) -> Long? // in hours
    ): Boolean {
        return when (badge) {
            ReputationBadge.IDENTITY_VERIFIED -> hasIdentityVerified(userId)
            ReputationBadge.VETERAN_TRADER -> reputation.totalReviews >= 100
            ReputationBadge.TOP_RATED -> reputation.averageRating >= 4.8 && reputation.totalReviews >= 50
            ReputationBadge.QUICK_RESPONDER -> {
                val avgResponseTime = getAverageResponseTime(userId)
                avgResponseTime != null && avgResponseTime <= 24
            }
            ReputationBadge.COMMUNITY_CONNECTOR -> reputation.tradeDiversityScore >= 0.8
            ReputationBadge.VERIFIED_BUSINESS -> hasBusinessVerified(userId)
            ReputationBadge.DISPUTE_FREE -> !hasDisputedTransactions(userId) && reputation.totalReviews >= 10
            ReputationBadge.FAST_TRADER -> {
                val avgCompletionTime = getAverageTradeCompletionTime(userId)
                // Fast trader: completes trades in 48 hours or less on average, with at least 10 completed trades
                avgCompletionTime != null && avgCompletionTime <= 48 && reputation.totalReviews >= 10
            }
        }
    }

    /**
     * Calculates average trade completion time in hours from a list of completed trades.
     * Returns null if no trades with timing data are available.
     */
    fun calculateAverageCompletionTime(trades: List<RiskAnalysisService.CompletedTrade>): Long? {
        val durations = trades.mapNotNull { trade ->
            if (trade.initiatedAt != null && trade.completedAt != null) {
                val durationMillis = trade.completedAt - trade.initiatedAt
                durationMillis / (1000 * 60 * 60) // Convert to hours
            } else {
                null
            }
        }
        
        return if (durations.isEmpty()) null else durations.average().toLong()
    }

    /**
     * Simplified weighted review data.
     */
    data class WeightedReview(
        val rating: Double,
        val weight: Double
    )
}
