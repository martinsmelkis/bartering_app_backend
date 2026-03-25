package app.bartering.features.wallet.service

import app.bartering.features.wallet.dao.WalletDao
import app.bartering.features.wallet.model.LedgerEntry
import app.bartering.features.wallet.model.LedgerTransaction
import app.bartering.features.wallet.model.TransactionType
import app.bartering.features.wallet.model.Wallet
import java.time.Instant
import java.util.UUID

class WalletServiceImpl(
    private val walletDao: WalletDao
) : WalletService {

    override suspend fun getWallet(userId: String): Wallet {
        return walletDao.getOrCreateWallet(userId)
    }

    override suspend fun earnCoins(
        userId: String,
        amount: Long,
        transactionType: TransactionType,
        externalRef: String?,
        metadataJson: String?
    ): Boolean {
        if (amount <= 0) return false

        val wallet = walletDao.getOrCreateWallet(userId)
        val updatedWallet = wallet.copy(
            availableBalance = wallet.availableBalance + amount,
            totalEarned = wallet.totalEarned + amount,
            updatedAt = Instant.now()
        )

        val transactionId = UUID.randomUUID().toString()
        val transaction = LedgerTransaction(
            id = transactionId,
            type = transactionType,
            amount = amount,
            fromUserId = null,
            toUserId = userId,
            externalRef = externalRef,
            metadataJson = metadataJson,
            createdAt = Instant.now()
        )

        val entry = LedgerEntry(
            id = UUID.randomUUID().toString(),
            transactionId = transactionId,
            userId = userId,
            delta = amount,
            balanceAfter = updatedWallet.availableBalance
        )

        if (!walletDao.createLedgerTransaction(transaction)) return false
        if (!walletDao.createLedgerEntries(listOf(entry))) return false
        return walletDao.upsertWallet(updatedWallet)
    }

    override suspend fun transferCoins(
        fromUserId: String,
        toUserId: String,
        amount: Long,
        transactionType: TransactionType,
        externalRef: String?,
        metadataJson: String?
    ): Boolean {
        if (amount <= 0 || fromUserId == toUserId) return false

        val fromWallet = walletDao.getOrCreateWallet(fromUserId)
        if (fromWallet.availableBalance < amount) return false
        val toWallet = walletDao.getOrCreateWallet(toUserId)

        val updatedFromWallet = fromWallet.copy(
            availableBalance = fromWallet.availableBalance - amount,
            totalSpent = fromWallet.totalSpent + amount,
            updatedAt = Instant.now()
        )

        val updatedToWallet = toWallet.copy(
            availableBalance = toWallet.availableBalance + amount,
            totalEarned = toWallet.totalEarned + amount,
            updatedAt = Instant.now()
        )

        val transactionId = UUID.randomUUID().toString()
        val transaction = LedgerTransaction(
            id = transactionId,
            type = transactionType,
            amount = amount,
            fromUserId = fromUserId,
            toUserId = toUserId,
            externalRef = externalRef,
            metadataJson = metadataJson,
            createdAt = Instant.now()
        )

        val fromEntry = LedgerEntry(
            id = UUID.randomUUID().toString(),
            transactionId = transactionId,
            userId = fromUserId,
            delta = -amount,
            balanceAfter = updatedFromWallet.availableBalance
        )

        val toEntry = LedgerEntry(
            id = UUID.randomUUID().toString(),
            transactionId = transactionId,
            userId = toUserId,
            delta = amount,
            balanceAfter = updatedToWallet.availableBalance
        )

        if (!walletDao.createLedgerTransaction(transaction)) return false
        if (!walletDao.createLedgerEntries(listOf(fromEntry, toEntry))) return false
        if (!walletDao.upsertWallet(updatedFromWallet)) return false
        return walletDao.upsertWallet(updatedToWallet)
    }

    override suspend fun spendCoins(
        userId: String,
        amount: Long,
        externalRef: String?,
        metadataJson: String?
    ): Boolean {
        if (amount <= 0) return false

        val wallet = walletDao.getOrCreateWallet(userId)
        if (wallet.availableBalance < amount) return false

        val updatedWallet = wallet.copy(
            availableBalance = wallet.availableBalance - amount,
            totalSpent = wallet.totalSpent + amount,
            updatedAt = Instant.now()
        )

        val transactionId = UUID.randomUUID().toString()
        val transaction = LedgerTransaction(
            id = transactionId,
            type = TransactionType.SPEND,
            amount = amount,
            fromUserId = userId,
            toUserId = null,
            externalRef = externalRef,
            metadataJson = metadataJson,
            createdAt = Instant.now()
        )

        val entry = LedgerEntry(
            id = UUID.randomUUID().toString(),
            transactionId = transactionId,
            userId = userId,
            delta = -amount,
            balanceAfter = updatedWallet.availableBalance
        )

        if (!walletDao.createLedgerTransaction(transaction)) return false
        if (!walletDao.createLedgerEntries(listOf(entry))) return false
        return walletDao.upsertWallet(updatedWallet)
    }

    override suspend fun getTransactions(userId: String, limit: Int, offset: Long): List<LedgerTransaction> {
        return walletDao.getTransactionsForUser(userId, limit, offset)
    }
}
