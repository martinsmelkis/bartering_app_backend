package app.bartering.features.wallet.routes

import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.utils.verifyRequestSignature
import app.bartering.features.wallet.model.TransferCoinsRequest
import app.bartering.features.wallet.model.TransactionType
import app.bartering.features.wallet.model.WalletOperationResponse
import app.bartering.features.wallet.model.WalletResponse
import app.bartering.features.wallet.model.WalletTransactionResponse
import app.bartering.features.wallet.service.WalletService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import org.koin.java.KoinJavaComponent.inject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("app.bartering.features.wallet.routes.WalletRoutes")

fun Route.getWalletRoute() {
    val walletService: WalletService by inject(WalletService::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/wallet") {
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@get
        }

        try {
            val wallet = walletService.getWallet(authenticatedUserId)
            call.respond(
                HttpStatusCode.OK,
                WalletResponse(
                    userId = wallet.userId,
                    availableBalance = wallet.availableBalance,
                    lockedBalance = wallet.lockedBalance,
                    totalEarned = wallet.totalEarned,
                    totalSpent = wallet.totalSpent,
                    updatedAt = wallet.updatedAt.toEpochMilli()
                )
            )
        } catch (e: Exception) {
            log.error("Failed to fetch wallet for user {}", authenticatedUserId, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to fetch wallet"))
        }
    }
}

fun Route.getWalletTransactionsRoute() {
    val walletService: WalletService by inject(WalletService::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/wallet/transactions") {
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
            val transactions = walletService.getTransactions(authenticatedUserId, limit, offset)
            val response = transactions.map {
                WalletTransactionResponse(
                    id = it.id,
                    type = it.type.value,
                    amount = it.amount,
                    fromUserId = it.fromUserId,
                    toUserId = it.toUserId,
                    externalRef = it.externalRef,
                    metadataJson = it.metadataJson,
                    createdAt = it.createdAt.toEpochMilli()
                )
            }
            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            log.error("Failed to fetch wallet transactions for user {}", authenticatedUserId, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to fetch wallet transactions"))
        }
    }
}

fun Route.transferCoinsRoute() {
    val walletService: WalletService by inject(WalletService::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/wallet/transfer") {
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            return@post
        }

        try {
            val request = Json.decodeFromString<TransferCoinsRequest>(requestBody)

            if (authenticatedUserId != request.fromUserId) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "You can only transfer coins from your own wallet")
                )
            }

            val transactionType = TransactionType.fromString(request.transactionType)
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid transactionType")
                )

            val success = walletService.transferCoins(
                fromUserId = request.fromUserId,
                toUserId = request.toUserId,
                amount = request.amount,
                transactionType = transactionType,
                externalRef = request.externalRef,
                metadataJson = request.metadataJson
            )

            if (!success) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    WalletOperationResponse(success = false, message = "Transfer failed")
                )
            }

            call.respond(
                HttpStatusCode.OK,
                WalletOperationResponse(success = true, message = "Transfer successful")
            )
        } catch (e: Exception) {
            log.error("Failed to transfer coins for user {}", authenticatedUserId, e)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request: ${e.message}"))
        }
    }
}
