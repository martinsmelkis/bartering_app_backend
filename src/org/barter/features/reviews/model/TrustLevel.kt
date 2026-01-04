package org.barter.features.reviews.model

/**
 * Trust levels based on user's trading history and reputation.
 * Higher trust levels unlock more features and carry more weight in the system.
 */
enum class TrustLevel(val value: String) {
    /**
     * New user with fewer than 5 reviews.
     */
    NEW("new"),

    /**
     * User with 5-19 reviews.
     */
    EMERGING("emerging"),

    /**
     * User with 20-99 reviews.
     */
    ESTABLISHED("established"),

    /**
     * User with 100+ reviews and high trade diversity score.
     */
    TRUSTED("trusted"),

    /**
     * Trusted user who has also completed identity verification.
     */
    VERIFIED("verified");

    companion object {
        fun fromString(value: String): TrustLevel? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}
