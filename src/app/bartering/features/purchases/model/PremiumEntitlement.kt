package app.bartering.features.purchases.model

import java.time.Instant

data class PremiumEntitlement(
    val userId: String,
    val isPremium: Boolean,
    val isLifetime: Boolean,
    val grantedByPurchaseId: String? = null,
    val grantedAt: Instant? = null,
    val updatedAt: Instant
)
