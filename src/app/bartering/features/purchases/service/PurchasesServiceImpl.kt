package app.bartering.features.purchases.service

import app.bartering.features.purchases.dao.PurchasesDao
import app.bartering.features.purchases.model.PremiumEntitlement
import app.bartering.features.purchases.model.PurchaseStatus
import app.bartering.features.purchases.model.PurchaseType
import app.bartering.features.purchases.model.UserPurchase
import app.bartering.features.wallet.model.TransactionType
import app.bartering.features.wallet.service.WalletService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

class PurchasesServiceImpl(
    private val purchasesDao: PurchasesDao,
    private val walletService: WalletService
) : PurchasesService {

    private val log = LoggerFactory.getLogger("app.bartering.features.purchases.service.PurchasesServiceImpl")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
    }

    private val revenueCatApiBaseUrl: String
        get() = System.getenv("REVENUECAT_API_BASE_URL")?.trim()?.ifBlank { null } ?: "https://api.revenuecat.com/v2"

    private val revenueCatProjectId: String?
        get() = System.getenv("REVENUECAT_PROJECT_ID")?.trim()?.ifBlank { null }

    private val revenueCatApiKey: String?
        get() = System.getenv("REVENUECAT_API_KEY")?.trim()?.ifBlank { null }

    private val revenueCatWebhookAuthToken: String?
        get() = System.getenv("REVENUECAT_WEBHOOK_AUTH_TOKEN")?.trim()?.ifBlank { null }

    private val revenueCatPremiumEntitlementId: String
        get() = System.getenv("REVENUECAT_PREMIUM_ENTITLEMENT_ID")?.trim()?.ifBlank { null } ?: "Bartering App Premium"

    private val revenueCatCoins20ProductId: String
        get() = System.getenv("REVENUECAT_COINS20_PRODUCT_ID")?.trim()?.ifBlank { null } ?: "web_coins_20"

    private val revenueCatCoins20Amount: Long
        get() = System.getenv("REVENUECAT_COINS20_AMOUNT")?.trim()?.toLongOrNull() ?: 20L

    private data class RevenueCatCoinRewardDefinition(
        val productId: String? = null,
        val coinAmount: Long,
        val placeholder: Boolean = false
    )

    private val revenueCatCoinRewardDefinitions: List<RevenueCatCoinRewardDefinition>
        get() = buildList {
            add(
                RevenueCatCoinRewardDefinition(
                    productId = revenueCatCoins20ProductId,
                    coinAmount = revenueCatCoins20Amount
                )
            )

            // TODO add 2 others

        }

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
            updatedAt = now,
            rcCustomerId = existingEntitlement.rcCustomerId,
            rcAppUserId = existingEntitlement.rcAppUserId,
            rcEntitlementId = existingEntitlement.rcEntitlementId,
            rcLastEventId = existingEntitlement.rcLastEventId,
            rcLastEventType = existingEntitlement.rcLastEventType,
            lastEventAt = existingEntitlement.lastEventAt,
            entitlementSource = existingEntitlement.entitlementSource
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

    override suspend fun processRevenueCatWebhook(rawPayload: String, authorizationHeader: String?): RevenueCatWebhookProcessResult {
        val configuredWebhookToken = revenueCatWebhookAuthToken
        if (!configuredWebhookToken.isNullOrBlank()) {
            val expected = "Bearer $configuredWebhookToken"
            if (authorizationHeader?.trim() != expected) {
                return RevenueCatWebhookProcessResult(
                    accepted = false,
                    message = "Unauthorized RevenueCat webhook"
                )
            }
        }

        val root = try {
            json.parseToJsonElement(rawPayload).jsonObject
        } catch (_: Exception) {
            return RevenueCatWebhookProcessResult(
                accepted = false,
                message = "Invalid JSON payload"
            )
        }

        val event = root["event"]?.jsonObject ?: root
        val eventId = event.stringOrNull("id")
        val eventType = event.stringOrNull("type")
        val eventAt = event.longOrNull("event_timestamp_ms")?.let { Instant.ofEpochMilli(it) }

        if (eventId.isNullOrBlank()) {
            return RevenueCatWebhookProcessResult(
                accepted = false,
                message = "Missing event id"
            )
        }

        val appUserId = event.stringOrNull("app_user_id")
            ?: event.stringOrNull("original_app_user_id")

        val targetUserIds = mutableSetOf<String>()

        if (!appUserId.isNullOrBlank()) targetUserIds += appUserId
        val originalAppUserId = event.stringOrNull("original_app_user_id")
        if (!originalAppUserId.isNullOrBlank()) targetUserIds += originalAppUserId
        targetUserIds += event.stringArrayOrEmpty("transferred_from")
        targetUserIds += event.stringArrayOrEmpty("transferred_to")

        val inserted = purchasesDao.markRevenueCatEventProcessed(
            eventId = eventId,
            appUserId = appUserId,
            eventType = eventType,
            eventAt = eventAt
        )

        if (!inserted) {
            return RevenueCatWebhookProcessResult(
                accepted = true,
                duplicate = true,
                eventId = eventId,
                message = "Duplicate event ignored"
            )
        }

        if (targetUserIds.isEmpty()) {
            return RevenueCatWebhookProcessResult(
                accepted = true,
                duplicate = false,
                eventId = eventId,
                message = "Event stored, no app_user_id to sync"
            )
        }

        val localTargetUserIds = targetUserIds.filter { userId ->
            val existsLocally = purchasesDao.userExists(userId)
            if (!existsLocally) {
                log.warn("Skipping RevenueCat webhook sync for unknown local user {}", userId)
            }
            existsLocally
        }

        if (localTargetUserIds.isEmpty()) {
            return RevenueCatWebhookProcessResult(
                accepted = true,
                duplicate = false,
                eventId = eventId,
                message = "Event stored, no local users to sync"
            )
        }

        localTargetUserIds.forEach { userId ->
            try {
                processRevenueCatCoinRewardsFromEvent(
                    userId = userId,
                    event = event,
                    eventId = eventId
                )

                refreshPremiumFromRevenueCat(
                    userId = userId,
                    entitlementSource = "revenuecat_webhook",
                    lastEventId = eventId,
                    lastEventType = eventType,
                    lastEventAt = eventAt
                )
            } catch (e: Exception) {
                log.error("Failed to sync premium snapshot from RevenueCat webhook for user {}", userId, e)
            }
        }

        return RevenueCatWebhookProcessResult(
            accepted = true,
            duplicate = false,
            eventId = eventId,
            message = "Webhook processed"
        )
    }

    override suspend fun syncPremiumFromRevenueCat(userId: String): PremiumEntitlement {
        return refreshPremiumFromRevenueCat(
            userId = userId,
            entitlementSource = "revenuecat_sync_now",
            lastEventId = null,
            lastEventType = "SYNC_NOW",
            lastEventAt = Instant.now()
        )
    }

    private suspend fun processRevenueCatCoinRewardsFromEvent(
        userId: String,
        event: JsonObject,
        eventId: String
    ) {
        val eventType = event.stringOrNull("type")?.uppercase(Locale.ROOT)
        val allowedCoinRewardEventTypes = setOf("NON_RENEWING_PURCHASE")
        if (eventType !in allowedCoinRewardEventTypes) {
            log.info("Skipping RevenueCat coin reward for event {} (type={})", eventId, eventType)
            return
        }

        val productIds = extractProductIdsFromEvent(event)
        val transactionRef = event.stringOrNull("transaction_id")
            ?: event.stringOrNull("original_transaction_id")
            ?: eventId

        val matchingDefinitions = revenueCatCoinRewardDefinitions.filter { definition ->
            val productMatch = !definition.productId.isNullOrBlank() && productIds.contains(definition.productId)
            productMatch
        }

        if (matchingDefinitions.isEmpty()) return

        matchingDefinitions.forEach { definition ->
            if (definition.coinAmount <= 0L) {
                log.info(
                    "Skipping RevenueCat coin reward for product {} due to non-positive amount {}",
                    definition.productId,
                    definition.coinAmount
                )
                return@forEach
            }

            if (definition.placeholder) {
                log.info(
                    "RevenueCat coin reward placeholder matched for product {} (amount={}); no-op placeholder processing",
                    definition.productId,
                    definition.coinAmount
                )
                return@forEach
            }

            val referenceKey = definition.productId ?: "unknown"
            val walletCredited = walletService.earnCoins(
                userId = userId,
                amount = definition.coinAmount,
                transactionType = TransactionType.PURCHASE_COIN_PACK,
                externalRef = "revenuecat:coinpack:$referenceKey:$transactionRef",
                metadataJson = "{\"source\":\"revenuecat_webhook\",\"eventId\":\"$eventId\",\"eventType\":\"${eventType ?: ""}\",\"transactionRef\":\"$transactionRef\",\"productId\":\"${definition.productId ?: ""}\"}"
            )

            if (!walletCredited) {
                log.warn(
                    "Failed to credit RevenueCat coin reward for user {} product {}",
                    userId,
                    definition.productId
                )
            }
        }
    }

    private fun extractProductIdsFromEvent(event: JsonObject): Set<String> {
        val ids = mutableSetOf<String>()

        event.stringOrNull("product_id")?.let { ids += it }

        val productIdsArray = event["product_ids"] as? JsonArray
        productIdsArray
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.isNotBlank() } }
            ?.forEach { ids += it }

        return ids
    }

    private suspend fun refreshPremiumFromRevenueCat(
        userId: String,
        entitlementSource: String,
        lastEventId: String?,
        lastEventType: String?,
        lastEventAt: Instant?
    ): PremiumEntitlement {
        val apiKey = revenueCatApiKey
            ?: throw IllegalStateException("REVENUECAT_API_KEY is not configured")

        val projectId = revenueCatProjectId
            ?: throw IllegalStateException("REVENUECAT_PROJECT_ID is not configured for RevenueCat API v2")

        val entitlementId = revenueCatPremiumEntitlementId
        val encodedProjectId = projectId.encodeURLPathPart()
        val encodedUserId = userId.encodeURLPathPart()

        val activeEntitlementsUrl = "${revenueCatApiBaseUrl.trimEnd('/')}/projects/$encodedProjectId/customers/$encodedUserId/active_entitlements"
        val customerUrl = "${revenueCatApiBaseUrl.trimEnd('/')}/projects/$encodedProjectId/customers/$encodedUserId"

        val activeEntitlementsBody = httpClient.get(activeEntitlementsUrl) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.Accept, "application/json")
        }.body<String>()

        val customerBody = httpClient.get(customerUrl) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.Accept, "application/json")
        }.body<String>()

        val activeRoot = json.parseToJsonElement(activeEntitlementsBody).jsonObject
        val items = activeRoot["items"]?.jsonArray ?: JsonArray(emptyList())

        val matchingEntitlement = items
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.stringOrNull("entitlement_id") == entitlementId }

        val now = Instant.now()
        val expiresAt = matchingEntitlement
            ?.longOrNull("expires_at")
            ?.let { Instant.ofEpochMilli(it) }
        val isActive = matchingEntitlement != null && (expiresAt == null || expiresAt.isAfter(now))

        val customerRoot = json.parseToJsonElement(customerBody).jsonObject
        val rcCustomerId = customerRoot.stringOrNull("id") ?: userId

        val updated = PremiumEntitlement(
            userId = userId,
            isPremium = isActive,
            isLifetime = isActive && expiresAt == null,
            grantedByPurchaseId = null,
            grantedAt = null,
            expiresAt = expiresAt,
            updatedAt = now,
            rcCustomerId = rcCustomerId,
            rcAppUserId = userId,
            rcEntitlementId = entitlementId,
            rcLastEventId = lastEventId,
            rcLastEventType = lastEventType,
            lastEventAt = lastEventAt,
            entitlementSource = entitlementSource
        )

        purchasesDao.upsertPremiumEntitlement(updated)
        return updated
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.longOrNull(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.longOrNullOrString()
    }

    private fun JsonPrimitive.longOrNullOrString(): Long? {
        return this.contentOrNull?.toLongOrNull()
    }

    private fun JsonObject.stringArrayOrEmpty(key: String): List<String> {
        val element = this[key] ?: return emptyList()
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.isNotBlank() } }
    }

}
