package app.bartering.features.wallet.model

/**
 * Per-wallet posting generated from a ledger transaction.
 */
data class LedgerEntry(
    val id: String,
    val transactionId: String,
    val userId: String,
    val delta: Long,
    val balanceAfter: Long
)
