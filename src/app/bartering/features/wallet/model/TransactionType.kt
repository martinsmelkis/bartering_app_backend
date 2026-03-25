package app.bartering.features.wallet.model

/**
 * Canonical transaction types for Barter Coins ledger operations.
 */
enum class TransactionType(val value: String) {
    EARN("earn"),
    TIP("tip"),
    BONUS("bonus"),
    SPEND("spend"),
    ADJUSTMENT("adjustment"),
    REVERSAL("reversal"),
    LOCK("lock"),
    UNLOCK("unlock");

    companion object {
        fun fromString(value: String): TransactionType? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}
