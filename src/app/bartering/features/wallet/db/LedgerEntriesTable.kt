package app.bartering.features.wallet.db

import app.bartering.features.profile.db.UserRegistrationDataTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Double-entry-like postings per affected wallet.
 */
object LedgerEntriesTable : Table("wallet_ledger_entries") {
    val id = varchar("id", 36)
    val transactionId = reference("transaction_id", LedgerTransactionsTable.id, onDelete = ReferenceOption.CASCADE).index()
    val userId = reference("user_id", UserRegistrationDataTable.id).index()
    val delta = long("delta")
    val balanceAfter = long("balance_after")

    override val primaryKey = PrimaryKey(id)
}
