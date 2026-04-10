package app.bartering.features.compliance.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

object ComplianceSecurityIncidentsTable : Table("compliance_security_incidents") {
    val id = long("id").autoIncrement()
    val incidentKey = varchar("incident_key", 80).uniqueIndex()
    val incidentType = varchar("incident_type", 64)
    val severity = varchar("severity", 16)
    val status = varchar("status", 24).default("detected")

    val summary = text("summary")
    val detectionSource = varchar("detection_source", 120).nullable()
    val affectedSystems = text("affected_systems").nullable()

    val detectedAt = timestamp("detected_at")
    val containedAt = timestamp("contained_at").nullable()
    val resolvedAt = timestamp("resolved_at").nullable()

    val riskToRights = bool("risk_to_rights").default(true)
    val regulatorNotificationRequired = bool("regulator_notification_required").default(true)
    val regulatorNotifiedAt = timestamp("regulator_notified_at").nullable()
    val notificationDeadlineAt = timestamp("notification_deadline_at")

    val likelyConsequences = text("likely_consequences").nullable()
    val mitigationSteps = text("mitigation_steps").nullable()

    val createdBy = varchar("created_by", 255).nullable()
    val updatedBy = varchar("updated_by", 255).nullable()
    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}