package org.barter.features.reviews.service

import org.barter.features.reviews.model.*
import java.math.BigDecimal

/**
 * Service for calculating the weight/impact of a review on reputation.
 * Not all reviews are equal - this implements the weighting system.
 */
class ReviewWeightService {

    /**
     * Calculates the weight of a review based on various factors.
     */
    suspend fun calculateReviewWeight(
        review: ReviewSubmission,
        reviewerAccountType: AccountType,
        transactionValue: BigDecimal?,
        reviewerReputation: ReviewerReputation?,
        isVerifiedTransaction: Boolean
    ): ReviewWeight {
        var weight = 1.0
        val modifiers = mutableListOf<String>()

        // Account type weighting
        when (reviewerAccountType) {
            AccountType.BUSINESS_VERIFIED -> {
                weight *= WeightModifier.VERIFIED_BUSINESS.multiplier
                modifiers.add(WeightModifier.VERIFIED_BUSINESS.value)
            }
            AccountType.BUSINESS_UNVERIFIED, AccountType.INDIVIDUAL -> {
                // Standard weight
            }
            AccountType.SUSPENDED -> {
                weight *= 0.1 // Heavily discounted
                modifiers.add("suspended_account")
            }
        }

        // Transaction value weighting
        transactionValue?.let { value ->
            when {
                value >= BigDecimal(1000) -> {
                    weight *= WeightModifier.HIGH_VALUE_TRADE.multiplier
                    modifiers.add(WeightModifier.HIGH_VALUE_TRADE.value)
                }
                value < BigDecimal(10) -> {
                    weight *= WeightModifier.LOW_VALUE_TRADE.multiplier
                    modifiers.add(WeightModifier.LOW_VALUE_TRADE.value)
                }
            }
        }

        // Reviewer reputation weighting
        reviewerReputation?.let { rep ->
            if (rep.averageRating > 4.5 && rep.totalReviews > 50) {
                weight *= WeightModifier.TRUSTED_REVIEWER.multiplier
                modifiers.add(WeightModifier.TRUSTED_REVIEWER.value)
            }
        }

        // Verified transaction bonus
        if (isVerifiedTransaction) {
            weight *= WeightModifier.VERIFIED_TRANSACTION.multiplier
            modifiers.add(WeightModifier.VERIFIED_TRANSACTION.value)
        }

        // Cap the weight to reasonable bounds
        weight = weight.coerceIn(0.1, 2.0)

        return ReviewWeight(weight, modifiers)
    }

    /**
     * Simplified reviewer reputation data.
     */
    data class ReviewerReputation(
        val averageRating: Double,
        val totalReviews: Int
    )
}
