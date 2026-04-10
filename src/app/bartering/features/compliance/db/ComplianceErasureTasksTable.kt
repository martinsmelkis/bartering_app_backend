package app.bartering.features.compliance.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

object ComplianceErasureTasksTable : Table("compliance_erasure_tasks") {
    val id = long("id").autoIncrement()
    val userId = varchar("user_id", 255).index()
    val taskType = varchar("task_type", 40)
    val status = varchar("status", 24).default("pending")

    val storageScope = varchar("storage_scope", 32)
    val targetRef = text("target_ref").nullable()

    val requestedBy = varchar("requested_by", 255).nullable()
    val handledBy = varchar("handled_by", 255).nullable()

    val dueAt = timestamp("due_at").nullable()
    val completedAt = timestamp("completed_at").nullable()
    val notes = text("notes").nullable()

    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}