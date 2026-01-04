package org.barter.features.reviews.model

/**
 * Risk factors that can be detected during transaction analysis.
 * Used to identify potential fraud, wash trading, or Sybil attacks.
 */
enum class RiskFactor(val value: String, val description: String) {
    /**
     * Both users are accessing from the same device.
     */
    SAME_DEVICE("same_device", "Same device fingerprint detected"),

    /**
     * Both users are accessing from the same IP address.
     */
    SAME_IP("same_ip", "Same IP address detected"),

    /**
     * Both users' GPS locations are within 100 meters.
     */
    SAME_LOCATION("same_location", "Users at nearly identical locations"),

    /**
     * Both accounts created within 30 days.
     */
    BOTH_NEW_ACCOUNTS("both_new_accounts", "Both accounts are new"),

    /**
     * Users only trade within a small closed group.
     */
    NO_OTHER_CONNECTIONS("no_other_connections", "No trading outside small circle"),

    /**
     * Unusual patterns like rapid sequential trades.
     */
    UNUSUAL_TRANSACTION_PATTERN("unusual_transaction_pattern", "Suspicious transaction pattern"),

    /**
     * Same contact info (email, phone) linked to multiple accounts.
     */
    MATCHED_CONTACT_INFO("matched_contact_info", "Shared contact information detected");

    companion object {
        fun fromString(value: String): RiskFactor? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}
