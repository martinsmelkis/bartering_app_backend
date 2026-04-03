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
    val legalHoldAppliedEvents: Int,
    val legalHoldReleasedEvents: Int,
    val dataExportRequestedEvents: Int,
    val dataExportCompletedEvents: Int,
    val accountDeletionRequestedEvents: Int,
    val accountDeletionCompletedEvents: Int,
    val retentionTaskCompletedEvents: Int,
    val retentionCycleCompletedEvents: Int,
    val dsarBreachedActorIds: List<String> = emptyList()
)