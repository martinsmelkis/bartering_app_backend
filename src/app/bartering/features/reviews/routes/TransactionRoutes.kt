package app.bartering.features.reviews.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.utils.verifyRequestSignature
import app.bartering.features.reviews.dao.BarterTransactionDao
import app.bartering.features.reviews.model.*
import org.koin.java.KoinJavaComponent.inject
import org.slf4j.LoggerFactory
import java.time.Instant

private val log = LoggerFactory.getLogger("app.bartering.features.reviews.routes.TransactionRoutes")

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
            log.error("Error creating transaction", e)
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
            val transaction =
                transactionDao.getTransaction(transactionId) ?: return@put call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Transaction not found")
                )

            if (authenticatedUserId != transaction.user1Id && authenticatedUserId != transaction.user2Id) {
                return@put call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "You are not a party to this transaction")
                )
            }

            // Parse status
            val newStatus = TransactionStatus.fromString(request.status) ?: return@put call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid status: ${request.status}")
            )

            // Validate state transitions - prevent invalid status changes
            val currentStatus = transaction.status
            if (!isValidStatusTransition(currentStatus, newStatus)) {
                log.warn("Invalid status transition attempt for transaction {}: {} -> {} by user {}", 
                    transactionId, currentStatus.value, newStatus.value, authenticatedUserId)
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(
                        "error" to "Invalid status transition: Cannot change from ${currentStatus.value} to ${newStatus.value}",
                        "currentStatus" to currentStatus.value,
                        "attemptedStatus" to newStatus.value
                    )
                )
            }

            // Prevent updating completedAt if already set (preserve original completion time)
            val completedAt = if (newStatus == TransactionStatus.DONE && transaction.completedAt == null) {
                Instant.now()
            } else {
                null // Don't update if already completed
            }

            // Update status
            val success = transactionDao.updateTransactionStatus(transactionId, newStatus, completedAt)

            if (success) {
                log.info("Transaction {} status updated from {} to {} by user {}", 
                    transactionId, currentStatus.value, newStatus.value, authenticatedUserId)
                call.respond(HttpStatusCode.OK, SuccessResponse(success = true))
            } else {
                log.error("Failed to update transaction {} status to {}", transactionId, newStatus.value)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to update transaction")
                )
            }

        } catch (e: Exception) {
            log.error("Error updating transaction status", e)
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid request: ${e.message}")
            )
        }
    }
}

/**
 * Validates whether a status transition is allowed.
 * Prevents invalid state changes that could corrupt data or analytics.
 */
private fun isValidStatusTransition(currentStatus: TransactionStatus, newStatus: TransactionStatus): Boolean {
    // Same status is idempotent - allowed but should be logged
    if (currentStatus == newStatus) {
        return true // Allow, but caller should log this
    }

    // Define valid transitions based on business rules
    return when (currentStatus) {
        TransactionStatus.PENDING -> {
            // From PENDING, can go to any status (user decides outcome)
            true
        }
        TransactionStatus.DONE -> {
            // Once DONE, cannot be changed (immutable completion)
            // This prevents data corruption and preserves audit trail
            false
        }
        TransactionStatus.CANCELLED -> {
            // Cancelled transactions cannot be revived or changed
            false
        }
        TransactionStatus.EXPIRED -> {
            // Expired transactions cannot be changed
            false
        }
        TransactionStatus.NO_DEAL -> {
            // No deal is final
            false
        }
        TransactionStatus.SCAM -> {
            // SCAM status is serious and should only be changed by moderation
            // In this API, regular users cannot change it
            false
        }
        TransactionStatus.DISPUTED -> {
            // Disputed transactions should only be resolved by moderation/admin
            // For now, prevent user changes
            false
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
            log.error("Error retrieving user transactions for userId={}", userId, e)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to retrieve transactions")
            )
        }
    }
}

/**
 * Get active transaction with a chat partner
 * Returns the most recent transaction (especially pending/active ones) between two users
 * Useful for chat interface to show transaction status and allow marking as done
 */
fun Route.getTransactionWithPartnerRoute() {
    val transactionDao: BarterTransactionDao by inject(BarterTransactionDao::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/transactions/with/{partnerId}") {
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) {
            return@get
        }

        val partnerId = call.parameters["partnerId"]
        if (partnerId.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing partnerId parameter")
            )
        }

        try {
            val transactions = transactionDao.getTransactionsBetweenUsers(authenticatedUserId, partnerId)
            
            if (transactions.isEmpty()) {
                // No transaction exists yet
                return@get call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "hasTransaction" to false,
                        "message" to "No transaction exists with this user yet"
                    )
                )
            }
            
            // Return the most recent transaction (ordered by initiatedAt DESC)
            val mostRecent = transactions.first()
            
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "hasTransaction" to true,
                    "transactionId" to mostRecent.id,
                    "status" to mostRecent.status.value,
                    "initiatedAt" to mostRecent.initiatedAt.toEpochMilli(),
                    "completedAt" to mostRecent.completedAt?.toEpochMilli(),
                    "estimatedValue" to mostRecent.estimatedValue,
                    "user1Id" to mostRecent.user1Id,
                    "user2Id" to mostRecent.user2Id
                )
            )
        } catch (e: Exception) {
            log.error("Error retrieving transaction with partnerId={}", partnerId, e)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to retrieve transaction: ${e.message}")
            )
        }
    }
}

// Request/Response models moved to ApiModels.kt
