package app.bartering.features.compliance.service

import app.bartering.config.RetentionConfig
import app.bartering.features.chat.dao.ChatAnalyticsDao
import app.bartering.features.chat.dao.OfflineMessageDao
import app.bartering.features.chat.dao.ReadReceiptDao
import app.bartering.features.encryptedfiles.dao.EncryptedFileDao
import app.bartering.features.migration.dao.MigrationDao
import app.bartering.features.postings.dao.UserPostingDao
import app.bartering.features.reviews.dao.RiskPatternDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Unified retention execution job.
 * Coordinates cleanup tasks and records structured compliance audit events.
 */
class RetentionOrchestrator(
    private val offlineMessageDao: OfflineMessageDao,
    private val chatAnalyticsDao: ChatAnalyticsDao,
    private val readReceiptDao: ReadReceiptDao,
    private val encryptedFileDao: EncryptedFileDao,
    private val migrationDao: MigrationDao,
    private val userPostingDao: UserPostingDao,
    private val riskPatternDao: RiskPatternDao,
    private val legalHoldService: LegalHoldService,
    private val complianceAuditService: ComplianceAuditService,
    private val dsrService: DataSubjectRequestService
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    fun start(scope: CoroutineScope) {
        scope.launch {
            log.info(
                "RetentionOrchestrator started (interval={}h)",
                RetentionConfig.retentionOrchestratorIntervalHours
            )

            while (isActive) {
                val cycleStartedAt = System.currentTimeMillis()
                var totalDeleted = 0
                var failed = false

                if (RetentionConfig.backupPolicyEnforcement && RetentionConfig.backupRetentionDays <= 0) {
                    complianceAuditService.logEvent(
                        actorType = "system",
                        eventType = "BACKUP_RETENTION_POLICY_INVALID",
                        entityType = "backup_policy",
                        outcome = "error",
                        purpose = "gdpr_data_retention",
                        details = mapOf("backupRetentionDays" to RetentionConfig.backupRetentionDays.toString())
                    )
                }

                complianceAuditService.logEvent(
                    actorType = "system",
                    eventType = "RETENTION_PURGE_CYCLE_STARTED",
                    entityType = "retention",
                    outcome = "success",
                    purpose = "gdpr_data_retention",
                    details = mapOf("startedAt" to Instant.now().toString())
                )

                try {
                    val heldUserIds = legalHoldService.getActiveHeldUserIds()

                    val deletedMessages = offlineMessageDao.deleteDeliveredMessages(
                        olderThanDays = RetentionConfig.chatDeliveredMessagesDays,
                        excludedUserIds = heldUserIds
                    )
                    totalDeleted += deletedMessages
                    complianceAuditService.logEvent(
                        actorType = "system",
                        eventType = "RETENTION_PURGE_TASK_COMPLETED",
                        entityType = "offline_messages",
                        outcome = "success",
                        purpose = "gdpr_data_retention",
                        details = mapOf(
                            "task" to "chat_delivered_messages",
                            "deleted" to deletedMessages.toString(),
                            "skippedLegalHolds" to heldUserIds.size.toString()
                        )
                    )

                    val deletedAnalytics = chatAnalyticsDao.deleteOldResponseTimes(
                        olderThanDays = RetentionConfig.chatAnalyticsDays,
                        excludedUserIds = heldUserIds
                    )
                    totalDeleted += deletedAnalytics
                    complianceAuditService.logEvent(
                        actorType = "system",
                        eventType = "RETENTION_PURGE_TASK_COMPLETED",
                        entityType = "chat_response_times",
                        outcome = "success",
                        purpose = "gdpr_data_retention",
                        details = mapOf(
                            "task" to "chat_analytics",
                            "deleted" to deletedAnalytics.toString(),
                            "skippedLegalHolds" to heldUserIds.size.toString()
                        )
                    )

                    val deletedReceipts = readReceiptDao.deleteOldReceipts(
                        olderThan = Instant.now().minus(RetentionConfig.readReceiptsDays.toLong(), ChronoUnit.DAYS),
                        excludedUserIds = heldUserIds
                    )
                    totalDeleted += deletedReceipts
                    complianceAuditService.logEvent(
                        actorType = "system",
                        eventType = "RETENTION_PURGE_TASK_COMPLETED",
                        entityType = "chat_read_receipts",
                        outcome = "success",
                        purpose = "gdpr_data_retention",
                        details = mapOf(
                            "task" to "read_receipts",
                            "deleted" to deletedReceipts.toString(),
                            "skippedLegalHolds" to heldUserIds.size.toString()
                        )
                    )

                    val deletedFiles = encryptedFileDao.deleteExpiredFiles(excludedUserIds = heldUserIds)
                    totalDeleted += deletedFiles
                    complianceAuditService.logEvent(
                        actorType = "system",
                        eventType = "RETENTION_PURGE_TASK_COMPLETED",
                        entityType = "encrypted_files",
                        outcome = "success",
                        purpose = "gdpr_data_retention",
                        details = mapOf(
                            "task" to "encrypted_files",
                            "deleted" to deletedFiles.toString(),
                            "skippedLegalHolds" to heldUserIds.size.toString()
                        )
                    )

                    val deletedMigration = migrationDao.cleanupExpiredSessions(excludedUserIds = heldUserIds)
                    totalDeleted += deletedMigration
                    complianceAuditService.logEvent(
                        actorType = "system",
                        eventType = "RETENTION_PURGE_TASK_COMPLETED",
                        entityType = "device_migration_sessions",
                        outcome = "success",
                        purpose = "gdpr_data_retention",
                        details = mapOf(
                            "task" to "migration_sessions",
                            "deleted" to deletedMigration.toString(),
                            "skippedLegalHolds" to heldUserIds.size.toString()
                        )
                    )

                    // Posting lifecycle retention tasks
                    val expiredPostings = userPostingDao.markExpiredPostings(excludedUserIds = heldUserIds)
                    totalDeleted += expiredPostings

                    val hardDeletedPostings = userPostingDao.hardDeleteExpiredPostings(
                        gracePeriodDays = RetentionConfig.postingHardDeleteGraceDays,
                        excludedUserIds = heldUserIds
                    )
                    totalDeleted += hardDeletedPostings
                    complianceAuditService.logEvent(
                        actorType = "system",
                        eventType = "RETENTION_PURGE_TASK_COMPLETED",
                        entityType = "user_postings",
                        outcome = "success",
                        purpose = "gdpr_data_retention",
                        details = mapOf(
                            "task" to "postings_lifecycle",
                            "expiredMarked" to expiredPostings.toString(),
                            "hardDeleted" to hardDeletedPostings.toString(),
                            "skippedLegalHolds" to heldUserIds.size.toString()
                        )
                    )

                    val deletedDevices = riskPatternDao.cleanupOldDeviceTracking(
                        olderThanDays = RetentionConfig.riskDeviceTrackingDays,
                        excludedUserIds = heldUserIds
                    )
                    totalDeleted += deletedDevices

                    val deletedIps = riskPatternDao.cleanupOldIpTracking(
                        olderThanDays = RetentionConfig.riskIpTrackingDays,
                        excludedUserIds = heldUserIds
                    )
                    totalDeleted += deletedIps

                    val deletedLocation = riskPatternDao.cleanupOldLocationChanges(
                        olderThanDays = RetentionConfig.riskLocationChangesDays,
                        excludedUserIds = heldUserIds
                    )
                    totalDeleted += deletedLocation

                    val deletedRiskPatterns = riskPatternDao.cleanupOldRiskPatterns(
                        olderThanDays = RetentionConfig.riskPatternsDays,
                        excludedUserIds = heldUserIds
                    )
                    totalDeleted += deletedRiskPatterns
                    complianceAuditService.logEvent(
                        actorType = "system",
                        eventType = "RETENTION_PURGE_TASK_COMPLETED",
                        entityType = "review_risk",
                        outcome = "success",
                        purpose = "gdpr_data_retention",
                        details = mapOf(
                            "task" to "risk_tracking",
                            "deletedDevices" to deletedDevices.toString(),
                            "deletedIps" to deletedIps.toString(),
                            "deletedLocationChanges" to deletedLocation.toString(),
                            "deletedRiskPatterns" to deletedRiskPatterns.toString(),
                            "skippedLegalHolds" to heldUserIds.size.toString()
                        )
                    )

                    val protectedComplianceEvents = setOf(
                        "ACCOUNT_DELETION_REQUESTED",
                        "ACCOUNT_DELETION_COMPLETED",
                        "DATA_EXPORT_REQUESTED",
                        "DATA_EXPORT_COMPLETED",
                        "LEGAL_HOLD_APPLIED",
                        "LEGAL_HOLD_RELEASED",
                        "CONSENT_UPDATED"
                    )

                    val deletedComplianceAudit = complianceAuditService.cleanupOldOperationalEvents(
                        retentionDays = RetentionConfig.complianceAuditRetentionDays,
                        excludedEventTypes = protectedComplianceEvents
                    )
                    totalDeleted += deletedComplianceAudit

                    complianceAuditService.logEvent(
                        actorType = "system",
                        eventType = "RETENTION_PURGE_TASK_COMPLETED",
                        entityType = "compliance_audit_log",
                        outcome = "success",
                        purpose = "gdpr_data_retention",
                        details = mapOf(
                            "task" to "compliance_audit_cleanup",
                            "deleted" to deletedComplianceAudit.toString(),
                            "retentionDays" to RetentionConfig.complianceAuditRetentionDays.toString()
                        )
                    )

                    val deletedClosedDsar = dsrService.cleanupOldClosedRequests(
                        retentionDays = RetentionConfig.dsarRequestRetentionDays
                    )
                    totalDeleted += deletedClosedDsar

                    complianceAuditService.logEvent(
                        actorType = "system",
                        eventType = "RETENTION_PURGE_TASK_COMPLETED",
                        entityType = "compliance_data_subject_requests",
                        outcome = "success",
                        purpose = "gdpr_data_retention",
                        details = mapOf(
                            "task" to "dsar_closed_requests_cleanup",
                            "deleted" to deletedClosedDsar.toString(),
                            "retentionDays" to RetentionConfig.dsarRequestRetentionDays.toString()
                        )
                    )

                    val durationMs = System.currentTimeMillis() - cycleStartedAt
                    log.info("Retention cycle completed: totalDeleted={}, durationMs={}", totalDeleted, durationMs)

                    complianceAuditService.logEvent(
                        actorType = "system",
                        eventType = "RETENTION_PURGE_CYCLE_COMPLETED",
                        entityType = "retention",
                        outcome = "success",
                        purpose = "gdpr_data_retention",
                        details = mapOf(
                            "totalDeleted" to totalDeleted.toString(),
                            "durationMs" to durationMs.toString(),
                            "heldUserCount" to heldUserIds.size.toString(),
                            "deletedMessages" to deletedMessages.toString(),
                            "deletedAnalytics" to deletedAnalytics.toString(),
                            "deletedReceipts" to deletedReceipts.toString(),
                            "deletedFiles" to deletedFiles.toString(),
                            "deletedMigration" to deletedMigration.toString(),
                            "expiredPostings" to expiredPostings.toString(),
                            "hardDeletedPostings" to hardDeletedPostings.toString(),
                            "deletedDevices" to deletedDevices.toString(),
                            "deletedIps" to deletedIps.toString(),
                            "deletedLocationChanges" to deletedLocation.toString(),
                            "deletedRiskPatterns" to deletedRiskPatterns.toString(),
                            "deletedComplianceAudit" to deletedComplianceAudit.toString(),
                            "deletedClosedDsar" to deletedClosedDsar.toString()
                        )
                    )
                } catch (e: Exception) {
                    failed = true
                    log.error("Retention cycle failed", e)
                    complianceAuditService.logEvent(
                        actorType = "system",
                        eventType = "RETENTION_PURGE_CYCLE_COMPLETED",
                        entityType = "retention",
                        outcome = "error",
                        purpose = "gdpr_data_retention",
                        details = mapOf(
                            "error" to (e.message ?: "unknown_error"),
                            "totalDeletedSoFar" to totalDeleted.toString()
                        )
                    )
                }

                val intervalHours = if (failed) {
                    1L
                } else {
                    RetentionConfig.retentionOrchestratorIntervalHours
                }
                delay(intervalHours * 60 * 60 * 1000)
            }
        }
    }
}
