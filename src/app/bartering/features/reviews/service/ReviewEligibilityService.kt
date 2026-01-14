package app.bartering.features.reviews.service

import app.bartering.features.reviews.model.ReviewEligibility
import app.bartering.features.reviews.model.TransactionStatus
import java.time.Duration
import java.time.Instant

/**
 * Service to determine if a user is eligible to leave a review.
 * Implements anti-abuse checks.
 */
class ReviewEligibilityService {

    /**
     * Checks if a user can submit a review for a transaction.
     */
    suspend fun checkReviewEligibility(
        reviewerId: String,
        targetUserId: String,
        transactionId: String,
        getTransaction: suspend (String) -> Transaction?,
        hasAlreadyReviewed: suspend (String, String, String) -> Boolean,
        getAccountAge: suspend (String) -> Duration,
        getReviewsInLastDays: suspend (String, Int) -> Int
    ): ReviewEligibility {
        // Check if user is reviewing themselves
        if (reviewerId == targetUserId) {
            return ReviewEligibility(false, "Cannot review yourself")
        }

        // Check if transaction exists and is completed
        val transaction = getTransaction(transactionId) ?: return ReviewEligibility(
            false,
            "Transaction not found"
        )

        if (transaction.status != TransactionStatus.DONE && transaction.status != TransactionStatus.SCAM) {
            return ReviewEligibility(false, "Transaction not completed - status: ${transaction.status}")
        }

        // Check if already reviewed
        if (hasAlreadyReviewed(reviewerId, targetUserId, transactionId)) {
            return ReviewEligibility(false, "Already reviewed this transaction")
        }

        // Check review window (must be within 90 days of completion)
        transaction.completedAt?.let { completedAt ->
            val daysSinceCompletion = Duration.between(completedAt, Instant.now()).toDays()
            if (daysSinceCompletion > 90) {
                return ReviewEligibility(false, "Review window expired (must review within 90 days)")
            }
        }

        // Check account age (must be at least 7 days old)
        val accountAge = getAccountAge(reviewerId)
        if (accountAge < Duration.ofDays(7)) {
            return ReviewEligibility(false, "Account too new to review (must be 7+ days old)")
        }

        // Check review velocity (max 5 reviews per day)
        val recentReviewCount = getReviewsInLastDays(reviewerId, 1)
        if (recentReviewCount >= 5) {
            return ReviewEligibility(
                false,
                "Too many reviews in short period (max 5 per day)",
                requiresVerification = true
            )
        }

        return ReviewEligibility(true)
    }

    /**
     * Simplified transaction data class for eligibility checks.
     */
    data class Transaction(
        val id: String,
        val status: TransactionStatus,
        val completedAt: Instant?
    )
}
