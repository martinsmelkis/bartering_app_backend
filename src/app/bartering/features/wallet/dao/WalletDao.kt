package app.bartering.features.wallet.dao

import app.bartering.features.wallet.model.LedgerEntry
import app.bartering.features.wallet.model.LedgerTransaction
import app.bartering.features.wallet.model.Wallet

interface WalletDao {
    suspend fun getOrCreateWallet(userId: String): Wallet

    suspend fun getWallet(userId: String): Wallet?

    suspend fun upsertWallet(wallet: Wallet): Boolean

    suspend fun createLedgerTransaction(transaction: LedgerTransaction): Boolean

    suspend fun createLedgerEntries(entries: List<LedgerEntry>): Boolean

    suspend fun getTransactionsForUser(userId: String, limit: Int = 50, offset: Long = 0): List<LedgerTransaction>
}
