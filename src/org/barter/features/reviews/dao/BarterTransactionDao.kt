package org.barter.features.reviews.dao

import org.barter.features.reviews.model.TransactionStatus
import java.math.BigDecimal
import java.time.Instant

/**
 * Data Access Object interface for barter transaction operations
 */
interface BarterTransactionDao {

    /**
     * Creates a new barter transaction between two users
     */
    suspend fun createTransaction(
        user1Id: String,
        user2Id: String,
        estimatedValue: BigDecimal? = null
    ): String // Returns transaction ID

    /**
     * Updates the status of a transaction
     */
    suspend fun updateTransactionStatus(
        transactionId: String,
        status: TransactionStatus,
        completedAt: Instant? = null
    ): Boolean

    /**
     * Gets a transaction by ID
     */
    suspend fun getTransaction(transactionId: String): BarterTransactionDto?

    /**
     * Gets all transactions for a user
     */
    suspend fun getUserTransactions(userId: String): List<BarterTransactionDto>

    /**
     * Gets transactions between two specific users
     */
    suspend fun getTransactionsBetweenUsers(user1Id: String, user2Id: String): List<BarterTransactionDto>

    /**
     * Gets completed transactions for a user
     */
    suspend fun getCompletedTrades(userId: String): List<CompletedTradeDto>

    /**
     * Updates risk score for a transaction
     */
    suspend fun updateRiskScore(transactionId: String, riskScore: Double): Boolean

    /**
     * Confirms location for a transaction
     */
    suspend fun confirmLocation(transactionId: String): Boolean

    /**
     * Gets all trading partners for a user
     */
    suspend fun getTradingPartners(userId: String): Set<String>
}

/**
 * DTO for barter transactions
 */
data class BarterTransactionDto(
    val id: String,
    val user1Id: String,
    val user2Id: String,
    val initiatedAt: Instant,
    val completedAt: Instant?,
    val status: TransactionStatus,
    val estimatedValue: BigDecimal?,
    val locationConfirmed: Boolean,
    val riskScore: Double?
)

/**
 * Simplified DTO for completed trades
 */
data class CompletedTradeDto(
    val transactionId: String,
    val otherUserId: String,
    val completedAt: Instant
)
