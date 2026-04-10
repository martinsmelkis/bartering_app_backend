package app.bartering.features.compliance.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

object ComplianceRopaRegisterTable : Table("compliance_ropa_register") {
    val id = long("id").autoIncrement()
    val activityKey = varchar("activity_key", 120).uniqueIndex()
    val activityName = varchar("activity_name", 255)
    val controllerName = varchar("controller_name", 255)
    val controllerContact = varchar("controller_contact", 255).nullable()
    val dpoContact = varchar("dpo_contact", 255).nullable()

    val processingPurposes = text("processing_purposes")
    val dataSubjectCategories = text("data_subject_categories")
    val personalDataCategories = text("personal_data_categories")
    val recipientCategories = text("recipient_categories").nullable()
    val thirdCountryTransfers = text("third_country_transfers").nullable()
    val safeguardsDescription = text("safeguards_description").nullable()

    val legalBasis = varchar("legal_basis", 255)
    val retentionSummary = text("retention_summary")
    val tomsSummary = text("toms_summary")

    val sourceSystems = text("source_systems").nullable()
    val processors = text("processors").nullable()
    val jointControllers = text("joint_controllers").nullable()

    val isActive = bool("is_active").default(true)
    val reviewDueAt = timestamp("review_due_at").nullable()
    val lastReviewedAt = timestamp("last_reviewed_at").nullable()

    val createdBy = varchar("created_by", 255).nullable()
    val updatedBy = varchar("updated_by", 255).nullable()
    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}