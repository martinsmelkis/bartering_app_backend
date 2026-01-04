package org.barter.features.reviews.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.barter.features.authentication.dao.AuthenticationDaoImpl
import org.barter.features.authentication.utils.verifyRequestSignature
import org.barter.features.reviews.dao.*
import org.barter.features.reviews.model.*
import org.barter.features.reviews.service.ReputationCalculationService
import org.barter.features.reviews.service.RiskAnalysisService
import org.koin.java.KoinJavaComponent.inject

/**
 * Get reputation score for a user
 */
fun Route.getReputationRoute() {
    val reputationDao: ReputationDao by inject(ReputationDao::class.java)
    val reviewDao: ReviewDao by inject(ReviewDao::class.java)
    val transactionDao: BarterTransactionDao by inject(BarterTransactionDao::class.java)
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
                            otherUserId = dto.otherUserId
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

            // Convert to response model
            val response = ReputationResponse(
                userId = reputation.userId,
                averageRating = reputation.averageRating,
                totalReviews = reputation.totalReviews,
                verifiedReviews = reputation.verifiedReviews,
                tradeDiversityScore = reputation.tradeDiversityScore,
                trustLevel = reputation.trustLevel.value,
                badges = reputation.badges.map { it.value },
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
 * Get badges for a user
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
            val badges = reputationDao.getUserBadges(userId)
            val badgeInfos = badges.map { badge ->
                BadgeInfo(
                    type = badge.value,
                    name = badge.description.split(" - ").firstOrNull() ?: badge.value,
                    description = badge.description,
                    earnedAt = System.currentTimeMillis() // TODO: Get actual earned time
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
