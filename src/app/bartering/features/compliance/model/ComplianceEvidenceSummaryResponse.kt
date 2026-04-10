package app.bartering.features.compliance.model

import kotlinx.serialization.Serializable

// DSAR metrics summarize GDPR request handling timelines (export/deletion requests).
// SLA fields indicate whether completion stayed within configured DSAR deadline.
@Serializable
data class ComplianceEvidenceSummaryResponse(
    val generatedAt: String,
    val dsarSlaDays: Int,
    val dsarTotalRequests: Int,
    val dsarCompletedWithinSla: Int,
    val dsarBreached: Int,
    val dsarOpen: Int,
    val dsarOverdueOpen: Int,
    val dsarBreachedActorIds: List<String>,
    val legalHoldAppliedEvents: Int,
    val legalHoldReleasedEvents: Int,
    val dataExportRequestedEvents: Int,
    val dataExportCompletedEvents: Int,
    val accountDeletionRequestedEvents: Int,
    val accountDeletionCompletedEvents: Int,
    val retentionTaskCompletedEvents: Int,
    val retentionCycleCompletedEvents: Int,
    val retentionCoverageRequiredTables: Int,
    val retentionCoverageCoveredTables: Int,
    val retentionCoverageMissingTables: Int,
    val retentionCoverageIncompleteTables: Int,
    val ropaActiveActivities: Int,
    val ropaReviewDueActivities: Int,
    val erasurePendingTasks: Int,
    val erasureOverdueTasks: Int,
    val erasureBackupDueSoonTasks: Int,
    val securityIncidentsOpen: Int,
    val securityIncidentsCriticalOpen: Int,
    val securityRegulatorNotificationOverdue: Int,
    val securityRegulatorNotificationDueWithin24h: Int,
    val securityAffectedUsersPendingNotification: Int,
    val securityAffectedUsersFailedNotification: Int
)