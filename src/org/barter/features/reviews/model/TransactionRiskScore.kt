package org.barter.features.reviews.model

import kotlinx.serialization.Serializable

/**
 * Risk assessment for a barter transaction.
 * Used to detect potential fraud, wash trading, or Sybil attacks.
 */
@Serializable
data class TransactionRiskScore(
    /**
     * Overall risk score from 0.0 (safe) to 1.0 (very risky).
     */
    val score: Double,

    /**
     * List of risk factors detected.
     */
    val riskFactors: List<String>
) {
    init {
        require(score in 0.0..1.0) { "Risk score must be between 0.0 and 1.0" }
    }

    /**
     * Whether this transaction should be flagged for review.
     */
    fun requiresReview(): Boolean = score >= 0.6

    /**
     * Whether this transaction should be blocked.
     */
    fun shouldBlock(): Boolean = score >= 0.9
}
