package app.bartering.features.wallet.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * Configurable operational limits for abuse prevention.
 */
object CoinLimitsTable : Table("wallet_coin_limits") {
    val id = varchar("id", 100)
    val maxDailyTransfer = long("max_daily_transfer").nullable()
    val maxSingleTransfer = long("max_single_transfer").nullable()
    val maxDailyEarn = long("max_daily_earn").nullable()
    val cooldownSeconds = integer("cooldown_seconds").nullable()
    val isEnabled = bool("is_enabled").default(true)
    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}
