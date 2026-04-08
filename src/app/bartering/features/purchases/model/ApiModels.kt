package app.bartering.features.purchases.model

import kotlinx.serialization.Serializable

@Serializable
data class PurchasePremiumLifetimeRequest(
    val userId: String,
    val currency: String,
    val amountMinor: Long,
    val externalRef: String? = null,
    val metadataJson: String? = null
)

@Serializable
data class PurchaseCoinPackRequest(
    val userId: String,
    val coinAmount: Long,
    val currency: String,
    val amountMinor: Long,
    val externalRef: String? = null,
    val metadataJson: String? = null
)

@Serializable
data class PurchaseVisibilityBoostRequest(
    val userId: String,
    val boostType: String,
    val costCoins: Long,
    val metadataJson: String? = null
)

@Serializable
data class PurchaseResponse(
    val id: String,
    val userId: String,
    val purchaseType: String,
    val status: String,
    val currency: String? = null,
    val fiatAmountMinor: Long? = null,
    val coinAmount: Long? = null,
    val externalRef: String? = null,
    val metadataJson: String? = null,
    val fulfillmentRef: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class PremiumStatusResponse(
    val userId: String,
    val isPremium: Boolean,
    val isLifetime: Boolean,
    val grantedAt: Long? = null,
    val expiresAt: Long? = null,
    val updatedAt: Long
)

@Serializable
data class PurchaseOperationResponse(
    val success: Boolean,
    val message: String? = null,
    val purchase: PurchaseResponse? = null
)
