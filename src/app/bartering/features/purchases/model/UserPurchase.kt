package app.bartering.features.purchases.model

import java.time.Instant

data class UserPurchase(
    val id: String,
    val userId: String,
    val purchaseType: PurchaseType,
    val status: PurchaseStatus,
    val currency: String? = null,
    val fiatAmountMinor: Long? = null,
    val coinAmount: Long? = null,
    val externalRef: String? = null,
    val metadataJson: String? = null,
    val fulfillmentRef: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)
