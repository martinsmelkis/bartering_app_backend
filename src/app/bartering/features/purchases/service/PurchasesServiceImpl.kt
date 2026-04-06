package app.bartering.features.purchases.service

import app.bartering.features.purchases.dao.PurchasesDao
import app.bartering.features.purchases.model.PremiumEntitlement
import app.bartering.features.purchases.model.PurchaseStatus
import app.bartering.features.purchases.model.PurchaseType
import app.bartering.features.purchases.model.UserPurchase
import app.bartering.features.wallet.model.TransactionType
import app.bartering.features.wallet.service.WalletService
import java.time.Instant
import java.util.Locale
import java.util.UUID

class PurchasesServiceImpl(
    private val purchasesDao: PurchasesDao,
    private val walletService: WalletService
) : PurchasesService {

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

    private val visibilityBoostCostByType = mapOf(
        "single_24h" to 50L,
        "single_72h" to 120L,
        "weekend" to 180L
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
        val expectedCostCoins = visibilityBoostCostByType[normalizedBoostType] ?: return null
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

        val boostMetadata = "{\"type\":\"purchase_boost\",\"boostType\":\"$normalizedBoostType\",\"purchaseId\":\"$purchaseId\",\"payload\":${metadataJson ?: "null"}}"
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
