package app.bartering.features.compliance.service

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.compliance.model.DsarCoverageItem
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

class DsarCoverageService {

    suspend fun buildLiveCoverage(userId: String): List<DsarCoverageItem> = dbQuery {
        val identityRows = countRows("SELECT COUNT(*) FROM user_registration_data WHERE id = ?", userId)
        val profileRows = countRows("SELECT COUNT(*) FROM user_profiles WHERE user_id = ?", userId)
        val consentRows = countRows("SELECT COUNT(*) FROM user_privacy_consents WHERE user_id = ?", userId)
        val postingsRows = countRows("SELECT COUNT(*) FROM user_postings WHERE user_id = ?", userId)
        val offlineMessagesRows = countRows(
            "SELECT COUNT(*) FROM offline_messages WHERE sender_id = ? OR recipient_id = ?",
            userId,
            userId
        )
        val readReceiptsRows = countRows(
            "SELECT COUNT(*) FROM chat_read_receipts WHERE sender_id = ? OR recipient_id = ?",
            userId,
            userId
        )
        val deviceTrackingRows = countRows("SELECT COUNT(*) FROM review_device_tracking WHERE user_id = ?", userId)
        val ipTrackingRows = countRows("SELECT COUNT(*) FROM review_ip_tracking WHERE user_id = ?", userId)
        val locationChangesRows = countRows("SELECT COUNT(*) FROM user_location_changes WHERE user_id = ?", userId)
        val reportsRows = countRows(
            "SELECT COUNT(*) FROM user_reports WHERE reporter_user_id = ? OR reported_user_id = ? OR reviewed_by = ?",
            userId,
            userId,
            userId
        )
        val dsarRows = countRows("SELECT COUNT(*) FROM compliance_data_subject_requests WHERE user_id = ?", userId)
        val auditRows = countRows("SELECT COUNT(*) FROM compliance_audit_log WHERE actor_id = ?", userId)
        val erasureTaskRows = countRows("SELECT COUNT(*) FROM compliance_erasure_tasks WHERE user_id = ?", userId)

        listOf(
            DsarCoverageItem(
                domain = "identity",
                table = "user_registration_data",
                exportIncluded = true,
                deletionGuaranteedByCascade = true,
                retentionControlled = false,
                notes = "rows=$identityRows"
            ),
            DsarCoverageItem(
                domain = "profile",
                table = "user_profiles",
                exportIncluded = true,
                deletionGuaranteedByCascade = true,
                retentionControlled = false,
                notes = "rows=$profileRows"
            ),
            DsarCoverageItem(
                domain = "consent",
                table = "user_privacy_consents",
                exportIncluded = true,
                deletionGuaranteedByCascade = true,
                retentionControlled = false,
                notes = "rows=$consentRows"
            ),
            DsarCoverageItem(
                domain = "marketplace",
                table = "user_postings",
                exportIncluded = true,
                deletionGuaranteedByCascade = true,
                retentionControlled = true,
                notes = "rows=$postingsRows"
            ),
            DsarCoverageItem(
                domain = "chat",
                table = "offline_messages",
                exportIncluded = false,
                deletionGuaranteedByCascade = false,
                retentionControlled = true,
                notes = "rows=$offlineMessagesRows"
            ),
            DsarCoverageItem(
                domain = "chat",
                table = "chat_read_receipts",
                exportIncluded = false,
                deletionGuaranteedByCascade = true,
                retentionControlled = true,
                notes = "rows=$readReceiptsRows"
            ),
            DsarCoverageItem(
                domain = "security",
                table = "review_device_tracking",
                exportIncluded = false,
                deletionGuaranteedByCascade = true,
                retentionControlled = true,
                notes = "rows=$deviceTrackingRows"
            ),
            DsarCoverageItem(
                domain = "security",
                table = "review_ip_tracking",
                exportIncluded = false,
                deletionGuaranteedByCascade = true,
                retentionControlled = true,
                notes = "rows=$ipTrackingRows"
            ),
            DsarCoverageItem(
                domain = "security",
                table = "user_location_changes",
                exportIncluded = false,
                deletionGuaranteedByCascade = true,
                retentionControlled = true,
                notes = "rows=$locationChangesRows"
            ),
            DsarCoverageItem(
                domain = "moderation",
                table = "user_reports",
                exportIncluded = true,
                deletionGuaranteedByCascade = true,
                retentionControlled = false,
                notes = "rows=$reportsRows"
            ),
            DsarCoverageItem(
                domain = "compliance",
                table = "compliance_data_subject_requests",
                exportIncluded = false,
                deletionGuaranteedByCascade = false,
                retentionControlled = true,
                notes = "rows=$dsarRows"
            ),
            DsarCoverageItem(
                domain = "compliance",
                table = "compliance_audit_log",
                exportIncluded = false,
                deletionGuaranteedByCascade = false,
                retentionControlled = true,
                notes = "rows=$auditRows"
            ),
            DsarCoverageItem(
                domain = "compliance",
                table = "compliance_erasure_tasks",
                exportIncluded = false,
                deletionGuaranteedByCascade = false,
                retentionControlled = true,
                notes = "rows=$erasureTaskRows"
            )
        )
    }

    private fun countRows(sql: String, vararg params: String): Int {
        val connection = TransactionManager.current().connection.connection as java.sql.Connection
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { rs ->
                return if (rs.next()) rs.getInt(1) else 0
            }
        }
    }
}
