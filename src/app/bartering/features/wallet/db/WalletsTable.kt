package app.bartering.features.wallet.db

import app.bartering.features.profile.db.UserRegistrationDataTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * One wallet per user for Barter Coins.
 */
object WalletsTable : Table("wallets") {
    val userId = reference("user_id", UserRegistrationDataTable.id)
    val availableBalance = long("available_balance").default(0)
    val lockedBalance = long("locked_balance").default(0)
    val totalEarned = long("total_earned").default(0)
    val totalSpent = long("total_spent").default(0)
    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(userId)
}
