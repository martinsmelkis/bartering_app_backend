package org.barter.features.reviews.db

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import java.time.Instant

/**
 * Queue of reviews requiring manual moderation.
 * Reviews flagged as scam or with high risk scores are placed here.
 */
object ModerationQueueTable : Table("review_moderation_queue") {
    val id = varchar("id", 36)
    val reviewId = reference("review_id", ReviewsTable.id).index()
    val transactionId = reference("transaction_id", BarterTransactionsTable.id).index()
    val flagReason = varchar("flag_reason", 50) // scam, disputed, high_risk, etc.
    val riskFactors = jsonb(
        "risk_factors",
        serialize = { Json.encodeToString(it) },
        deserialize = { Json.decodeFromString<List<String>>(it) }
    ).nullable()
    val priority = varchar("priority", 50).default("medium")
    val submittedAt = timestamp("submitted_at").default(Instant.now()).index()
    val reviewerId = varchar("reviewer_id", 255)
    val targetUserId = varchar("target_user_id", 255)
    val assignedTo = varchar("assigned_to", 255).nullable() // Moderator ID
    val status = varchar("status", 50).default("pending") // pending, in_review, resolved
    val resolvedAt = timestamp("resolved_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_moderation_status", false, status, priority, submittedAt)
    }
}
