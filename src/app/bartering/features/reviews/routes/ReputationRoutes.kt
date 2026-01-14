package app.bartering.features.reviews.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.utils.verifyRequestSignature
import app.bartering.features.chat.dao.ChatAnalyticsDao
import app.bartering.features.reviews.dao.*
import app.bartering.features.reviews.model.*
import app.bartering.features.reviews.service.ReputationCalculationService
import app.bartering.features.reviews.service.RiskAnalysisService
import org.koin.java.KoinJavaComponent.inject

/**
 * Get reputation score for a user
 */
fun Route.getReputationRoute() {
    val reputationDao: ReputationDao by inject(ReputationDao::class.java)
    val reviewDao: ReviewDao by inject(ReviewDao::class.java)
    val transactionDao: BarterTransactionDao by inject(BarterTransactionDao::class.java)
    val chatAnalyticsDao: ChatAnalyticsDao by inject(ChatAnalyticsDao::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)
    val reputationService: ReputationCalculationService by inject(ReputationCalculationService::class.java)

    get("/api/v1/reputation/{userId}") {
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@get
        }

        val userId = call.parameters["userId"]
        if (userId.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing userId parameter")
            )
        }

        try {
            // Check if we have cached reputation
            val cachedReputation = reputationDao.getReputation(userId)
            
            // If cached and recent (< 1 hour old), return it
            if (cachedReputation != null && 
                cachedReputation.lastUpdated.plusSeconds(3600).isAfter(java.time.Instant.now())) {
                
                val badges = reputationDao.getUserBadges(userId)
                
                call.respond(HttpStatusCode.OK, ReputationResponse(
                    userId = cachedReputation.userId,
                    averageRating = cachedReputation.averageRating,
                    totalReviews = cachedReputation.totalReviews,
                    verifiedReviews = cachedReputation.verifiedReviews,
                    tradeDiversityScore = cachedReputation.tradeDiversityScore,
                    trustLevel = cachedReputation.trustLevel.value,
                    badges = badges.map { it.value },
                    lastUpdated = cachedReputation.lastUpdated.toEpochMilli()
                ))
                return@get
            }

            // Otherwise, calculate fresh reputation
            val reputation = reputationService.calculateReputationScore(
                userId = userId,
                getWeightedReviews = { uid ->
                    reviewDao.getWeightedReviews(uid).map { dto ->
                        ReputationCalculationService.WeightedReview(
                            rating = dto.rating,
                            weight = dto.weight
                        )
                    }
                },
                getVerifiedReviewCount = { reviewDao.getVerifiedReviewCount(it) },
                getCompletedTrades = { uid ->
                    transactionDao.getCompletedTrades(uid).map { dto ->
                        RiskAnalysisService.CompletedTrade(
                            transactionId = dto.transactionId,
                            otherUserId = dto.otherUserId,
                            initiatedAt = dto.initiatedAt.toEpochMilli(),
                            completedAt = dto.completedAt.toEpochMilli()
                        )
                    }
                },
                getBadges = { reputationDao.getUserBadges(it) }
            )

            // Cache the result
            reputationDao.updateReputation(ReputationDto(
                userId = reputation.userId,
                averageRating = reputation.averageRating,
                totalReviews = reputation.totalReviews,
                verifiedReviews = reputation.verifiedReviews,
                tradeDiversityScore = reputation.tradeDiversityScore,
                trustLevel = reputation.trustLevel,
                lastUpdated = java.time.Instant.now()
            ))

            // Check and update badge eligibility for all badges
            updateUserBadges(userId, reputation, reputationService, reputationDao, transactionDao, reviewDao, chatAnalyticsDao)

            // Get updated badges after eligibility check
            val updatedBadges = reputationDao.getUserBadges(userId)

            // Convert to response model
            val response = ReputationResponse(
                userId = reputation.userId,
                averageRating = reputation.averageRating,
                totalReviews = reputation.totalReviews,
                verifiedReviews = reputation.verifiedReviews,
                tradeDiversityScore = reputation.tradeDiversityScore,
                trustLevel = reputation.trustLevel.value,
                badges = updatedBadges.map { it.value },
                lastUpdated = reputation.lastUpdated
            )
            
            call.respond(HttpStatusCode.OK, response)

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to retrieve reputation: ${e.message}")
            )
        }
    }
}

/**
 * Get earned badges for a user
 */
fun Route.getUserBadgesRoute() {
    val reputationDao: ReputationDao by inject(ReputationDao::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/reputation/{userId}/badges") {
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@get
        }

        val userId = call.parameters["userId"]
        if (userId.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing userId parameter")
            )
        }

        try {
            val badgesWithTimestamps = reputationDao.getUserBadgesWithTimestamps(userId)
            val badgeInfos = badgesWithTimestamps.map { badgeData ->
                BadgeInfo(
                    type = badgeData.badge.value,
                    name = badgeData.badge.description.split(" - ").firstOrNull() ?: badgeData.badge.value,
                    description = badgeData.badge.description,
                    earnedAt = badgeData.earnedAt.toEpochMilli()
                )
            }
            call.respond(HttpStatusCode.OK, BadgesResponse(
                userId = userId,
                badges = badgeInfos
            ))

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to retrieve badges")
            )
        }
    }
}

/**
 * Helper function to check and update badge eligibility for a user
 */
private suspend fun updateUserBadges(
    userId: String,
    reputation: ReputationScore,
    reputationService: ReputationCalculationService,
    reputationDao: ReputationDao,
    transactionDao: BarterTransactionDao,
    reviewDao: ReviewDao,
    chatAnalyticsDao: ChatAnalyticsDao
) {
    // Get current badges
    val currentBadges = reputationDao.getUserBadges(userId).toSet()

    // Check eligibility for each badge type
    for (badge in ReputationBadge.entries) {
        try {
            val isEligible = reputationService.checkBadgeEligibility(
                userId = userId,
                badge = badge,
                reputation = reputation,
                hasIdentityVerified = { uid ->
                    badge == ReputationBadge.IDENTITY_VERIFIED && currentBadges.contains(badge)
                },
                hasBusinessVerified = { uid ->
                    badge == ReputationBadge.VERIFIED_BUSINESS && currentBadges.contains(badge)
                },
                getAverageResponseTime = { uid ->
                    // Get average response time from chat analytics (last 30 days)
                    chatAnalyticsDao.getAverageResponseTime(uid, daysToConsider = 30)
                },
                hasDisputedTransactions = { uid ->
                    // Check if user has any disputed or scam transactions
                    val transactions = transactionDao.getUserTransactions(uid)
                    transactions.any { 
                        it.status == TransactionStatus.DISPUTED || it.status == TransactionStatus.SCAM
                    }
                },
                getAverageTradeCompletionTime = { uid ->
                    transactionDao.getAverageTradeCompletionTime(uid)
                }
            )

            // Update badge status
            if (isEligible && !currentBadges.contains(badge)) {
                // Award new badge
                reputationDao.addBadge(userId, badge)
            } else if (!isEligible && currentBadges.contains(badge)) {
                // Remove badge if no longer eligible (except for manually awarded badges)
                if (badge != ReputationBadge.IDENTITY_VERIFIED && 
                    badge != ReputationBadge.VERIFIED_BUSINESS) {
                    reputationDao.removeBadge(userId, badge)
                }
            }
        } catch (e: Exception) {
            // Log error but continue checking other badges
            e.printStackTrace()
        }
    }
}
