package app.bartering.features.wallet.db

import app.bartering.features.profile.db.UserRegistrationDataTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * Immutable ledger header rows.
 */
object LedgerTransactionsTable : Table("wallet_ledger_transactions") {
    val id = varchar("id", 36)
    val type = varchar("type", 50).index()
    val amount = long("amount")
    val fromUserId = reference("from_user_id", UserRegistrationDataTable.id).nullable().index()
    val toUserId = reference("to_user_id", UserRegistrationDataTable.id).nullable().index()
    val externalRef = varchar("external_ref", 255).nullable().index()
    val metadataJson = text("metadata_json").nullable()
    val createdAt = timestamp("created_at").default(Instant.now()).index()

    override val primaryKey = PrimaryKey(id)
}
