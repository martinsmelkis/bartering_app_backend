package app.bartering.features.profile.util

import app.bartering.features.profile.cache.UserActivityCache
import app.bartering.features.profile.model.UserProfileWithDistance
import app.bartering.features.reviews.dao.ReputationDao
import app.bartering.features.reviews.dao.ReputationDto
import app.bartering.features.reviews.dao.ReviewDao
import app.bartering.features.reviews.model.ReputationBadge
import app.bartering.features.reviews.model.TrustLevel
import org.slf4j.LoggerFactory

/**
 * Utility object for calculating and applying boost scores to user profiles based on various factors
 * including online activity, reputation, trust level, badges, and trade diversity.
 */
object ProfileBoostCalculator {
    private val log = LoggerFactory.getLogger(this::class.java)

    // Online activity boosts
    private const val RECENT_ONLINE_BOOST = 0.05  // Boost score by 0.05 for users online < 10 minutes ago
    private const val DAILY_ACTIVE_BOOST = 0.02   // Boost score by 0.02 for users active < 24 hours ago
    private const val TEN_MINUTES_MILLIS = 10 * 60 * 1000L  // 10 minutes in milliseconds
    private const val TWENTY_FOUR_HOURS_MILLIS = 24 * 60 * 60 * 1000L  // 24 hours in milliseconds

    // Reputation boosts (based on average rating)
    private const val EXCELLENT_RATING_BOOST = 0.08  // 4.8+ average rating
    private const val GREAT_RATING_BOOST = 0.05      // 4.5+ average rating
    private const val GOOD_RATING_BOOST = 0.02       // 4.0+ average rating

    // Trust level boosts
    private const val VERIFIED_TRUST_BOOST = 0.10    // Verified trust level
    private const val TRUSTED_BOOST = 0.07           // Trusted trust level
    private const val ESTABLISHED_BOOST = 0.04       // Established trust level
    private const val EMERGING_BOOST = 0.02          // Emerging trust level

    // Badge boosts (stackable, but capped)
    private const val TOP_RATED_BADGE_BOOST = 0.06
    private const val VETERAN_TRADER_BADGE_BOOST = 0.05
    private const val QUICK_RESPONDER_BADGE_BOOST = 0.04
    private const val DISPUTE_FREE_BADGE_BOOST = 0.04
    private const val FAST_TRADER_BADGE_BOOST = 0.03
    private const val COMMUNITY_CONNECTOR_BADGE_BOOST = 0.03
    private const val VERIFIED_BUSINESS_BADGE_BOOST = 0.05
    private const val IDENTITY_VERIFIED_BADGE_BOOST = 0.04

    // Trade activity boost
    private const val HIGH_TRADE_DIVERSITY_BOOST = 0.03  // Diversity score > 0.7

    // Maximum total boost to prevent over-boosting
    private const val MAX_TOTAL_BOOST = 0.25  // Cap at 0.25 total boost

    /**
     * Apply comprehensive boost to user profiles based on multiple factors:
     * 
     * ONLINE ACTIVITY:
     * - 0.05 boost if online within last 10 minutes
     * - 0.02 boost if active within last 24 hours
     * 
     * REPUTATION (average rating):
     * - 0.08 boost for 4.8+ rating (excellent)
     * - 0.05 boost for 4.5+ rating (great)
     * - 0.02 boost for 4.0+ rating (good)
     * 
     * TRUST LEVEL:
     * - 0.10 boost for VERIFIED
     * - 0.07 boost for TRUSTED
     * - 0.04 boost for ESTABLISHED
     * - 0.02 boost for EMERGING
     * 
     * BADGES (stackable):
     * - 0.06 boost for TOP_RATED
     * - 0.05 boost for VETERAN_TRADER, VERIFIED_BUSINESS
     * - 0.04 boost for QUICK_RESPONDER, DISPUTE_FREE, IDENTITY_VERIFIED
     * - 0.03 boost for FAST_TRADER, COMMUNITY_CONNECTOR
     * 
     * TRADE DIVERSITY:
     * - 0.03 boost for diversity score > 0.7
     * 
     * Total boost is capped at 0.25 to prevent over-boosting
     * 
     * @param profiles List of profiles to boost
     * @param reputationDao DAO for fetching cached reputation data
     * @param reviewDao Optional DAO for fetching ratings directly from reviews (fallback)
     * @return Same list with boosted scores and lastOnlineAt timestamps set
     */
    suspend fun applyBoostAndStatus(
        profiles: List<UserProfileWithDistance>,
        reputationDao: ReputationDao,
        reviewDao: ReviewDao? = null
    ): List<UserProfileWithDistance> {
        if (profiles.isEmpty()) return profiles

        val currentTime = System.currentTimeMillis()

        // Batch fetch reputation data for all users to minimize database calls
        val userIds = profiles.map { it.profile.userId }
        val reputationDataMap = mutableMapOf<String, Pair<ReputationDto, List<ReputationBadge>>>()

        try {
            userIds.forEach { userId ->
                val reputation = reputationDao.getReputation(userId)
                val badges = reputationDao.getUserBadges(userId)
                if (reputation != null) {
                    reputationDataMap[userId] = reputation to badges
                    log.debug("Fetched reputation for user {}: rating={}, reviews={}", 
                        userId, reputation.averageRating, reputation.totalReviews)
                } else {
                    log.debug("No reputation found for user {}", userId)
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch reputation data for boosting, continuing without reputation boost", e)
        }

        log.debug("Boosting {} profiles, fetched reputation for {} users", 
            profiles.size, reputationDataMap.size)

        // For users without cached reputation, try fetching from reviews directly (fallback)
        val fallbackRatingsMap = mutableMapOf<String, Pair<Double, Int>>()
        if (reviewDao != null && reputationDataMap.size < profiles.size) {
            val missingUserIds = userIds.filter { it !in reputationDataMap }
            if (missingUserIds.isNotEmpty()) {
                log.debug("Fetching fallback ratings from reviews for {} users", missingUserIds.size)
                try {
                    missingUserIds.forEach { userId ->
                        val ratingAndCount = reviewDao.getAverageRatingAndCount(userId)
                        if (ratingAndCount != null) {
                            fallbackRatingsMap[userId] = ratingAndCount
                            log.debug("Fallback rating for user {}: rating={}, count={}",
                                userId, ratingAndCount.first, ratingAndCount.second)
                        }
                    }
                } catch (e: Exception) {
                    log.warn("Failed to fetch fallback ratings from reviews", e)
                }
            }
        }

        // Apply comprehensive boost based on multiple factors
        return profiles.map { profileWithDistance ->
            val userId = profileWithDistance.profile.userId
            val lastOnlineAt = UserActivityCache.getLastSeen(userId)

            // Update the UserProfile with lastOnlineAt timestamp
            val updatedProfile = profileWithDistance.profile.copy(lastOnlineAt = lastOnlineAt)

            // Get reputation data for rating fields (from cache or fallback)
            val (reputation, badges) = reputationDataMap[userId] ?: (null to emptyList())
            
            // Try cached reputation first, then fallback to reviews table
            val averageRating = reputation?.averageRating 
                ?: fallbackRatingsMap[userId]?.first
            val totalReviews = reputation?.totalReviews 
                ?: fallbackRatingsMap[userId]?.second

            // Only calculate boost if there's a valid relevancy score to boost
            if (profileWithDistance.matchRelevancyScore == null || profileWithDistance.matchRelevancyScore.isNaN()) {
                log.debug("User {} has invalid/null relevancy score ({}), skipping boost", 
                    userId, profileWithDistance.matchRelevancyScore)
                return@map profileWithDistance.copy(
                    profile = updatedProfile,
                    averageRating = averageRating,
                    totalReviews = totalReviews
                )
            }

            var totalBoost = 0.0

            // 1. ONLINE ACTIVITY BOOST
            if (lastOnlineAt != null) {
                val timeSinceLastOnline = currentTime - lastOnlineAt
                when {
                    timeSinceLastOnline < TEN_MINUTES_MILLIS -> {
                        totalBoost += RECENT_ONLINE_BOOST
                        log.trace("User {} recently online (<10min), +{}", userId, RECENT_ONLINE_BOOST)
                    }
                    timeSinceLastOnline < TWENTY_FOUR_HOURS_MILLIS -> {
                        totalBoost += DAILY_ACTIVE_BOOST
                        log.trace("User {} active today (<24h), +{}", userId, DAILY_ACTIVE_BOOST)
                    }
                }
            }

            // 2. REPUTATION AND BADGES BOOST
            // Apply rating boost from either cached reputation or fallback reviews data
            val ratingForBoost = reputation?.averageRating ?: fallbackRatingsMap[userId]?.first
            if (ratingForBoost != null) {
                // 2a. Average Rating Boost
                when {
                    ratingForBoost >= 4.8 -> {
                        totalBoost += EXCELLENT_RATING_BOOST
                        log.trace("User {} has excellent rating ({}), +{}", userId, ratingForBoost, EXCELLENT_RATING_BOOST)
                    }
                    ratingForBoost >= 4.5 -> {
                        totalBoost += GREAT_RATING_BOOST
                        log.trace("User {} has great rating ({}), +{}", userId, ratingForBoost, GREAT_RATING_BOOST)
                    }
                    ratingForBoost >= 4.0 -> {
                        totalBoost += GOOD_RATING_BOOST
                        log.trace("User {} has good rating ({}), +{}", userId, ratingForBoost, GOOD_RATING_BOOST)
                    }
                }
            }

            // Apply trust level and trade diversity boosts only from full reputation data
            if (reputation != null) {
                // 2b. Trust Level Boost
                when (reputation.trustLevel) {
                    TrustLevel.VERIFIED -> {
                        totalBoost += VERIFIED_TRUST_BOOST
                        log.trace("User {} is VERIFIED, +{}", userId, VERIFIED_TRUST_BOOST)
                    }
                    TrustLevel.TRUSTED -> {
                        totalBoost += TRUSTED_BOOST
                        log.trace("User {} is TRUSTED, +{}", userId, TRUSTED_BOOST)
                    }
                    TrustLevel.ESTABLISHED -> {
                        totalBoost += ESTABLISHED_BOOST
                        log.trace("User {} is ESTABLISHED, +{}", userId, ESTABLISHED_BOOST)
                    }
                    TrustLevel.EMERGING -> {
                        totalBoost += EMERGING_BOOST
                        log.trace("User {} is EMERGING, +{}", userId, EMERGING_BOOST)
                    }
                    else -> {} // No boost for NEW
                }

                // 2c. Trade Diversity Boost
                if (reputation.tradeDiversityScore >= 0.7) {
                    totalBoost += HIGH_TRADE_DIVERSITY_BOOST
                    log.trace("User {} has high trade diversity ({}), +{}", userId, reputation.tradeDiversityScore, HIGH_TRADE_DIVERSITY_BOOST)
                }
            }

            // 2d. Badge Boosts (stackable)
            badges.forEach { badge ->
                val badgeBoost = when (badge) {
                    ReputationBadge.TOP_RATED -> TOP_RATED_BADGE_BOOST
                    ReputationBadge.VETERAN_TRADER -> VETERAN_TRADER_BADGE_BOOST
                    ReputationBadge.VERIFIED_BUSINESS -> VERIFIED_BUSINESS_BADGE_BOOST
                    ReputationBadge.QUICK_RESPONDER -> QUICK_RESPONDER_BADGE_BOOST
                    ReputationBadge.DISPUTE_FREE -> DISPUTE_FREE_BADGE_BOOST
                    ReputationBadge.IDENTITY_VERIFIED -> IDENTITY_VERIFIED_BADGE_BOOST
                    ReputationBadge.FAST_TRADER -> FAST_TRADER_BADGE_BOOST
                    ReputationBadge.COMMUNITY_CONNECTOR -> COMMUNITY_CONNECTOR_BADGE_BOOST
                }
                totalBoost += badgeBoost
                log.trace("User {} has badge {}, +{}", userId, badge.value, badgeBoost)
            }

            // Apply the cap to prevent over-boosting
            val cappedTotalBoost = totalBoost.coerceAtMost(MAX_TOTAL_BOOST)

            if (cappedTotalBoost > 0.0) {
                // Apply the boost to relevancy score (additive, then cap at 1.0)
                val rawBoostedScore = profileWithDistance.matchRelevancyScore + cappedTotalBoost
                val boostedScore = if (rawBoostedScore.isNaN()) {
                    log.warn("User {} calculated NaN boosted score (relevancy: {}, boost: {}), using original score",
                        userId, profileWithDistance.matchRelevancyScore, cappedTotalBoost)
                    profileWithDistance.matchRelevancyScore
                } else {
                    rawBoostedScore.coerceAtMost(1.0)
                }
                log.debug("User {} total boost: +{} (score: {} -> {})", userId, cappedTotalBoost,
                    profileWithDistance.matchRelevancyScore, boostedScore)
                profileWithDistance.copy(
                    profile = updatedProfile,
                    matchRelevancyScore = boostedScore,
                    averageRating = averageRating,
                    totalReviews = totalReviews
                )
            } else {
                profileWithDistance.copy(
                    profile = updatedProfile,
                    averageRating = averageRating,
                    totalReviews = totalReviews
                )
            }
        }
    }
}
