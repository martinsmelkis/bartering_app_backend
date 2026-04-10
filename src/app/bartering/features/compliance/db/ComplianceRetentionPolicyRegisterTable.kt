package app.bartering.features.compliance.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

object ComplianceRetentionPolicyRegisterTable : Table("compliance_retention_policy_register") {
    val id = long("id").autoIncrement()
    val dataDomain = varchar("data_domain", 80)
    val dataTableName = varchar("table_name", 120).uniqueIndex()
    val processingPurpose = text("processing_purpose")
    val legalBasis = varchar("legal_basis", 120)
    val retentionPeriodDays = integer("retention_period_days")
    val deletionTrigger = varchar("deletion_trigger", 120)
    val deletionMethod = varchar("deletion_method", 120)
    val exceptionRules = text("exception_rules").nullable()
    val ownerRole = varchar("owner_role", 120).nullable()
    val enforcementJob = varchar("enforcement_job", 120).nullable()
    val isActive = bool("is_active").default(true)
    val createdBy = varchar("created_by", 255).nullable()
    val updatedBy = varchar("updated_by", 255).nullable()
    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}