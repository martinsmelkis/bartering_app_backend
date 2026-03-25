package app.bartering.features.wallet.model

import java.time.Instant

/**
 * Read model for a user wallet state.
 */
data class Wallet(
    val userId: String,
    val availableBalance: Long,
    val lockedBalance: Long,
    val totalEarned: Long,
    val totalSpent: Long,
    val updatedAt: Instant
)
