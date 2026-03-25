package app.bartering.features.wallet.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.wallet.db.LedgerEntriesTable
import app.bartering.features.wallet.db.LedgerTransactionsTable
import app.bartering.features.wallet.db.WalletsTable
import app.bartering.features.wallet.model.LedgerEntry
import app.bartering.features.wallet.model.LedgerTransaction
import app.bartering.features.wallet.model.TransactionType
import app.bartering.features.wallet.model.Wallet
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

class WalletDaoImpl : WalletDao {

    override suspend fun getOrCreateWallet(userId: String): Wallet = dbQuery {
        val existing = WalletsTable
            .selectAll()
            .where { WalletsTable.userId eq userId }
            .map(::rowToWallet)
            .singleOrNull()

        if (existing != null) return@dbQuery existing

        WalletsTable.insertIgnore {
            it[WalletsTable.userId] = userId
            it[availableBalance] = 0
            it[lockedBalance] = 0
            it[totalEarned] = 0
            it[totalSpent] = 0
            it[updatedAt] = Instant.now()
        }

        WalletsTable
            .selectAll()
            .where { WalletsTable.userId eq userId }
            .map(::rowToWallet)
            .singleOrNull()
            ?: Wallet(userId, 0, 0, 0, 0, Instant.now())
    }

    override suspend fun getWallet(userId: String): Wallet? = dbQuery {
        WalletsTable
            .selectAll()
            .where { WalletsTable.userId eq userId }
            .map(::rowToWallet)
            .singleOrNull()
    }

    override suspend fun upsertWallet(wallet: Wallet): Boolean = dbQuery {
        val updated = WalletsTable.update({ WalletsTable.userId eq wallet.userId }) {
            it[availableBalance] = wallet.availableBalance
            it[lockedBalance] = wallet.lockedBalance
            it[totalEarned] = wallet.totalEarned
            it[totalSpent] = wallet.totalSpent
            it[updatedAt] = wallet.updatedAt
        }

        if (updated > 0) {
            true
        } else {
            WalletsTable.insert {
                it[userId] = wallet.userId
                it[availableBalance] = wallet.availableBalance
                it[lockedBalance] = wallet.lockedBalance
                it[totalEarned] = wallet.totalEarned
                it[totalSpent] = wallet.totalSpent
                it[updatedAt] = wallet.updatedAt
            }
            true
        }
    }

    override suspend fun createLedgerTransaction(transaction: LedgerTransaction): Boolean = dbQuery {
        LedgerTransactionsTable.insert {
            it[id] = transaction.id
            it[type] = transaction.type.value
            it[amount] = transaction.amount
            it[fromUserId] = transaction.fromUserId
            it[toUserId] = transaction.toUserId
            it[externalRef] = transaction.externalRef
            it[metadataJson] = transaction.metadataJson
            it[createdAt] = transaction.createdAt
        }
        true
    }

    override suspend fun createLedgerEntries(entries: List<LedgerEntry>): Boolean = dbQuery {
        entries.forEach { entry ->
            LedgerEntriesTable.insert {
                it[id] = entry.id
                it[transactionId] = entry.transactionId
                it[userId] = entry.userId
                it[delta] = entry.delta
                it[balanceAfter] = entry.balanceAfter
            }
        }
        true
    }

    override suspend fun getTransactionsForUser(userId: String, limit: Int, offset: Long): List<LedgerTransaction> = dbQuery {
        LedgerTransactionsTable
            .selectAll()
            .where {
                (LedgerTransactionsTable.fromUserId eq userId) or
                (LedgerTransactionsTable.toUserId eq userId)
            }
            .orderBy(LedgerTransactionsTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .offset(offset)
            .map(::rowToLedgerTransaction)
    }

    private fun rowToWallet(row: ResultRow): Wallet {
        return Wallet(
            userId = row[WalletsTable.userId],
            availableBalance = row[WalletsTable.availableBalance],
            lockedBalance = row[WalletsTable.lockedBalance],
            totalEarned = row[WalletsTable.totalEarned],
            totalSpent = row[WalletsTable.totalSpent],
            updatedAt = row[WalletsTable.updatedAt]
        )
    }

    private fun rowToLedgerTransaction(row: ResultRow): LedgerTransaction {
        return LedgerTransaction(
            id = row[LedgerTransactionsTable.id],
            type = TransactionType.fromString(row[LedgerTransactionsTable.type]) ?: TransactionType.ADJUSTMENT,
            amount = row[LedgerTransactionsTable.amount],
            fromUserId = row[LedgerTransactionsTable.fromUserId],
            toUserId = row[LedgerTransactionsTable.toUserId],
            externalRef = row[LedgerTransactionsTable.externalRef],
            metadataJson = row[LedgerTransactionsTable.metadataJson],
            createdAt = row[LedgerTransactionsTable.createdAt]
        )
    }
}
