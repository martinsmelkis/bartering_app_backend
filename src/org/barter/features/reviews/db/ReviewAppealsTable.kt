package org.barter.features.reviews.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Review appeals/disputes for moderation.
 */
object ReviewAppealsTable : Table("review_appeals") {
    val id = varchar("id", 36)
    val reviewId = reference("review_id", ReviewsTable.id).index()
    val appealedBy = varchar("appealed_by", 255).index()
    val reason = text("reason")
    val status = varchar("status", 50).default("pending")
    val appealedAt = timestamp("appealed_at").default(Instant.now())
    val resolvedAt = timestamp("resolved_at").nullable()
    val moderatorId = varchar("moderator_id", 255).nullable()
    val moderatorNotes = text("moderator_notes").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_appeal_status", false, status, appealedAt)
    }
}
