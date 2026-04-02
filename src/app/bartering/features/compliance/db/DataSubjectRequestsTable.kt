package app.bartering.features.compliance.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * GDPR Data Subject Request (DSR/DSAR) case tracking table.
 */
object DataSubjectRequestsTable : Table("compliance_data_subject_requests") {
    val id = long("id").autoIncrement()

    val userId = varchar("user_id", 255).index()
    val requestType = varchar("request_type", 32) // access | export | deletion | rectification | restriction | objection
    val status = varchar("status", 24).default("received") // received | in_progress | completed | rejected | cancelled

    val requestedBy = varchar("requested_by", 255).nullable()
    val handledBy = varchar("handled_by", 255).nullable()

    val requestSource = varchar("request_source", 32).default("user") // user | admin | system
    val reason = text("reason").nullable()
    val notes = text("notes").nullable()
    val rejectionReason = text("rejection_reason").nullable()

    val dueAt = timestamp("due_at")
    val completedAt = timestamp("completed_at").nullable()

    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}
