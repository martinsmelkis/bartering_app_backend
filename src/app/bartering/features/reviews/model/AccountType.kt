package app.bartering.features.reviews.model

/**
 * Types of user accounts in the barter system.
 * Used for reputation weighting and trust calculations.
 */
enum class AccountType(val value: String) {
    /**
     * Standard individual user account.
     */
    INDIVIDUAL("individual"),

    /**
     * Individual account with verified identity.
     */
    INDIVIDUAL_VERIFIED("individual_verified"),

    /**
     * Business account without verification.
     */
    BUSINESS_UNVERIFIED("business_unverified"),

    /**
     * Business account with verified credentials.
     */
    BUSINESS_VERIFIED("business_verified"),

    /**
     * Administrative account allowed to access internal compliance tooling.
     */
    ADMIN("admin"),

    /**
     * Moderator account allowed to access internal User moderation dashboard.
     */
    MODERATOR("moderator"),

    /**
     * Account suspended due to violations or suspicious activity.
     */
    SUSPENDED("suspended");

    companion object {
        fun fromString(value: String): AccountType? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}
