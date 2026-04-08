package app.bartering.features.purchases.service

import app.bartering.features.purchases.dao.PurchasesDao
import app.bartering.features.purchases.model.PremiumEntitlement
import app.bartering.features.purchases.model.PurchaseStatus
import app.bartering.features.purchases.model.PurchaseType
import app.bartering.features.purchases.model.UserPurchase
import app.bartering.features.wallet.model.TransactionType
import app.bartering.features.wallet.service.WalletService
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

class PurchasesServiceImpl(
    private val purchasesDao: PurchasesDao,
    private val walletService: WalletService
) : PurchasesService {

    private data class VisibilityBoostDefinition(
        val canonicalType: String,
        val costCoins: Long,
        val durationHours: Long
    )

    private val visibilityBoostDefinitionsByType = mapOf(
        // Existing backend types
        "single_24h" to VisibilityBoostDefinition("single_24h", costCoins = 50L, durationHours = 24L),
        "single_72h" to VisibilityBoostDefinition("single_72h", costCoins = 120L, durationHours = 72L),
        "weekend" to VisibilityBoostDefinition("weekend", costCoins = 180L, durationHours = 72L),

        // Client compatibility aliases from create_posting_screen.dart
        "posting_visibility_3_days" to VisibilityBoostDefinition("posting_visibility_3_days", costCoins = 20L, durationHours = 72L),
        "posting_visibility_7_days" to VisibilityBoostDefinition("posting_visibility_7_days", costCoins = 50L, durationHours = 168L)
    )

    private val premiumLifetimePriceMinorByCurrency = mapOf(
        "EUR" to 999L,
        "USD" to 1099L,
        "GBP" to 899L
    )

    private val coinPackCatalog = mapOf(
        "coins_100" to CoinPackDefinition(coinAmount = 100L, priceMinor = mapOf("EUR" to 199L, "USD" to 199L, "GBP" to 179L)),
        "coins_500" to CoinPackDefinition(coinAmount = 500L, priceMinor = mapOf("EUR" to 799L, "USD" to 799L, "GBP" to 699L)),
        "coins_1200" to CoinPackDefinition(coinAmount = 1200L, priceMinor = mapOf("EUR" to 1499L, "USD" to 1499L, "GBP" to 1299L))
    )

    private data class CoinPackDefinition(
        val coinAmount: Long,
        val priceMinor: Map<String, Long>
    )

    override suspend fun purchasePremiumLifetime(
        userId: String,
        currency: String,
        amountMinor: Long,
        externalRef: String?,
        metadataJson: String?
    ): UserPurchase? {
        val normalizedCurrency = currency.trim().uppercase(Locale.ROOT)
        val expectedAmountMinor = premiumLifetimePriceMinorByCurrency[normalizedCurrency] ?: return null
        if (amountMinor != expectedAmountMinor) return null

        val now = Instant.now()
        val purchase = UserPurchase(
            id = UUID.randomUUID().toString(),
            userId = userId,
            purchaseType = PurchaseType.PREMIUM_LIFETIME,
            status = PurchaseStatus.COMPLETED,
            currency = normalizedCurrency,
            fiatAmountMinor = amountMinor,
            coinAmount = null,
            externalRef = externalRef,
            metadataJson = metadataJson,
            fulfillmentRef = "premium:lifetime",
            createdAt = now,
            updatedAt = now
        )

        val created = purchasesDao.createPurchase(purchase)
        if (!created) return null

        val entitlement = PremiumEntitlement(
            userId = userId,
            isPremium = true,
            isLifetime = true,
            grantedByPurchaseId = purchase.id,
            grantedAt = now,
            expiresAt = null,
            updatedAt = now
        )
        val entitlementUpdated = purchasesDao.upsertPremiumEntitlement(entitlement)
        if (!entitlementUpdated) return null

        return purchase
    }

    override suspend fun purchaseCoinPack(
        userId: String,
        coinAmount: Long,
        currency: String,
        amountMinor: Long,
        externalRef: String?,
        metadataJson: String?
    ): UserPurchase? {
        val normalizedCurrency = currency.trim().uppercase(Locale.ROOT)
        val selectedPack = coinPackCatalog.values.firstOrNull { it.coinAmount == coinAmount } ?: return null
        val expectedAmountMinor = selectedPack.priceMinor[normalizedCurrency] ?: return null
        if (amountMinor != expectedAmountMinor) return null

        val now = Instant.now()
        val purchaseId = UUID.randomUUID().toString()
        val purchase = UserPurchase(
            id = purchaseId,
            userId = userId,
            purchaseType = PurchaseType.COIN_PACK,
            status = PurchaseStatus.PENDING,
            currency = normalizedCurrency,
            fiatAmountMinor = amountMinor,
            coinAmount = selectedPack.coinAmount,
            externalRef = externalRef,
            metadataJson = metadataJson,
            fulfillmentRef = null,
            createdAt = now,
            updatedAt = now
        )

        val created = purchasesDao.createPurchase(purchase)
        if (!created) return null

        val walletCredited = walletService.earnCoins(
            userId = userId,
            amount = selectedPack.coinAmount,
            transactionType = TransactionType.PURCHASE_COIN_PACK,
            externalRef = "purchase:$purchaseId",
            metadataJson = metadataJson
        )

        if (!walletCredited) {
            purchasesDao.updatePurchaseStatus(purchaseId, PurchaseStatus.FAILED)
            return null
        }

        purchasesDao.updatePurchaseStatus(
            purchaseId,
            PurchaseStatus.COMPLETED,
            fulfillmentRef = "wallet_tx:purchase:$purchaseId"
        )

        return purchase.copy(
            status = PurchaseStatus.COMPLETED,
            fulfillmentRef = "wallet_tx:purchase:$purchaseId",
            updatedAt = Instant.now()
        )
    }

    override suspend fun purchaseVisibilityBoost(
        userId: String,
        boostType: String,
        costCoins: Long,
        metadataJson: String?
    ): UserPurchase? {
        val normalizedBoostType = boostType.trim().lowercase(Locale.ROOT)
        val boostDefinition = visibilityBoostDefinitionsByType[normalizedBoostType] ?: return null
        val expectedCostCoins = boostDefinition.costCoins
        if (costCoins != expectedCostCoins) return null

        val now = Instant.now()
        val purchaseId = UUID.randomUUID().toString()
        val purchase = UserPurchase(
            id = purchaseId,
            userId = userId,
            purchaseType = PurchaseType.VISIBILITY_BOOST,
            status = PurchaseStatus.PENDING,
            currency = null,
            fiatAmountMinor = null,
            coinAmount = expectedCostCoins,
            externalRef = null,
            metadataJson = metadataJson,
            fulfillmentRef = null,
            createdAt = now,
            updatedAt = now
        )

        val created = purchasesDao.createPurchase(purchase)
        if (!created) return null

        val boostMetadata = "{\"type\":\"purchase_boost\",\"boostType\":\"$normalizedBoostType\",\"canonicalBoostType\":\"${boostDefinition.canonicalType}\",\"purchaseId\":\"$purchaseId\",\"payload\":${metadataJson ?: "null"}}"
        val spent = walletService.spendCoins(
            userId = userId,
            amount = expectedCostCoins,
            externalRef = "boost:$purchaseId:$normalizedBoostType",
            metadataJson = boostMetadata
        )

        if (!spent) {
            purchasesDao.updatePurchaseStatus(purchaseId, PurchaseStatus.FAILED)
            return null
        }

        val boostDurationHours = boostDefinition.durationHours
        val entitlementExpiresAt = now.plus(boostDurationHours, ChronoUnit.HOURS)

        val existingEntitlement = purchasesDao.getPremiumEntitlement(userId)
        val existingExpiresAt = existingEntitlement.expiresAt
        val effectiveExpiresAt = if (existingExpiresAt != null && existingExpiresAt.isAfter(entitlementExpiresAt)) {
            existingExpiresAt
        } else {
            entitlementExpiresAt
        }

        val updatedEntitlement = PremiumEntitlement(
            userId = userId,
            isPremium = existingEntitlement.isPremium,
            isLifetime = existingEntitlement.isLifetime,
            grantedByPurchaseId = purchaseId,
            grantedAt = now,
            expiresAt = if (existingEntitlement.isLifetime) null else effectiveExpiresAt,
            updatedAt = now
        )
        purchasesDao.upsertPremiumEntitlement(updatedEntitlement)

        purchasesDao.updatePurchaseStatus(
            purchaseId,
            PurchaseStatus.COMPLETED,
            fulfillmentRef = "boost:$normalizedBoostType"
        )

        return purchase.copy(
            status = PurchaseStatus.COMPLETED,
            fulfillmentRef = "boost:$normalizedBoostType",
            updatedAt = Instant.now()
        )
    }

    override suspend fun getPremiumStatus(userId: String): PremiumEntitlement {
        return purchasesDao.getPremiumEntitlement(userId)
    }

    override suspend fun getPurchaseHistory(userId: String, limit: Int, offset: Long): List<UserPurchase> {
        return purchasesDao.getPurchasesForUser(userId, limit, offset)
    }
}
