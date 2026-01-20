package app.bartering.features.reviews.routes

import io.ktor.http.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.utils.verifyRequestSignature
import app.bartering.features.profile.dao.UserProfileDao
import app.bartering.features.profile.dao.UserProfileDaoImpl
import app.bartering.features.reviews.dao.*
import app.bartering.features.reviews.model.*
import app.bartering.features.reviews.service.*
import org.koin.java.KoinJavaComponent.inject
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val log = LoggerFactory.getLogger("app.bartering.features.reviews.routes.ReviewRoutes")

/**
 * Get the real client IP address from request headers.
 * When running behind a proxy (like Docker, nginx, etc.), the direct connection IP
 * will be the proxy's IP. We need to check forwarding headers to get the real client IP.
 * 
 * Priority:
 * 1. X-Forwarded-For (most common, can contain multiple IPs - we take the first one)
 * 2. X-Real-IP (nginx standard)
 * 3. Fallback to direct connection IP
 */
private fun io.ktor.server.application.ApplicationCall.getRealIpAddress(): String {
    // X-Forwarded-For can contain multiple IPs: "client, proxy1, proxy2"
    // We want the first one (original client)
    request.headers["X-Forwarded-For"]?.let { forwarded ->
        val clientIp = forwarded.split(",").firstOrNull()?.trim()
        if (!clientIp.isNullOrBlank() && clientIp != "unknown") {
            log.debug("Client IP from X-Forwarded-For: {}", clientIp)
            return clientIp
        }
    }
    
    // X-Real-IP is set by some proxies (nginx)
    request.headers["X-Real-IP"]?.let { realIp ->
        if (realIp.isNotBlank() && realIp != "unknown") {
            log.debug("Client IP from X-Real-IP: {}", realIp)
            return realIp
        }
    }
    
    // Fallback to direct connection IP (will be Docker IP if behind proxy)
    val fallbackIp = request.origin.remoteAddress
    log.debug("Client IP from origin.remoteAddress (fallback): {}", fallbackIp)
    return fallbackIp
}

/**
 * Submit a review for a transaction
 */
fun Route.submitReviewRoute() {
    val reviewDao: ReviewDao by inject(ReviewDao::class.java)
    val transactionDao: BarterTransactionDao by inject(BarterTransactionDao::class.java)
    val reputationDao: ReputationDao by inject(ReputationDao::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)
    val profileDao: UserProfileDao by inject(UserProfileDaoImpl::class.java)
    val eligibilityService: ReviewEligibilityService by inject(ReviewEligibilityService::class.java)
    val weightService: ReviewWeightService by inject(ReviewWeightService::class.java)
    val riskAnalysisService: RiskAnalysisService by inject(RiskAnalysisService::class.java)
    val locationService: LocationPatternDetectionService by inject(LocationPatternDetectionService::class.java)
    val deviceService: DevicePatternDetectionService by inject(DevicePatternDetectionService::class.java)
    val ipService: IpPatternDetectionService by inject(IpPatternDetectionService::class.java)

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
                    val createdAt = profileDao.getUserCreatedAt(userId)
                    if (createdAt != null) {
                        Duration.between(createdAt, Instant.now())
                    } else {
                        // If user registration date not found, assume account is old enough
                        // This handles legacy accounts or accounts created before this tracking
                        log.warn("Could not find registration date for userId={}, assuming account is old enough", userId)
                        Duration.ofDays(30)
                    }
                },
                getReviewsInLastDays = { userId, days ->
                    reviewDao.getReviewsInLastDays(userId, days)
                }
            )

            if (!eligibility.canReview) {
                log.info("Review eligibility failed for reviewerId={}: {}", request.reviewerId, eligibility.reason)
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to eligibility.reason)
                )
            }

            // Extract request metadata for risk analysis
            val deviceFingerprint = call.request.headers["X-Device-Fingerprint"]
            val ipAddress = call.getRealIpAddress() // Get real client IP (handles Docker/proxy)
            val userAgent = call.request.headers["User-Agent"]

            // Track patterns for risk analysis (device and IP only, no GPS)
            if (deviceFingerprint != null) {
                deviceService.trackDeviceUsage(
                    userId = request.reviewerId,
                    deviceFingerprint = deviceFingerprint,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                    action = "review_submit"
                )
            }

            ipService.trackIpUsage(
                userId = request.reviewerId,
                ipAddress = ipAddress,
                action = "review_submit"
            )

            // Location tracking is now done via profile location changes only
            // No real-time GPS tracking during review submission
            
            // Perform risk analysis on the transaction
            val riskReport = riskAnalysisService.analyzeTransactionRisk(
                transactionId = request.transactionId,
                user1Id = request.reviewerId,
                user2Id = request.targetUserId,
                getAccountAge = { userId ->
                    val createdAt = profileDao.getUserCreatedAt(userId)
                    if (createdAt != null) {
                        Duration.between(createdAt, Instant.now())
                    } else {
                        Duration.ofDays(30) // Default for legacy accounts
                    }
                },
                getTradingPartners = { userId ->
                    transactionDao.getTradingPartners(userId)
                }
            )
            
            // Apply risk-based policies
            when (riskReport.riskLevel) {
                "CRITICAL" -> {
                    log.error("CRITICAL risk detected for transactionId={}: {}", 
                        request.transactionId, riskReport.detectedPatterns)
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf(
                            "error" to "Review blocked due to suspicious activity",
                            "reason" to "Multiple high-risk patterns detected"
                        )
                    )
                }
                "HIGH" -> {
                    log.warn("HIGH risk detected for transactionId={}: {}", 
                        request.transactionId, riskReport.detectedPatterns)
                    // Flag for manual review but allow submission
                    // Weight will be reduced below
                }
                else -> {
                    // MEDIUM, LOW, MINIMAL - proceed normally
                }
            }
            
            // Update transaction risk score
            transactionDao.updateRiskScore(request.transactionId, riskReport.overallRiskScore)

            // Parse transaction status
            val transactionStatus =
                TransactionStatus.fromString(request.transactionStatus) ?: run {
                    log.warn("Invalid transaction status: {}", request.transactionStatus)
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid transaction status: ${request.transactionStatus}")
                    )
                }

            // Calculate review weight
            val transaction = transactionDao.getTransaction(request.transactionId)
            val reviewerReputation = reputationDao.getReputation(request.reviewerId)
            
            val weight = weightService.calculateReviewWeight(
                review = ReviewSubmission(
                    barterTransactionId = request.transactionId,
                    reviewerId = request.reviewerId,
                    targetUserId = request.targetUserId,
                    rating = request.rating,
                    reviewText = request.reviewText,
                    transactionStatus = transactionStatus
                ),
                reviewerAccountType = AccountType.INDIVIDUAL,
                transactionValue = transaction?.estimatedValue,
                reviewerReputation = reviewerReputation?.let {
                    ReviewWeightService.ReviewerReputation(
                        averageRating = it.averageRating,
                        totalReviews = it.totalReviews
                    )
                },
                isVerifiedTransaction = transaction?.locationConfirmed ?: false
            )
            
            // Adjust weight based on risk analysis
            val adjustedWeight = when (riskReport.riskLevel) {
                "HIGH" -> weight.baseWeight * 0.5 // Reduce weight by 50% for high risk
                "MEDIUM" -> weight.baseWeight * 0.75 // Reduce weight by 25% for medium risk
                else -> weight.baseWeight // No adjustment for low/minimal risk
            }

            // Determine moderation status
            val moderationStatus = when {
                transactionStatus == TransactionStatus.SCAM -> "pending"
                riskReport.requiresManualReview -> "pending"
                riskReport.riskLevel == "HIGH" -> "pending"
                else -> null
            }

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
                reviewWeight = adjustedWeight,
                isVisible = false, // Hidden until blind review period ends
                submittedAt = Instant.now(),
                revealedAt = null,
                isVerified = false,
                moderationStatus = moderationStatus
            )

            val success = reviewDao.createReview(review)

            if (success) {
                // Check if both parties have submitted, reveal if ready
                if (transaction != null) {
                    val bothSubmitted = reviewDao.haveBothPartiesSubmitted(
                        transactionId = request.transactionId,
                        user1Id = transaction.user1Id,
                        user2Id = transaction.user2Id
                    )
                    
                    if (bothSubmitted) {
                        // Both parties have submitted, reveal reviews immediately
                        reviewDao.makeReviewsVisible(request.transactionId)
                    }
                }

                val responseMessage = when {
                    riskReport.requiresManualReview -> 
                        "Review submitted and flagged for manual review due to risk patterns. It will be visible after both parties submit reviews or 14-day deadline."
                    riskReport.riskLevel == "HIGH" ->
                        "Review submitted with reduced weight due to detected risk patterns. It will be visible after both parties submit reviews or 14-day deadline."
                    else ->
                        "Review submitted. It will be visible after both parties submit reviews or 14-day deadline."
                }
                
                // Include risk analysis in response if there are any risk concerns
                val includeRiskAnalysis = riskReport.riskLevel != "MINIMAL" && riskReport.riskLevel != "LOW"
                
                call.respond(
                    HttpStatusCode.Created,
                    SubmitReviewResponse(
                        success = true,
                        reviewId = reviewId,
                        message = responseMessage,
                        riskAnalysis = if (includeRiskAnalysis) riskReport else null
                    )
                )
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to submit review")
                )
            }

        } catch (e: Exception) {
            log.error("Error submitting review", e)
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
    val reviewDao: ReviewDao by inject(ReviewDaoImpl::class.java)
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
            val transaction =
                transactionDao.getTransaction(transactionId) ?: return@get call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Transaction not found")
                )

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
                    reason = "No completed transaction found"
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
                    reason = "You have already reviewed this transaction"
                ))
            }

            call.respond(HttpStatusCode.OK, ReviewEligibilityResponse(
                eligible = true,
                transactionId = completedTransaction.id,
                reason = null,
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