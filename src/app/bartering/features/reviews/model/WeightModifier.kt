package app.bartering.features.reviews.model

/**
 * Modifiers that affect the weight/impact of a review on reputation.
 * Used to give more credibility to certain types of reviews.
 */
enum class WeightModifier(val value: String, val multiplier: Double) {
    /**
     * Review from a verified business account.
     */
    VERIFIED_BUSINESS("verified_business", 1.2),

    /**
     * Review from an unverified account.
     */
    UNVERIFIED_ACCOUNT("unverified_account", 0.5),

    /**
     * Review for a high-value transaction (>$1000 equivalent).
     */
    HIGH_VALUE_TRADE("high_value_trade", 1.5),

    /**
     * Review for a low-value transaction (<$10 equivalent).
     */
    LOW_VALUE_TRADE("low_value_trade", 0.5),

    /**
     * Review from a trusted reviewer (4.5+ rating, 50+ reviews).
     */
    TRUSTED_REVIEWER("trusted_reviewer", 1.3),

    /**
     * Review from a new account (<7 days old).
     */
    NEW_REVIEWER("new_reviewer", 0.6),

    /**
     * Review verified through random sampling/proof request.
     */
    VERIFIED_TRANSACTION("verified_transaction", 1.4);

    companion object {
        fun fromString(value: String): WeightModifier? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}
