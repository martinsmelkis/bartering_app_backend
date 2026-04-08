package app.bartering.features.wallet.model

import kotlinx.serialization.Serializable

@Serializable
data class WalletResponse(
    val userId: String,
    val availableBalance: Long,
    val lockedBalance: Long,
    val totalEarned: Long,
    val totalSpent: Long,
    val updatedAt: Long
)

@Serializable
data class WalletTransactionResponse(
    val id: String,
    val type: String,
    val amount: Long,
    val fromUserId: String?,
    val toUserId: String?,
    val externalRef: String? = null,
    val metadataJson: String? = null,
    val createdAt: Long
)

@Serializable
data class TransferCoinsRequest(
    val fromUserId: String,
    val toUserId: String,
    val amount: Long,
    val transactionType: String = TransactionType.TIP.value,
    val externalRef: String? = null,
    val metadataJson: String? = null
)

@Serializable
data class WalletOperationResponse(
    val success: Boolean,
    val message: String? = null
)

@Serializable
data class ClaimAwardRequest(
    val userId: String,
    val awardType: String,
    val externalRef: String? = null,
    val metadataJson: String? = null
)

@Serializable
data class ClaimAwardResponse(
    val success: Boolean,
    val awarded: Boolean,
    val awardType: String,
    val amount: Long,
    val externalRef: String,
    val message: String
)
