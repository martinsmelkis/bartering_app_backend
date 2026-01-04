package org.barter.features.reviews.model

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
     * Business account without verification.
     */
    BUSINESS_UNVERIFIED("business_unverified"),

    /**
     * Business account with verified credentials.
     */
    BUSINESS_VERIFIED("business_verified"),

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
