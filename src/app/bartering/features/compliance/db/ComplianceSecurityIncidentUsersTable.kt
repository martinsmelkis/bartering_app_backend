package app.bartering.features.compliance.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

object ComplianceSecurityIncidentUsersTable : Table("compliance_security_incident_users") {
    val id = long("id").autoIncrement()
    val incidentId = long("incident_id")
        .references(ComplianceSecurityIncidentsTable.id, onDelete = ReferenceOption.CASCADE)
    val userId = varchar("user_id", 255)

    val notificationStatus = varchar("notification_status", 24).default("pending")
    val notifiedAt = timestamp("notified_at").nullable()
    val lastError = text("last_error").nullable()

    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())

    init {
        uniqueIndex(incidentId, userId)
    }

    override val primaryKey = PrimaryKey(id)
}