package app.bartering.features.reviews.model

/**
 * Badges that can be earned based on user behavior and reputation.
 * Displayed on user profiles to signal trustworthiness.
 */
enum class ReputationBadge(val value: String, val description: String) {
    /**
     * User has identity verification completed.
     */
    IDENTITY_VERIFIED("identity_verified", "Identity Verified"),

    /**
     * User has completed 100+ successful trades.
     */
    VETERAN_TRADER("veteran_trader", "Veteran Trader - 100+ trades"),

    /**
     * User has an active premium account.
     */
    PREMIUM_USER("premium_user", "Premium User"),

    /**
     * User maintains 4.8+ average rating with 50+ reviews.
     */
    TOP_RATED("top_rated", "Top Rated Seller"),

    /**
     * User always responds within 24 hours.
     */
    QUICK_RESPONDER("quick_responder", "Quick Responder"),

    /**
     * User trades with diverse partners (high diversity score).
     */
    COMMUNITY_CONNECTOR("community_connector", "Community Connector"),

    /**
     * User has verified business registration.
     */
    VERIFIED_BUSINESS("verified_business", "Verified Business"),

    /**
     * User has never had a disputed transaction.
     */
    DISPUTE_FREE("dispute_free", "Dispute-Free History"),

    /**
     * User completes trades faster than average.
     */
    FAST_TRADER("fast_trader", "Fast & Reliable"),

    /**
     * User was among the first 1000 registered users.
     */
    TOP_1000("top_1000", "Early Adopter - First 1000 Users");

    companion object {
        fun fromString(value: String): ReputationBadge? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}
