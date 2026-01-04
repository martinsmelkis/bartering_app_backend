package org.barter.features.reviews.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.barter.features.authentication.dao.AuthenticationDaoImpl
import org.barter.features.authentication.utils.verifyRequestSignature
import org.barter.features.reviews.dao.*
import org.barter.features.reviews.model.*
import org.barter.features.reviews.service.ReviewEligibilityService
import org.barter.features.reviews.service.ReviewWeightService
import org.koin.java.KoinJavaComponent.inject
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Submit a review for a transaction
 */
fun Route.submitReviewRoute() {
    val reviewDao: ReviewDao by inject(ReviewDao::class.java)
    val transactionDao: BarterTransactionDao by inject(BarterTransactionDao::class.java)
    val reputationDao: ReputationDao by inject(ReputationDao::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)
    val eligibilityService: ReviewEligibilityService by inject(ReviewEligibilityService::class.java)
    val weightService: ReviewWeightService by inject(ReviewWeightService::class.java)

    post("/api/v1/reviews/submit") {
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            return@post
        }

        try {
            val request = Json.decodeFromString<SubmitReviewRequest>(requestBody)

            // Verify the authenticated user is the reviewer
            if (authenticatedUserId != request.reviewerId) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "You can only submit reviews for yourself")
                )
            }

            // Check eligibility
            val eligibility = eligibilityService.checkReviewEligibility(
                reviewerId = request.reviewerId,
                targetUserId = request.targetUserId,
                transactionId = request.transactionId,
                getTransaction = { id ->
                    transactionDao.getTransaction(id)?.let {
                        ReviewEligibilityService.Transaction(
                            id = it.id,
                            status = it.status,
                            completedAt = it.completedAt
                        )
                    }
                },
                hasAlreadyReviewed = { reviewerId, targetUserId, transactionId ->
                    reviewDao.hasAlreadyReviewed(reviewerId, targetUserId, transactionId)
                },
                getAccountAge = { userId ->
                    // TODO: Get actual account age from user registration
                    Duration.ofDays(30) // Placeholder
                },
                getReviewsInLastDays = { userId, days ->
                    reviewDao.getReviewsInLastDays(userId, days)
                }
            )

            if (!eligibility.canReview) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to eligibility.reason)
                )
            }

            // Parse transaction status
            val transactionStatus = TransactionStatus.fromString(request.transactionStatus)
            if (transactionStatus == null) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid transaction status")
                )
            }

            // Calculate review weight
            val transaction = transactionDao.getTransaction(request.transactionId)
            val reviewerReputation = reputationDao.getReputation(request.reviewerId)
            
            val weight = weightService.calculateReviewWeight(
                review = org.barter.features.reviews.model.ReviewSubmission(
                    barterTransactionId = request.transactionId,
                    reviewerId = request.reviewerId,
                    targetUserId = request.targetUserId,
                    rating = request.rating,
                    reviewText = request.reviewText,
                    transactionStatus = transactionStatus
                ),
                reviewerAccountType = org.barter.features.reviews.model.AccountType.INDIVIDUAL, // TODO: Get actual type
                transactionValue = transaction?.estimatedValue,
                reviewerReputation = reviewerReputation?.let {
                    ReviewWeightService.ReviewerReputation(
                        averageRating = it.averageRating,
                        totalReviews = it.totalReviews
                    )
                },
                isVerifiedTransaction = transaction?.locationConfirmed ?: false
            )

            // Create review
            val reviewId = UUID.randomUUID().toString()
            val review = ReviewDto(
                id = reviewId,
                transactionId = request.transactionId,
                reviewerId = request.reviewerId,
                targetUserId = request.targetUserId,
                rating = request.rating,
                reviewText = request.reviewText,
                transactionStatus = transactionStatus,
                reviewWeight = weight.baseWeight,
                isVisible = false, // Hidden until blind review period ends
                submittedAt = Instant.now(),
                revealedAt = null,
                isVerified = false,
                moderationStatus = if (transactionStatus == TransactionStatus.SCAM) "pending" else null
            )

            val success = reviewDao.createReview(review)

            if (success) {
                // TODO: Check if both parties have submitted, reveal if ready
                call.respond(
                    HttpStatusCode.Created,
                    SubmitReviewResponse(
                        success = true,
                        reviewId = reviewId,
                        message = "Review submitted. It will be visible after both parties submit reviews or 14-day deadline."
                    )
                )
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to submit review")
                )
            }

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid request: ${e.message}")
            )
        }
    }
}

/**
 * Get reviews for a user
 */
fun Route.getUserReviewsRoute() {
    val reviewDao: ReviewDao by inject(ReviewDao::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/reviews/user/{userId}") {
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
            // Anyone can view visible reviews for a user
            val reviews = reviewDao.getUserReviews(userId)
            val reviewResponses = reviews.map { dto ->
                ReviewResponse(
                    id = dto.id,
                    transactionId = dto.transactionId,
                    reviewerId = dto.reviewerId,
                    targetUserId = dto.targetUserId,
                    rating = dto.rating,
                    reviewText = dto.reviewText,
                    transactionStatus = dto.transactionStatus.value,
                    reviewWeight = dto.reviewWeight,
                    isVisible = dto.isVisible,
                    submittedAt = dto.submittedAt.toEpochMilli(),
                    revealedAt = dto.revealedAt?.toEpochMilli(),
                    isVerified = dto.isVerified,
                    moderationStatus = dto.moderationStatus
                )
            }
            
            // Calculate average rating
            val avgRating = if (reviewResponses.isNotEmpty()) {
                reviewResponses.map { it.rating }.average()
            } else 0.0
            
            call.respond(HttpStatusCode.OK, UserReviewsResponse(
                userId = userId,
                reviews = reviewResponses,
                totalCount = reviewResponses.size,
                averageRating = avgRating
            ))

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to retrieve reviews")
            )
        }
    }
}

/**
 * Get reviews for a transaction
 */
fun Route.getTransactionReviewsRoute() {
    val reviewDao: ReviewDao by inject(ReviewDao::class.java)
    val transactionDao: BarterTransactionDao by inject(BarterTransactionDao::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/reviews/transaction/{transactionId}") {
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@get
        }

        val transactionId = call.parameters["transactionId"]
        if (transactionId.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing transactionId parameter")
            )
        }

        try {
            // Verify user is a party to the transaction
            val transaction = transactionDao.getTransaction(transactionId)
            if (transaction == null) {
                return@get call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Transaction not found")
                )
            }

            if (authenticatedUserId != transaction.user1Id && authenticatedUserId != transaction.user2Id) {
                return@get call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "You are not a party to this transaction")
                )
            }

            val reviews = reviewDao.getTransactionReviews(transactionId)
            val reviewResponses = reviews.map { dto ->
                ReviewResponse(
                    id = dto.id,
                    transactionId = dto.transactionId,
                    reviewerId = dto.reviewerId,
                    targetUserId = dto.targetUserId,
                    rating = dto.rating,
                    reviewText = dto.reviewText,
                    transactionStatus = dto.transactionStatus.value,
                    reviewWeight = dto.reviewWeight,
                    isVisible = dto.isVisible,
                    submittedAt = dto.submittedAt.toEpochMilli(),
                    revealedAt = dto.revealedAt?.toEpochMilli(),
                    isVerified = dto.isVerified,
                    moderationStatus = dto.moderationStatus
                )
            }
            call.respond(HttpStatusCode.OK, reviewResponses)

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to retrieve reviews")
            )
        }
    }
}

/**
 * Check if user can review another user
 */
fun Route.checkReviewEligibilityRoute() {
    val transactionDao: BarterTransactionDao by inject(BarterTransactionDao::class.java)
    val reviewDao: ReviewDao by inject(ReviewDao::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/reviews/eligibility/{userId}/with/{otherUserId}") {
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) return@get

        val userId = call.parameters["userId"]
        val otherUserId = call.parameters["otherUserId"]

        if (userId != authenticatedUserId) {
            return@get call.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "Can only check your own eligibility")
            )
        }

        try {
            // Find most recent completed transaction between users
            val transactions = transactionDao.getTransactionsBetweenUsers(userId, otherUserId!!)
            val completedTransaction = transactions
                .filter { it.status == TransactionStatus.DONE }
                .maxByOrNull { it.completedAt ?: it.initiatedAt }

            if (completedTransaction == null) {
                return@get call.respond(HttpStatusCode.OK, ReviewEligibilityResponse(
                    eligible = false,
                    transactionId = null,
                    reason = "No completed transaction found",
                    otherUserName = "User" // TODO: Get from profile
                ))
            }

            // Check if already reviewed
            val alreadyReviewed = reviewDao.hasAlreadyReviewed(
                userId,
                otherUserId,
                completedTransaction.id
            )

            if (alreadyReviewed) {
                return@get call.respond(HttpStatusCode.OK, ReviewEligibilityResponse(
                    eligible = false,
                    transactionId = completedTransaction.id,
                    reason = "You have already reviewed this transaction",
                    otherUserName = "User" // TODO: Get from profile
                ))
            }

            call.respond(HttpStatusCode.OK, ReviewEligibilityResponse(
                eligible = true,
                transactionId = completedTransaction.id,
                reason = null,
                otherUserName = "User", // TODO: Get from profile
                transactionCompletedAt = completedTransaction.completedAt?.toEpochMilli()
            ))

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to check eligibility: ${e.message}")
            )
        }
    }
}

// Request/Response models moved to ApiModels.kt
