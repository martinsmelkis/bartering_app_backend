package app.bartering.features.reviews.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.reviews.db.BarterTransactionsTable
import app.bartering.features.reviews.model.TransactionStatus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class BarterTransactionDaoImpl : BarterTransactionDao {

    override suspend fun createTransaction(
        user1Id: String,
        user2Id: String,
        estimatedValue: BigDecimal?
    ): String = dbQuery {
        val transactionId = UUID.randomUUID().toString()
        BarterTransactionsTable.insert {
            it[id] = transactionId
            it[BarterTransactionsTable.user1Id] = user1Id
            it[BarterTransactionsTable.user2Id] = user2Id
            it[BarterTransactionsTable.estimatedValue] = estimatedValue
            it[status] = TransactionStatus.PENDING.value
        }
        transactionId
    }

    override suspend fun updateTransactionStatus(
        transactionId: String,
        status: TransactionStatus,
        completedAt: Instant?
    ): Boolean = dbQuery {
        try {
            BarterTransactionsTable.update({ BarterTransactionsTable.id eq transactionId }) {
                it[BarterTransactionsTable.status] = status.value
                if (completedAt != null) {
                    it[BarterTransactionsTable.completedAt] = completedAt
                }
            } > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun getTransaction(transactionId: String): BarterTransactionDto? = dbQuery {
        BarterTransactionsTable
            .selectAll()
            .where { BarterTransactionsTable.id eq transactionId }
            .map { rowToDto(it) }
            .singleOrNull()
    }

    override suspend fun getUserTransactions(userId: String): List<BarterTransactionDto> = dbQuery {
        BarterTransactionsTable
            .selectAll()
            .where {
                (BarterTransactionsTable.user1Id eq userId) or
                (BarterTransactionsTable.user2Id eq userId)
            }
            .orderBy(BarterTransactionsTable.initiatedAt, SortOrder.DESC)
            .map { rowToDto(it) }
    }

    override suspend fun getTransactionsBetweenUsers(user1Id: String, user2Id: String): List<BarterTransactionDto> = dbQuery {
        BarterTransactionsTable
            .selectAll()
            .where {
                ((BarterTransactionsTable.user1Id eq user1Id) and (BarterTransactionsTable.user2Id eq user2Id)) or
                ((BarterTransactionsTable.user1Id eq user2Id) and (BarterTransactionsTable.user2Id eq user1Id))
            }
            .orderBy(BarterTransactionsTable.initiatedAt, SortOrder.DESC)
            .map { rowToDto(it) }
    }

    override suspend fun getCompletedTrades(userId: String): List<CompletedTradeDto> = dbQuery {
        BarterTransactionsTable
            .selectAll()
            .where {
                ((BarterTransactionsTable.user1Id eq userId) or (BarterTransactionsTable.user2Id eq userId)) and
                (BarterTransactionsTable.status eq TransactionStatus.DONE.value)
            }
            .map { row ->
                CompletedTradeDto(
                    transactionId = row[BarterTransactionsTable.id],
                    otherUserId = if (row[BarterTransactionsTable.user1Id] == userId) 
                        row[BarterTransactionsTable.user2Id] 
                    else 
                        row[BarterTransactionsTable.user1Id],
                    initiatedAt = row[BarterTransactionsTable.initiatedAt],
                    completedAt = row[BarterTransactionsTable.completedAt] ?: row[BarterTransactionsTable.initiatedAt]
                )
            }
    }

    override suspend fun updateRiskScore(transactionId: String, riskScore: Double): Boolean = dbQuery {
        try {
            BarterTransactionsTable.update({ BarterTransactionsTable.id eq transactionId }) {
                it[BarterTransactionsTable.riskScore] = riskScore.toBigDecimal()
            } > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun confirmLocation(transactionId: String): Boolean = dbQuery {
        try {
            BarterTransactionsTable.update({ BarterTransactionsTable.id eq transactionId }) {
                it[locationConfirmed] = true
            } > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun getTradingPartners(userId: String): Set<String> = dbQuery {
        val partners = mutableSetOf<String>()
        
        BarterTransactionsTable
            .selectAll()
            .where {
                ((BarterTransactionsTable.user1Id eq userId) or (BarterTransactionsTable.user2Id eq userId)) and
                (BarterTransactionsTable.status eq TransactionStatus.DONE.value)
            }
            .forEach { row ->
                val otherUser = if (row[BarterTransactionsTable.user1Id] == userId) 
                    row[BarterTransactionsTable.user2Id] 
                else 
                    row[BarterTransactionsTable.user1Id]
                partners.add(otherUser)
            }
        
        partners
    }

    override suspend fun getAverageTradeCompletionTime(userId: String): Long? = dbQuery {
        val completedTrades = BarterTransactionsTable
            .selectAll()
            .where {
                ((BarterTransactionsTable.user1Id eq userId) or (BarterTransactionsTable.user2Id eq userId)) and
                (BarterTransactionsTable.status eq TransactionStatus.DONE.value) and
                (BarterTransactionsTable.completedAt.isNotNull())
            }
            .mapNotNull { row ->
                val initiatedAt = row[BarterTransactionsTable.initiatedAt]
                val completedAt = row[BarterTransactionsTable.completedAt]
                
                if (completedAt != null) {
                    val durationMillis = completedAt.toEpochMilli() - initiatedAt.toEpochMilli()
                    val durationHours = durationMillis / (1000 * 60 * 60) // Convert to hours
                    durationHours
                } else {
                    null
                }
            }
        
        if (completedTrades.isEmpty()) {
            null
        } else {
            completedTrades.average().toLong()
        }
    }

    private fun rowToDto(row: ResultRow): BarterTransactionDto {
        return BarterTransactionDto(
            id = row[BarterTransactionsTable.id],
            user1Id = row[BarterTransactionsTable.user1Id],
            user2Id = row[BarterTransactionsTable.user2Id],
            initiatedAt = row[BarterTransactionsTable.initiatedAt],
            completedAt = row[BarterTransactionsTable.completedAt],
            status = TransactionStatus.fromString(row[BarterTransactionsTable.status]) ?: TransactionStatus.PENDING,
            estimatedValue = row[BarterTransactionsTable.estimatedValue],
            locationConfirmed = row[BarterTransactionsTable.locationConfirmed],
            riskScore = row[BarterTransactionsTable.riskScore]?.toDouble()
        )
    }
}
