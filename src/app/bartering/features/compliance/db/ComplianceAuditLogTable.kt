package app.bartering.features.compliance.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * Cross-domain GDPR/compliance audit trail.
 * Append-only by convention: only INSERT operations should be used.
 */
object ComplianceAuditLogTable : Table("compliance_audit_log") {
    val id = long("id").autoIncrement()

    val actorType = varchar("actor_type", 20) // user | admin | system
    val actorId = varchar("actor_id", 255).nullable()

    val eventType = varchar("event_type", 80)
    val entityType = varchar("entity_type", 80).nullable()
    val entityId = varchar("entity_id", 255).nullable()

    val purpose = varchar("purpose", 255).nullable()
    val outcome = varchar("outcome", 20) // success | denied | error

    val requestId = varchar("request_id", 128).nullable()
    val ipHash = varchar("ip_hash", 128).nullable()
    val deviceIdHash = varchar("device_id_hash", 128).nullable()

    val detailsJson = text("details_json").nullable()
    val dsrRequestId = long("dsr_request_id").nullable()
    val createdAt = timestamp("created_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}
