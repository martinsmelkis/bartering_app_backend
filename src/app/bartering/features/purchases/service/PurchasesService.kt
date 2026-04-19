package app.bartering.features.purchases.service

import app.bartering.features.purchases.model.PremiumEntitlement
import app.bartering.features.purchases.model.UserPurchase

interface PurchasesService {
    suspend fun purchasePremiumLifetime(
        userId: String,
        currency: String,
        amountMinor: Long,
        externalRef: String? = null,
        metadataJson: String? = null
    ): UserPurchase?

    suspend fun purchaseCoinPack(
        userId: String,
        coinAmount: Long,
        currency: String,
        amountMinor: Long,
        externalRef: String? = null,
        metadataJson: String? = null
    ): UserPurchase?

    suspend fun purchaseVisibilityBoost(
        userId: String,
        boostType: String,
        costCoins: Long,
        metadataJson: String? = null
    ): UserPurchase?

    suspend fun purchaseAvatarIconUnlock(
        userId: String,
        iconId: String,
        costCoins: Long,
        externalRef: String,
        metadataJson: String? = null
    ): UserPurchase?

    suspend fun hasPurchasedAvatarIcon(userId: String, iconId: String): Boolean
    suspend fun getPurchasedAvatarIconIds(userId: String): List<String>

    suspend fun getPremiumStatus(userId: String): PremiumEntitlement
    suspend fun getPurchaseHistory(userId: String, limit: Int = 50, offset: Long = 0): List<UserPurchase>

    suspend fun processRevenueCatWebhook(rawPayload: String, authorizationHeader: String?): RevenueCatWebhookProcessResult
    suspend fun syncPremiumFromRevenueCat(userId: String): PremiumEntitlement
}

data class RevenueCatWebhookProcessResult(
    val accepted: Boolean,
    val duplicate: Boolean = false,
    val eventId: String? = null,
    val message: String
)
