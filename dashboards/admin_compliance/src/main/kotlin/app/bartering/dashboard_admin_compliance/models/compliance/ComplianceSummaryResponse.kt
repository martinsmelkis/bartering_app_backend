package app.bartering.dashboard_admin_compliance.models.compliance

import kotlinx.serialization.Serializable

@Serializable
data class ComplianceSummaryResponse(
    val generatedAt: String,
    val dsarSlaDays: Int,
    val dsarTotalRequests: Int,
    val dsarCompletedWithinSla: Int,
    val dsarBreached: Int,
    val dsarOpen: Int,
    val dsarOverdueOpen: Int = 0,
    val legalHoldAppliedEvents: Int,
    val legalHoldReleasedEvents: Int,
    val dataExportRequestedEvents: Int,
    val dataExportCompletedEvents: Int,
    val accountDeletionRequestedEvents: Int,
    val accountDeletionCompletedEvents: Int,
    val retentionTaskCompletedEvents: Int,
    val retentionCycleCompletedEvents: Int,
    val retentionCoverageRequiredTables: Int = 0,
    val retentionCoverageCoveredTables: Int = 0,
    val retentionCoverageMissingTables: Int = 0,
    val retentionCoverageIncompleteTables: Int = 0,
    val ropaActiveActivities: Int = 0,
    val ropaReviewDueActivities: Int = 0,
    val erasurePendingTasks: Int = 0,
    val erasureOverdueTasks: Int = 0,
    val erasureBackupDueSoonTasks: Int = 0,
    val securityIncidentsOpen: Int = 0,
    val securityIncidentsCriticalOpen: Int = 0,
    val securityRegulatorNotificationOverdue: Int = 0,
    val securityRegulatorNotificationDueWithin24h: Int = 0,
    val securityAffectedUsersPendingNotification: Int = 0,
    val securityAffectedUsersFailedNotification: Int = 0,
    val dsarBreachedActorIds: List<String> = emptyList()
)