package org.barter.features.reviews.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.barter.features.authentication.dao.AuthenticationDaoImpl
import org.barter.features.authentication.utils.verifyRequestSignature
import org.barter.features.reviews.dao.BarterTransactionDao
import org.barter.features.reviews.model.*
import org.koin.java.KoinJavaComponent.inject
import java.time.Instant

/**
 * Create a new barter transaction
 */
fun Route.createTransactionRoute() {
    val transactionDao: BarterTransactionDao by inject(BarterTransactionDao::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    post("/api/v1/transactions/create") {
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            return@post
        }

        try {
            val request = Json.decodeFromString<CreateTransactionRequest>(requestBody)

            // Verify the authenticated user is one of the parties
            if (authenticatedUserId != request.user1Id && authenticatedUserId != request.user2Id) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "You must be a party to the transaction")
                )
            }

            // Create transaction
            val transactionId = transactionDao.createTransaction(
                user1Id = request.user1Id,
                user2Id = request.user2Id,
                estimatedValue = request.estimatedValue
            )

            // Calculate risk score (async, non-blocking)
            // In production, this would be done in a background job
            // For now, we'll just acknowledge the transaction was created

            call.respond(
                HttpStatusCode.Created,
                CreateTransactionResponse(
                    success = true,
                    transactionId = transactionId
                )
            )

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid request: ${e.message}")
            )
        }
    }
}

/**
 * Update transaction status
 */
fun Route.updateTransactionStatusRoute() {
    val transactionDao: BarterTransactionDao by inject(BarterTransactionDao::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    put("/api/v1/transactions/{id}/status") {
        val (authenticatedUserId, requestBody) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null || requestBody == null) {
            return@put
        }

        val transactionId = call.parameters["id"]
        if (transactionId.isNullOrBlank()) {
            return@put call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing transaction ID")
            )
        }

        try {
            val request = Json.decodeFromString<UpdateTransactionStatusRequest>(requestBody)

            // Verify the authenticated user is a party to the transaction
            val transaction = transactionDao.getTransaction(transactionId)
            if (transaction == null) {
                return@put call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Transaction not found")
                )
            }

            if (authenticatedUserId != transaction.user1Id && authenticatedUserId != transaction.user2Id) {
                return@put call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "You are not a party to this transaction")
                )
            }

            // Parse status
            val status = TransactionStatus.fromString(request.status)
            if (status == null) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid status: ${request.status}")
                )
            }

            // Update status
            val completedAt = if (status == TransactionStatus.DONE) Instant.now() else null
            val success = transactionDao.updateTransactionStatus(transactionId, status, completedAt)

            if (success) {
                call.respond(HttpStatusCode.OK, SuccessResponse(success = true))
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to update transaction")
                )
            }

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid request: ${e.message}")
            )
        }
    }
}

/**
 * Get user's transactions
 */
fun Route.getUserTransactionsRoute() {
    val transactionDao: BarterTransactionDao by inject(BarterTransactionDao::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/transactions/user/{userId}") {
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@get
        }

        val userId = call.parameters["userId"]
        if (userId.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing userId parameter")
            )
        }

        // Users can only view their own transactions
        if (authenticatedUserId != userId) {
            return@get call.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "You can only view your own transactions")
            )
        }

        try {
            val transactions = transactionDao.getUserTransactions(userId)
            val responses = transactions.map { dto ->
                TransactionResponse(
                    id = dto.id,
                    user1Id = dto.user1Id,
                    user2Id = dto.user2Id,
                    initiatedAt = dto.initiatedAt.toEpochMilli(),
                    completedAt = dto.completedAt?.toEpochMilli(),
                    status = dto.status.value,
                    estimatedValue = dto.estimatedValue,
                    locationConfirmed = dto.locationConfirmed,
                    riskScore = dto.riskScore
                )
            }
            call.respond(HttpStatusCode.OK, responses)

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to retrieve transactions")
            )
        }
    }
}

// Request/Response models moved to ApiModels.kt
