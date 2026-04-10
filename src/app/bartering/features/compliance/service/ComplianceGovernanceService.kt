package app.bartering.features.compliance.service

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.compliance.db.ComplianceRetentionPolicyRegisterTable
import app.bartering.features.compliance.db.ComplianceRopaRegisterTable
import app.bartering.features.compliance.model.RetentionPolicyUpsertRequest
import app.bartering.features.compliance.model.RopaUpsertRequest
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.temporal.ChronoUnit
import java.time.Instant
import java.util.Locale

class ComplianceGovernanceService {

    private val requiredRetentionTables = listOf(
        "user_registration_data",
        "user_profiles",
        "user_privacy_consents",
        "offline_messages",
        "chat_read_receipts",
        "chat_response_times",
        "review_device_tracking",
        "review_ip_tracking",
        "user_location_changes",
        "user_reports",
        "user_postings",
        "compliance_data_subject_requests",
        "compliance_audit_log",
        "compliance_erasure_tasks"
    )

    suspend fun upsertRetentionPolicy(request: RetentionPolicyUpsertRequest, actorId: String?): Long = dbQuery {
        val now = Instant.now()

        val updated = ComplianceRetentionPolicyRegisterTable.update(
            { ComplianceRetentionPolicyRegisterTable.dataTableName eq request.tableName }
        ) {
            it[dataDomain] = request.dataDomain
            it[dataTableName] = request.tableName
            it[processingPurpose] = request.processingPurpose
            it[legalBasis] = request.legalBasis
            it[retentionPeriodDays] = request.retentionPeriodDays
            it[deletionTrigger] = request.deletionTrigger
            it[deletionMethod] = request.deletionMethod
            it[exceptionRules] = request.exceptionRules
            it[ownerRole] = request.ownerRole
            it[enforcementJob] = request.enforcementJob
            it[isActive] = request.isActive
            it[updatedBy] = actorId
            it[updatedAt] = now
        }

        if (updated > 0) {
            ComplianceRetentionPolicyRegisterTable
                .selectAll()
                .where { ComplianceRetentionPolicyRegisterTable.dataTableName eq request.tableName }
                .limit(1)
                .first()[ComplianceRetentionPolicyRegisterTable.id]
        } else {
            ComplianceRetentionPolicyRegisterTable.insert {
                it[dataDomain] = request.dataDomain
                it[dataTableName] = request.tableName
                it[processingPurpose] = request.processingPurpose
                it[legalBasis] = request.legalBasis
                it[retentionPeriodDays] = request.retentionPeriodDays
                it[deletionTrigger] = request.deletionTrigger
                it[deletionMethod] = request.deletionMethod
                it[exceptionRules] = request.exceptionRules
                it[ownerRole] = request.ownerRole
                it[enforcementJob] = request.enforcementJob
                it[isActive] = request.isActive
                it[createdBy] = actorId
                it[updatedBy] = actorId
                it[createdAt] = now
                it[updatedAt] = now
            }[ComplianceRetentionPolicyRegisterTable.id]
        }
    }

    suspend fun listRetentionPolicies(activeOnly: Boolean = false, limit: Int = 500): List<RetentionPolicyItemView> = dbQuery {
        val rows = ComplianceRetentionPolicyRegisterTable
            .selectAll()
            .orderBy(ComplianceRetentionPolicyRegisterTable.dataTableName to SortOrder.ASC)
            .limit(limit)
            .toList()

        rows.asSequence()
            .filter { !activeOnly || it[ComplianceRetentionPolicyRegisterTable.isActive] }
            .map { row ->
                RetentionPolicyItemView(
                    id = row[ComplianceRetentionPolicyRegisterTable.id],
                    dataDomain = row[ComplianceRetentionPolicyRegisterTable.dataDomain],
                    tableName = row[ComplianceRetentionPolicyRegisterTable.dataTableName],
                    processingPurpose = row[ComplianceRetentionPolicyRegisterTable.processingPurpose],
                    legalBasis = row[ComplianceRetentionPolicyRegisterTable.legalBasis],
                    retentionPeriodDays = row[ComplianceRetentionPolicyRegisterTable.retentionPeriodDays],
                    deletionTrigger = row[ComplianceRetentionPolicyRegisterTable.deletionTrigger],
                    deletionMethod = row[ComplianceRetentionPolicyRegisterTable.deletionMethod],
                    exceptionRules = row[ComplianceRetentionPolicyRegisterTable.exceptionRules],
                    ownerRole = row[ComplianceRetentionPolicyRegisterTable.ownerRole],
                    enforcementJob = row[ComplianceRetentionPolicyRegisterTable.enforcementJob],
                    isActive = row[ComplianceRetentionPolicyRegisterTable.isActive],
                    createdBy = row[ComplianceRetentionPolicyRegisterTable.createdBy],
                    updatedBy = row[ComplianceRetentionPolicyRegisterTable.updatedBy],
                    createdAt = row[ComplianceRetentionPolicyRegisterTable.createdAt],
                    updatedAt = row[ComplianceRetentionPolicyRegisterTable.updatedAt]
                )
            }
            .toList()
    }

    suspend fun upsertRopaActivity(request: RopaUpsertRequest, actorId: String?): Long = dbQuery {
        val now = Instant.now()
        val reviewDue = request.reviewDueAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val reviewedAt = request.lastReviewedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }

        val updated = ComplianceRopaRegisterTable.update(
            { ComplianceRopaRegisterTable.activityKey eq request.activityKey }
        ) {
            it[activityKey] = request.activityKey
            it[activityName] = request.activityName
            it[controllerName] = request.controllerName
            it[controllerContact] = request.controllerContact
            it[dpoContact] = request.dpoContact
            it[processingPurposes] = request.processingPurposes
            it[dataSubjectCategories] = request.dataSubjectCategories
            it[personalDataCategories] = request.personalDataCategories
            it[recipientCategories] = request.recipientCategories
            it[thirdCountryTransfers] = request.thirdCountryTransfers
            it[safeguardsDescription] = request.safeguardsDescription
            it[legalBasis] = request.legalBasis
            it[retentionSummary] = request.retentionSummary
            it[tomsSummary] = request.tomsSummary
            it[sourceSystems] = request.sourceSystems
            it[processors] = request.processors
            it[jointControllers] = request.jointControllers
            it[isActive] = request.isActive
            it[reviewDueAt] = reviewDue
            it[lastReviewedAt] = reviewedAt
            it[updatedBy] = actorId
            it[updatedAt] = now
        }

        if (updated > 0) {
            ComplianceRopaRegisterTable
                .selectAll()
                .where { ComplianceRopaRegisterTable.activityKey eq request.activityKey }
                .limit(1)
                .first()[ComplianceRopaRegisterTable.id]
        } else {
            ComplianceRopaRegisterTable.insert {
                it[activityKey] = request.activityKey
                it[activityName] = request.activityName
                it[controllerName] = request.controllerName
                it[controllerContact] = request.controllerContact
                it[dpoContact] = request.dpoContact
                it[processingPurposes] = request.processingPurposes
                it[dataSubjectCategories] = request.dataSubjectCategories
                it[personalDataCategories] = request.personalDataCategories
                it[recipientCategories] = request.recipientCategories
                it[thirdCountryTransfers] = request.thirdCountryTransfers
                it[safeguardsDescription] = request.safeguardsDescription
                it[legalBasis] = request.legalBasis
                it[retentionSummary] = request.retentionSummary
                it[tomsSummary] = request.tomsSummary
                it[sourceSystems] = request.sourceSystems
                it[processors] = request.processors
                it[jointControllers] = request.jointControllers
                it[isActive] = request.isActive
                it[reviewDueAt] = reviewDue
                it[lastReviewedAt] = reviewedAt
                it[createdBy] = actorId
                it[updatedBy] = actorId
                it[createdAt] = now
                it[updatedAt] = now
            }[ComplianceRopaRegisterTable.id]
        }
    }

    suspend fun listRopaActivities(activeOnly: Boolean = false, limit: Int = 500): List<RopaItemView> = dbQuery {
        val rows = ComplianceRopaRegisterTable
            .selectAll()
            .orderBy(ComplianceRopaRegisterTable.activityKey to SortOrder.ASC)
            .limit(limit)
            .toList()

        rows.asSequence()
            .filter { !activeOnly || it[ComplianceRopaRegisterTable.isActive] }
            .map { row ->
                RopaItemView(
                    id = row[ComplianceRopaRegisterTable.id],
                    activityKey = row[ComplianceRopaRegisterTable.activityKey],
                    activityName = row[ComplianceRopaRegisterTable.activityName],
                    controllerName = row[ComplianceRopaRegisterTable.controllerName],
                    controllerContact = row[ComplianceRopaRegisterTable.controllerContact],
                    dpoContact = row[ComplianceRopaRegisterTable.dpoContact],
                    processingPurposes = row[ComplianceRopaRegisterTable.processingPurposes],
                    dataSubjectCategories = row[ComplianceRopaRegisterTable.dataSubjectCategories],
                    personalDataCategories = row[ComplianceRopaRegisterTable.personalDataCategories],
                    recipientCategories = row[ComplianceRopaRegisterTable.recipientCategories],
                    thirdCountryTransfers = row[ComplianceRopaRegisterTable.thirdCountryTransfers],
                    safeguardsDescription = row[ComplianceRopaRegisterTable.safeguardsDescription],
                    legalBasis = row[ComplianceRopaRegisterTable.legalBasis],
                    retentionSummary = row[ComplianceRopaRegisterTable.retentionSummary],
                    tomsSummary = row[ComplianceRopaRegisterTable.tomsSummary],
                    sourceSystems = row[ComplianceRopaRegisterTable.sourceSystems],
                    processors = row[ComplianceRopaRegisterTable.processors],
                    jointControllers = row[ComplianceRopaRegisterTable.jointControllers],
                    isActive = row[ComplianceRopaRegisterTable.isActive],
                    reviewDueAt = row[ComplianceRopaRegisterTable.reviewDueAt],
                    lastReviewedAt = row[ComplianceRopaRegisterTable.lastReviewedAt],
                    createdBy = row[ComplianceRopaRegisterTable.createdBy],
                    updatedBy = row[ComplianceRopaRegisterTable.updatedBy],
                    createdAt = row[ComplianceRopaRegisterTable.createdAt],
                    updatedAt = row[ComplianceRopaRegisterTable.updatedAt]
                )
            }
            .toList()
    }

    suspend fun evaluateRetentionCoverage(): RetentionPolicyCoverageView = dbQuery {
        val rows = ComplianceRetentionPolicyRegisterTable
            .selectAll()
            .toList()

        val byTable = rows.associateBy {
            it[ComplianceRetentionPolicyRegisterTable.dataTableName].lowercase(Locale.ROOT)
        }

        val items = requiredRetentionTables.map { requiredTable ->
            val key = requiredTable.lowercase(Locale.ROOT)
            val row = byTable[key]
            val missingFields = mutableListOf<String>()

            val present = row != null
            val active = row?.get(ComplianceRetentionPolicyRegisterTable.isActive) == true
            val hasOwner = !row?.get(ComplianceRetentionPolicyRegisterTable.ownerRole).isNullOrBlank()
            val hasEnforcementJob = !row?.get(ComplianceRetentionPolicyRegisterTable.enforcementJob).isNullOrBlank()

            if (!present) {
                missingFields += listOf("policy_row", "owner_role", "enforcement_job")
            } else {
                if (!active) missingFields += "is_active"
                if (!hasOwner) missingFields += "owner_role"
                if (!hasEnforcementJob) missingFields += "enforcement_job"
            }

            RetentionPolicyCoverageItemView(
                tableName = requiredTable,
                dataDomain = row?.get(ComplianceRetentionPolicyRegisterTable.dataDomain) ?: "unknown",
                present = present,
                active = active,
                hasOwner = hasOwner,
                hasEnforcementJob = hasEnforcementJob,
                missingFields = missingFields
            )
        }

        RetentionPolicyCoverageView(
            requiredTableCount = requiredRetentionTables.size,
            coveredTableCount = items.count { it.present && it.active && it.missingFields.isEmpty() },
            missingTableCount = items.count { !it.present },
            incompleteTableCount = items.count { it.present && it.missingFields.isNotEmpty() },
            items = items
        )
    }

    suspend fun summarizeRopaReadiness(): RopaReadinessView = dbQuery {
        val now = Instant.now()
        val activeRows = ComplianceRopaRegisterTable
            .selectAll()
            .where { ComplianceRopaRegisterTable.isActive eq true }
            .toList()

        val reviewDueCount = activeRows.count {
            val dueAt = it[ComplianceRopaRegisterTable.reviewDueAt]
            dueAt != null && !dueAt.isAfter(now)
        }

        val reviewDueWithin30DaysCount = activeRows.count {
            val dueAt = it[ComplianceRopaRegisterTable.reviewDueAt]
            dueAt != null && !dueAt.isBefore(now) && !dueAt.isAfter(now.plus(30, ChronoUnit.DAYS))
        }

        RopaReadinessView(
            activeActivities = activeRows.size,
            reviewDueActivities = reviewDueCount,
            reviewDueWithin30DaysActivities = reviewDueWithin30DaysCount
        )
    }
}


data class RetentionPolicyCoverageView(
    val requiredTableCount: Int,
    val coveredTableCount: Int,
    val missingTableCount: Int,
    val incompleteTableCount: Int,
    val items: List<RetentionPolicyCoverageItemView>
)


data class RetentionPolicyCoverageItemView(
    val tableName: String,
    val dataDomain: String,
    val present: Boolean,
    val active: Boolean,
    val hasOwner: Boolean,
    val hasEnforcementJob: Boolean,
    val missingFields: List<String>
)


data class RopaReadinessView(
    val activeActivities: Int,
    val reviewDueActivities: Int,
    val reviewDueWithin30DaysActivities: Int
)


data class RetentionPolicyItemView(
    val id: Long,
    val dataDomain: String,
    val tableName: String,
    val processingPurpose: String,
    val legalBasis: String,
    val retentionPeriodDays: Int,
    val deletionTrigger: String,
    val deletionMethod: String,
    val exceptionRules: String?,
    val ownerRole: String?,
    val enforcementJob: String?,
    val isActive: Boolean,
    val createdBy: String?,
    val updatedBy: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class RopaItemView(
    val id: Long,
    val activityKey: String,
    val activityName: String,
    val controllerName: String,
    val controllerContact: String?,
    val dpoContact: String?,
    val processingPurposes: String,
    val dataSubjectCategories: String,
    val personalDataCategories: String,
    val recipientCategories: String?,
    val thirdCountryTransfers: String?,
    val safeguardsDescription: String?,
    val legalBasis: String,
    val retentionSummary: String,
    val tomsSummary: String,
    val sourceSystems: String?,
    val processors: String?,
    val jointControllers: String?,
    val isActive: Boolean,
    val reviewDueAt: Instant?,
    val lastReviewedAt: Instant?,
    val createdBy: String?,
    val updatedBy: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)
