package app.bartering.features.compliance.model

import kotlinx.serialization.Serializable

@Serializable
data class SecurityIncidentCreateRequest(
    val incidentKey: String,
    val incidentType: String,
    val severity: String,
    val summary: String,
    val detectionSource: String? = null,
    val affectedSystems: String? = null,
    val detectedAt: String? = null,
    val riskToRights: Boolean = true,
    val regulatorNotificationRequired: Boolean = true,
    val likelyConsequences: String? = null,
    val mitigationSteps: String? = null,
    val affectedUserIds: List<String> = emptyList()
)

@Serializable
data class SecurityIncidentUpdateRequest(
    val status: String,
    val containedAt: String? = null,
    val resolvedAt: String? = null,
    val regulatorNotifiedAt: String? = null,
    val mitigationSteps: String? = null,
    val likelyConsequences: String? = null
)

@Serializable
data class SecurityIncidentNotifyUsersRequest(
    val incidentId: Long,
    val userIds: List<String>? = null,
    val customTitle: String? = null,
    val customBody: String? = null
)

@Serializable
data class SecurityIncidentResponse(
    val id: Long,
    val incidentKey: String,
    val incidentType: String,
    val severity: String,
    val status: String,
    val summary: String,
    val detectionSource: String?,
    val affectedSystems: String?,
    val detectedAt: String,
    val notificationDeadlineAt: String,
    val regulatorNotificationRequired: Boolean,
    val regulatorNotifiedAt: String?,
    val riskToRights: Boolean,
    val likelyConsequences: String?,
    val mitigationSteps: String?,
    val affectedUsersTotal: Int,
    val affectedUsersPending: Int,
    val affectedUsersSent: Int,
    val affectedUsersFailed: Int,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class SecurityIncidentSummaryResponse(
    val openIncidents: Int,
    val criticalOpenIncidents: Int,
    val regulatorNotificationOverdue: Int,
    val regulatorNotificationDueWithin24h: Int,
    val affectedUsersPendingNotification: Int,
    val affectedUsersFailedNotification: Int,
    val generatedAt: String
)