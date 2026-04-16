package app.bartering.features.purchases.model

import java.time.Instant

data class PremiumEntitlement(
    val userId: String,
    val isPremium: Boolean,
    val isLifetime: Boolean,
    val grantedByPurchaseId: String? = null,
    val grantedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val updatedAt: Instant,
    val rcCustomerId: String? = null,
    val rcAppUserId: String? = null,
    val rcEntitlementId: String? = null,
    val rcLastEventId: String? = null,
    val rcLastEventType: String? = null,
    val lastEventAt: Instant? = null,
    val entitlementSource: String? = null
)
