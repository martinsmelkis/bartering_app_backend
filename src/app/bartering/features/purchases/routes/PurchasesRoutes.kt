package app.bartering.features.purchases.routes

import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.utils.verifyRequestSignature
import app.bartering.features.purchases.model.PurchaseCoinPackRequest
import app.bartering.features.purchases.model.PurchaseOperationResponse
import app.bartering.features.purchases.model.PurchasePremiumLifetimeRequest
import app.bartering.features.purchases.model.PurchaseResponse
import app.bartering.features.purchases.model.PurchaseVisibilityBoostRequest
import app.bartering.features.purchases.model.PremiumStatusResponse
import app.bartering.features.purchases.service.PurchasesService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import org.koin.java.KoinJavaComponent.inject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("app.bartering.features.purchases.routes.PurchasesRoutes")

fun Route.getPremiumStatusRoute() {
    val purchasesService: PurchasesService by inject(PurchasesService::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/purchases/premium/status") {
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@get
        }

        try {
            val premium = purchasesService.getPremiumStatus(authenticatedUserId)
            call.respond(
                HttpStatusCode.OK,
                PremiumStatusResponse(
                    userId = premium.userId,
                    isPremium = premium.isPremium,
                    isLifetime = premium.isLifetime,
                    grantedAt = premium.grantedAt?.toEpochMilli(),
                    updatedAt = premium.updatedAt.toEpochMilli()
                )
            )
        } catch (e: Exception) {
            log.error("Failed to fetch premium status for user {}", authenticatedUserId, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to fetch premium status"))
        }
    }
}

fun Route.getPurchaseHistoryRoute() {
    val purchasesService: PurchasesService by inject(PurchasesService::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/purchases/history") {
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@get
        }

        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
        val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
        if (limit !in 1..100 || offset < 0) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid pagination parameters")
            )
        }

        try {
            val purchases = purchasesService.getPurchaseHistory(authenticatedUserId, limit, offset)
            val response = purchases.map {
                PurchaseResponse(
                    id = it.id,
                    userId = it.userId,
                    purchaseType = it.purchaseType.value,
                    status = it.status.value,
                    currency = it.currency,
                    fiatAmountMinor = it.fiatAmountMinor,
                    coinAmount = it.coinAmount,
                    externalRef = it.externalRef,
                    metadataJson = it.metadataJson,
                    fulfillmentRef = it.fulfillmentRef,
                    createdAt = it.createdAt.toEpochMilli(),
                    updatedAt = it.updatedAt.toEpochMilli()
                )
            }
            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            log.error("Failed to fetch purchase history for user {}", authenticatedUserId, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to fetch purchase history"))
        }
    }
}

fun Route.purchasePremiumLifetimeRoute() {
    val purchasesService: PurchasesService by inject(PurchasesService::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/purchases/premium/lifetime") {
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            return@post
        }

        try {
            val request = Json.decodeFromString<PurchasePremiumLifetimeRequest>(requestBody)
            if (request.userId != authenticatedUserId) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "You can only purchase for your own account")
                )
            }

            val purchase = purchasesService.purchasePremiumLifetime(
                userId = request.userId,
                currency = request.currency,
                amountMinor = request.amountMinor,
                externalRef = request.externalRef,
                metadataJson = request.metadataJson
            )

            if (purchase == null) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    PurchaseOperationResponse(success = false, message = "Premium purchase failed")
                )
            }

            call.respond(
                HttpStatusCode.OK,
                PurchaseOperationResponse(
                    success = true,
                    message = "Premium lifetime activated",
                    purchase = PurchaseResponse(
                        id = purchase.id,
                        userId = purchase.userId,
                        purchaseType = purchase.purchaseType.value,
                        status = purchase.status.value,
                        currency = purchase.currency,
                        fiatAmountMinor = purchase.fiatAmountMinor,
                        coinAmount = purchase.coinAmount,
                        externalRef = purchase.externalRef,
                        metadataJson = purchase.metadataJson,
                        fulfillmentRef = purchase.fulfillmentRef,
                        createdAt = purchase.createdAt.toEpochMilli(),
                        updatedAt = purchase.updatedAt.toEpochMilli()
                    )
                )
            )
        } catch (e: Exception) {
            log.error("Failed premium purchase for user {}", authenticatedUserId, e)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request: ${e.message}"))
        }
    }
}

fun Route.purchaseCoinPackRoute() {
    val purchasesService: PurchasesService by inject(PurchasesService::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/purchases/coins") {
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            return@post
        }

        try {
            val request = Json.decodeFromString<PurchaseCoinPackRequest>(requestBody)
            if (request.userId != authenticatedUserId) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "You can only purchase for your own account")
                )
            }

            val purchase = purchasesService.purchaseCoinPack(
                userId = request.userId,
                coinAmount = request.coinAmount,
                currency = request.currency,
                amountMinor = request.amountMinor,
                externalRef = request.externalRef,
                metadataJson = request.metadataJson
            )

            if (purchase == null) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    PurchaseOperationResponse(success = false, message = "Coin pack purchase failed")
                )
            }

            call.respond(
                HttpStatusCode.OK,
                PurchaseOperationResponse(
                    success = true,
                    message = "Coin pack purchased",
                    purchase = PurchaseResponse(
                        id = purchase.id,
                        userId = purchase.userId,
                        purchaseType = purchase.purchaseType.value,
                        status = purchase.status.value,
                        currency = purchase.currency,
                        fiatAmountMinor = purchase.fiatAmountMinor,
                        coinAmount = purchase.coinAmount,
                        externalRef = purchase.externalRef,
                        metadataJson = purchase.metadataJson,
                        fulfillmentRef = purchase.fulfillmentRef,
                        createdAt = purchase.createdAt.toEpochMilli(),
                        updatedAt = purchase.updatedAt.toEpochMilli()
                    )
                )
            )
        } catch (e: Exception) {
            log.error("Failed coin purchase for user {}", authenticatedUserId, e)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request: ${e.message}"))
        }
    }
}

fun Route.purchaseVisibilityBoostRoute() {
    val purchasesService: PurchasesService by inject(PurchasesService::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/purchases/boosts/visibility") {
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            return@post
        }

        try {
            val request = Json.decodeFromString<PurchaseVisibilityBoostRequest>(requestBody)
            if (request.userId != authenticatedUserId) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "You can only purchase for your own account")
                )
            }

            val purchase = purchasesService.purchaseVisibilityBoost(
                userId = request.userId,
                boostType = request.boostType,
                costCoins = request.costCoins,
                metadataJson = request.metadataJson
            )

            if (purchase == null) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    PurchaseOperationResponse(success = false, message = "Visibility boost purchase failed")
                )
            }

            call.respond(
                HttpStatusCode.OK,
                PurchaseOperationResponse(
                    success = true,
                    message = "Visibility boost purchased",
                    purchase = PurchaseResponse(
                        id = purchase.id,
                        userId = purchase.userId,
                        purchaseType = purchase.purchaseType.value,
                        status = purchase.status.value,
                        currency = purchase.currency,
                        fiatAmountMinor = purchase.fiatAmountMinor,
                        coinAmount = purchase.coinAmount,
                        externalRef = purchase.externalRef,
                        metadataJson = purchase.metadataJson,
                        fulfillmentRef = purchase.fulfillmentRef,
                        createdAt = purchase.createdAt.toEpochMilli(),
                        updatedAt = purchase.updatedAt.toEpochMilli()
                    )
                )
            )
        } catch (e: Exception) {
            log.error("Failed visibility boost purchase for user {}", authenticatedUserId, e)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request: ${e.message}"))
        }
    }
}
