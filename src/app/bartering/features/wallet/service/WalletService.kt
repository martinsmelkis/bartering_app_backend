package app.bartering.features.wallet.service

import app.bartering.features.wallet.model.LedgerTransaction
import app.bartering.features.wallet.model.TransactionType
import app.bartering.features.wallet.model.Wallet

interface WalletService {
    suspend fun getWallet(userId: String): Wallet

    suspend fun earnCoins(
        userId: String,
        amount: Long,
        transactionType: TransactionType = TransactionType.EARN,
        externalRef: String? = null,
        metadataJson: String? = null
    ): Boolean

    suspend fun transferCoins(
        fromUserId: String,
        toUserId: String,
        amount: Long,
        transactionType: TransactionType = TransactionType.TIP,
        externalRef: String? = null,
        metadataJson: String? = null
    ): Boolean

    suspend fun spendCoins(
        userId: String,
        amount: Long,
        externalRef: String? = null,
        metadataJson: String? = null
    ): Boolean

    suspend fun getTransactions(userId: String, limit: Int = 50, offset: Long = 0): List<LedgerTransaction>
}
