package app.bartering.features.purchases.dao

import app.bartering.features.purchases.model.PremiumEntitlement
import app.bartering.features.purchases.model.UserPurchase

interface PurchasesDao {
    suspend fun createPurchase(purchase: UserPurchase): Boolean
    suspend fun updatePurchaseStatus(
        purchaseId: String,
        status: app.bartering.features.purchases.model.PurchaseStatus,
        fulfillmentRef: String? = null
    ): Boolean
    suspend fun getPurchasesForUser(userId: String, limit: Int = 50, offset: Long = 0): List<UserPurchase>
    suspend fun getPremiumEntitlement(userId: String): PremiumEntitlement
    suspend fun upsertPremiumEntitlement(entitlement: PremiumEntitlement): Boolean
    suspend fun userExists(userId: String): Boolean
    suspend fun markRevenueCatEventProcessed(eventId: String, appUserId: String?, eventType: String?, eventAt: java.time.Instant?): Boolean
}
