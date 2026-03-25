package app.bartering.features.wallet.model

import java.time.Instant

/**
 * Immutable top-level ledger transaction.
 */
data class LedgerTransaction(
    val id: String,
    val type: TransactionType,
    val amount: Long,
    val fromUserId: String?,
    val toUserId: String?,
    val externalRef: String? = null,
    val metadataJson: String? = null,
    val createdAt: Instant
)
