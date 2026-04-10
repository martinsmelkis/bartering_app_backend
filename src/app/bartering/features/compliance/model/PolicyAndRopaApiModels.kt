package app.bartering.features.compliance.model

import kotlinx.serialization.Serializable

@Serializable
data class RetentionPolicyUpsertRequest(
    val dataDomain: String,
    val tableName: String,
    val processingPurpose: String,
    val legalBasis: String,
    val retentionPeriodDays: Int,
    val deletionTrigger: String,
    val deletionMethod: String,
    val exceptionRules: String? = null,
    val ownerRole: String? = null,
    val enforcementJob: String? = null,
    val isActive: Boolean = true
)

@Serializable
data class RetentionPolicyItemResponse(
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
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class RopaUpsertRequest(
    val activityKey: String,
    val activityName: String,
    val controllerName: String,
    val controllerContact: String? = null,
    val dpoContact: String? = null,
    val processingPurposes: String,
    val dataSubjectCategories: String,
    val personalDataCategories: String,
    val recipientCategories: String? = null,
    val thirdCountryTransfers: String? = null,
    val safeguardsDescription: String? = null,
    val legalBasis: String,
    val retentionSummary: String,
    val tomsSummary: String,
    val sourceSystems: String? = null,
    val processors: String? = null,
    val jointControllers: String? = null,
    val isActive: Boolean = true,
    val reviewDueAt: String? = null,
    val lastReviewedAt: String? = null
)

@Serializable
data class RopaItemResponse(
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
    val reviewDueAt: String?,
    val lastReviewedAt: String?,
    val createdBy: String?,
    val updatedBy: String?,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class RetentionPolicyCoverageItemResponse(
    val tableName: String,
    val dataDomain: String,
    val present: Boolean,
    val active: Boolean,
    val hasOwner: Boolean,
    val hasEnforcementJob: Boolean,
    val missingFields: List<String>
)

@Serializable
data class RetentionPolicyCoverageResponse(
    val requiredTableCount: Int,
    val coveredTableCount: Int,
    val missingTableCount: Int,
    val incompleteTableCount: Int,
    val items: List<RetentionPolicyCoverageItemResponse>
)
