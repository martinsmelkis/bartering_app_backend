package org.barter.features.reviews.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Tracks barter transactions between users.
 * A completed transaction is a prerequisite for leaving a review.
 */
object BarterTransactionsTable : Table("barter_transactions") {
    val id = varchar("id", 36) // UUID
    val user1Id = varchar("user1_id", 255).index()
    val user2Id = varchar("user2_id", 255).index()
    val initiatedAt = timestamp("initiated_at").default(Instant.now())
    val completedAt = timestamp("completed_at").nullable()
    val status = varchar("status", 50).default("pending") // pending, done, cancelled, etc.
    val estimatedValue = decimal("estimated_value", 10, 2).nullable()
    val locationConfirmed = bool("location_confirmed").default(false)
    val riskScore = decimal("risk_score", 3, 2).nullable()

    override val primaryKey = PrimaryKey(id)
}
