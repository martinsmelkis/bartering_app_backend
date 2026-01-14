package app.bartering.features.relationships.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Table for storing user reports (abuse, harassment, spam, etc.)
 * Separate from relationships table to allow detailed tracking and moderation
 */
object UserReportsTable : Table("user_reports") {
    val id = varchar("id", 36) // UUID
    val reporterUserId = varchar("reporter_user_id", 255).index()
    val reportedUserId = varchar("reported_user_id", 255).index()
    val reportReason = varchar("report_reason", 50) // SPAM, HARASSMENT, INAPPROPRIATE_CONTENT, SCAM, FAKE_PROFILE, OTHER
    val description = text("description").nullable()
    val contextType = varchar("context_type", 50).nullable() // PROFILE, POSTING, CHAT, REVIEW
    val contextId = varchar("context_id", 36).nullable() // ID of posting, chat, review, etc.
    val status = varchar("status", 50).default("PENDING") // PENDING, REVIEWED, DISMISSED, ACTION_TAKEN
    val reportedAt = timestamp("reported_at").default(Instant.now())
    val reviewedAt = timestamp("reviewed_at").nullable()
    val reviewedBy = varchar("reviewed_by", 255).nullable() // Moderator ID
    val moderatorNotes = text("moderator_notes").nullable()
    val actionTaken = varchar("action_taken", 50).nullable() // WARNING, TEMPORARY_BAN, PERMANENT_BAN, CONTENT_REMOVED, NONE

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_reporter_reported", false, reporterUserId, reportedUserId)
        index("idx_reported_status", false, reportedUserId, status)
        index("idx_status_reported_at", false, status, reportedAt)
    }
}
