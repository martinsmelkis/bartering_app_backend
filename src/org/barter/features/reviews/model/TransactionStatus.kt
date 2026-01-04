package org.barter.features.reviews.model

/**
 * Status values that can be assigned to a barter transaction.
 * These are used to track the outcome of trades and influence reputation.
 */
enum class TransactionStatus(val value: String) {
    /**
     * Transaction is still being negotiated/arranged.
     */
    PENDING("pending"),

    /**
     * Both parties confirmed successful completion of the barter.
     */
    DONE("done"),

    /**
     * Transaction was cancelled by mutual agreement or one party.
     */
    CANCELLED("cancelled"),

    /**
     * Transaction expired without completion (e.g., no response, timing issues).
     */
    EXPIRED("expired"),

    /**
     * Users talked but decided not to proceed with the trade.
     */
    NO_DEAL("no_deal"),

    /**
     * One party reported fraudulent/scam behavior. Triggers moderation.
     */
    SCAM("scam"),

    /**
     * Transaction is disputed - requires mediation/review.
     */
    DISPUTED("disputed");

    companion object {
        fun fromString(value: String): TransactionStatus? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}
