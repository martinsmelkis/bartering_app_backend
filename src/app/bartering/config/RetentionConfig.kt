package app.bartering.config

/**
 * Centralized retention policy configuration loaded from environment variables.
 * Values are in days unless explicitly specified.
 */
object RetentionConfig {
    private fun envInt(name: String, default: Int): Int =
        System.getenv(name)?.toIntOrNull() ?: default

    // Chat/domain retention
    val chatDeliveredMessagesDays: Int = envInt("RETENTION_CHAT_MESSAGES_DAYS", 7)
    val chatAnalyticsDays: Int = envInt("RETENTION_CHAT_ANALYTICS_DAYS", 90)
    val readReceiptsDays: Int = envInt("RETENTION_CHAT_READ_RECEIPTS_DAYS", 30)

    // Risk/reviews retention
    val riskDeviceTrackingDays: Int = envInt("RETENTION_RISK_DEVICE_TRACKING_DAYS", 90)
    val riskIpTrackingDays: Int = envInt("RETENTION_RISK_IP_TRACKING_DAYS", 90)
    val riskLocationChangesDays: Int = envInt("RETENTION_RISK_LOCATION_CHANGES_DAYS", 90)
    val riskPatternsDays: Int = envInt("RETENTION_RISK_PATTERNS_DAYS", 180)

    // Posting retention
    val postingHardDeleteGraceDays: Int = envInt("RETENTION_POSTING_HARD_DELETE_GRACE_DAYS", 30)

    // Backup/non-primary store policy
    val backupRetentionDays: Int = envInt("RETENTION_BACKUP_DAYS", 30)
    val backupPolicyEnforcement: Boolean =
        (System.getenv("RETENTION_BACKUP_POLICY_ENFORCEMENT") ?: "true").toBoolean()

    // DSAR = Data Subject Access Request (e.g., export/delete request under GDPR).
    // SLA = Service Level Agreement (maximum completion window in days).
    val dsarSlaDays: Int = envInt("GDPR_DSAR_SLA_DAYS", 30)

    // Orchestrator cadence
    val retentionOrchestratorIntervalHours: Long =
        System.getenv("RETENTION_ORCHESTRATOR_INTERVAL_HOURS")?.toLongOrNull() ?: 24L
}
