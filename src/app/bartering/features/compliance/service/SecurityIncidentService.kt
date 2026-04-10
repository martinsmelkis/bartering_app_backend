package app.bartering.features.compliance.service

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.compliance.db.ComplianceSecurityIncidentUsersTable
import app.bartering.features.compliance.db.ComplianceSecurityIncidentsTable
import app.bartering.features.notifications.dao.NotificationPreferencesDao
import app.bartering.features.notifications.model.EmailNotification
import app.bartering.features.notifications.model.NotificationData
import app.bartering.features.notifications.model.PushNotification
import app.bartering.features.notifications.service.EmailService
import app.bartering.features.notifications.service.PushNotificationService
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.temporal.ChronoUnit

class SecurityIncidentService(
    private val notificationPreferencesDao: NotificationPreferencesDao,
    private val pushService: PushNotificationService,
    private val emailService: EmailService
) {

    suspend fun createIncident(
        incidentKey: String,
        incidentType: String,
        severity: String,
        summary: String,
        detectionSource: String?,
        affectedSystems: String?,
        detectedAt: Instant,
        riskToRights: Boolean,
        regulatorNotificationRequired: Boolean,
        likelyConsequences: String?,
        mitigationSteps: String?,
        createdBy: String?,
        affectedUserIds: List<String>
    ): Long = dbQuery {
        val now = Instant.now()
        val deadline = detectedAt.plus(72, ChronoUnit.HOURS)

        val incidentId = ComplianceSecurityIncidentsTable.insert {
            it[ComplianceSecurityIncidentsTable.incidentKey] = incidentKey
            it[ComplianceSecurityIncidentsTable.incidentType] = incidentType
            it[ComplianceSecurityIncidentsTable.severity] = severity
            it[ComplianceSecurityIncidentsTable.status] = "detected"
            it[ComplianceSecurityIncidentsTable.summary] = summary
            it[ComplianceSecurityIncidentsTable.detectionSource] = detectionSource
            it[ComplianceSecurityIncidentsTable.affectedSystems] = affectedSystems
            it[ComplianceSecurityIncidentsTable.detectedAt] = detectedAt
            it[ComplianceSecurityIncidentsTable.riskToRights] = riskToRights
            it[ComplianceSecurityIncidentsTable.regulatorNotificationRequired] = regulatorNotificationRequired
            it[ComplianceSecurityIncidentsTable.notificationDeadlineAt] = deadline
            it[ComplianceSecurityIncidentsTable.likelyConsequences] = likelyConsequences
            it[ComplianceSecurityIncidentsTable.mitigationSteps] = mitigationSteps
            it[ComplianceSecurityIncidentsTable.createdBy] = createdBy
            it[ComplianceSecurityIncidentsTable.updatedBy] = createdBy
            it[ComplianceSecurityIncidentsTable.createdAt] = now
            it[ComplianceSecurityIncidentsTable.updatedAt] = now
        }[ComplianceSecurityIncidentsTable.id]

        if (affectedUserIds.isNotEmpty()) {
            ComplianceSecurityIncidentUsersTable.batchInsert(affectedUserIds.distinct()) { userId ->
                this[ComplianceSecurityIncidentUsersTable.incidentId] = incidentId
                this[ComplianceSecurityIncidentUsersTable.userId] = userId
                this[ComplianceSecurityIncidentUsersTable.notificationStatus] = "pending"
                this[ComplianceSecurityIncidentUsersTable.createdAt] = now
                this[ComplianceSecurityIncidentUsersTable.updatedAt] = now
            }
        }

        incidentId
    }

    suspend fun updateIncident(
        incidentId: Long,
        status: String,
        containedAt: Instant?,
        resolvedAt: Instant?,
        regulatorNotifiedAt: Instant?,
        mitigationSteps: String?,
        likelyConsequences: String?,
        updatedBy: String?
    ): Boolean = dbQuery {
        ComplianceSecurityIncidentsTable.update({ ComplianceSecurityIncidentsTable.id eq incidentId }) {
            it[ComplianceSecurityIncidentsTable.status] = status
            if (containedAt != null) it[ComplianceSecurityIncidentsTable.containedAt] = containedAt
            if (resolvedAt != null) it[ComplianceSecurityIncidentsTable.resolvedAt] = resolvedAt
            if (regulatorNotifiedAt != null) it[ComplianceSecurityIncidentsTable.regulatorNotifiedAt] = regulatorNotifiedAt
            if (mitigationSteps != null) it[ComplianceSecurityIncidentsTable.mitigationSteps] = mitigationSteps
            if (likelyConsequences != null) it[ComplianceSecurityIncidentsTable.likelyConsequences] = likelyConsequences
            it[ComplianceSecurityIncidentsTable.updatedBy] = updatedBy
            it[ComplianceSecurityIncidentsTable.updatedAt] = Instant.now()
        } > 0
    }

    suspend fun listIncidents(limit: Int = 200, status: String? = null): List<SecurityIncidentView> = dbQuery {
        val rows = ComplianceSecurityIncidentsTable
            .selectAll()
            .orderBy(ComplianceSecurityIncidentsTable.detectedAt to SortOrder.DESC)
            .limit(limit * 3)
            .toList()

        rows.asSequence()
            .filter { status == null || it[ComplianceSecurityIncidentsTable.status] == status }
            .take(limit)
            .map { it.toSecurityIncidentView() }
            .toList()
    }

    suspend fun notifyAffectedUsers(
        incidentId: Long,
        actorId: String?,
        customTitle: String?,
        customBody: String?,
        selectedUserIds: List<String>? = null
    ): NotificationDispatchResult {
        val incident = getIncident(incidentId) ?: return NotificationDispatchResult(0, 0, 0)

        val rows = dbQuery {
            ComplianceSecurityIncidentUsersTable
                .selectAll()
                .where { ComplianceSecurityIncidentUsersTable.incidentId eq incidentId }
                .toList()
        }

        val targets = rows
            .filter { row ->
                selectedUserIds.isNullOrEmpty() || selectedUserIds.contains(row[ComplianceSecurityIncidentUsersTable.userId])
            }
            .map { row ->
                SecurityIncidentUserTarget(
                    id = row[ComplianceSecurityIncidentUsersTable.id],
                    userId = row[ComplianceSecurityIncidentUsersTable.userId],
                    notificationStatus = row[ComplianceSecurityIncidentUsersTable.notificationStatus]
                )
            }

        var sent = 0
        var failed = 0
        var skipped = 0

        for (target in targets) {
            if (target.notificationStatus == "sent") {
                skipped++
                continue
            }

            val title = customTitle ?: "Important security notice"
            val body = customBody ?: "We detected a security incident that may affect your account. Please review your account security and recent activity."

            val contacts = notificationPreferencesDao.getUserContacts(target.userId)
            var delivered = false
            var lastError: String? = null

            try {
                if (contacts?.email != null) {
                    val emailResult = emailService.sendEmail(
                        EmailNotification(
                            to = listOf(contacts.email),
                            from = "security@bartering.app",
                            fromName = "Bartering Security",
                            subject = title,
                            htmlBody = "<p>$body</p><p>Incident: ${incident.incidentKey}</p>",
                            textBody = "$body\n\nIncident: ${incident.incidentKey}",
                            metadata = mapOf("incidentId" to incidentId.toString(), "userId" to target.userId)
                        )
                    )
                    delivered = delivered || emailResult.success
                    if (!emailResult.success) {
                        lastError = emailResult.errorMessage ?: "email_send_failed"
                    }
                }

                val pushTokens = contacts?.pushTokens?.filter { it.isActive }?.map { it.token }.orEmpty()
                if (pushTokens.isNotEmpty()) {
                    val pushResult = pushService.sendPushNotification(
                        PushNotification(
                            tokens = pushTokens,
                            notification = NotificationData(
                                title = title,
                                body = body,
                                data = mapOf(
                                    "type" to "security_incident",
                                    "incidentId" to incidentId.toString(),
                                    "incidentKey" to incident.incidentKey
                                )
                            ),
                            data = mapOf(
                                "type" to "security_incident",
                                "incidentId" to incidentId.toString(),
                                "incidentKey" to incident.incidentKey
                            )
                        )
                    )
                    delivered = delivered || pushResult.success
                    if (!pushResult.success && lastError == null) {
                        lastError = pushResult.errorMessage ?: "push_send_failed"
                    }
                }

                if (delivered) {
                    markUserNotificationStatus(target.id, "sent", null)
                    sent++
                } else {
                    markUserNotificationStatus(target.id, "failed", lastError ?: "no_delivery_channel")
                    failed++
                }
            } catch (e: Exception) {
                markUserNotificationStatus(target.id, "failed", e.message ?: "notification_exception")
                failed++
            }
        }

        if (sent > 0) {
            updateIncident(
                incidentId = incidentId,
                status = "notified",
                containedAt = null,
                resolvedAt = null,
                regulatorNotifiedAt = null,
                mitigationSteps = null,
                likelyConsequences = null,
                updatedBy = actorId
            )
        }

        return NotificationDispatchResult(sent = sent, failed = failed, skipped = skipped)
    }

    suspend fun summarizeIncidentReadiness(): SecurityIncidentSummaryView = dbQuery {
        val now = Instant.now()
        val in24h = now.plus(24, ChronoUnit.HOURS)

        val rows = ComplianceSecurityIncidentsTable.selectAll().toList()

        val openRows = rows.filter { it[ComplianceSecurityIncidentsTable.status] !in setOf("resolved", "closed") }
        val regulatorRequiredOpen = openRows.filter {
            it[ComplianceSecurityIncidentsTable.regulatorNotificationRequired] &&
                it[ComplianceSecurityIncidentsTable.regulatorNotifiedAt] == null
        }

        val overdue = regulatorRequiredOpen.count {
            !it[ComplianceSecurityIncidentsTable.notificationDeadlineAt].isAfter(now)
        }

        val dueSoon = regulatorRequiredOpen.count {
            val deadline = it[ComplianceSecurityIncidentsTable.notificationDeadlineAt]
            deadline.isAfter(now) && !deadline.isAfter(in24h)
        }

        val userRows = ComplianceSecurityIncidentUsersTable.selectAll().toList()

        SecurityIncidentSummaryView(
            openIncidents = openRows.size,
            criticalOpenIncidents = openRows.count { it[ComplianceSecurityIncidentsTable.severity] == "critical" },
            regulatorNotificationOverdue = overdue,
            regulatorNotificationDueWithin24h = dueSoon,
            affectedUsersPendingNotification = userRows.count { it[ComplianceSecurityIncidentUsersTable.notificationStatus] == "pending" },
            affectedUsersFailedNotification = userRows.count { it[ComplianceSecurityIncidentUsersTable.notificationStatus] == "failed" },
            generatedAt = now
        )
    }

    private suspend fun getIncident(incidentId: Long): SecurityIncidentView? = dbQuery {
        ComplianceSecurityIncidentsTable
            .selectAll()
            .where { ComplianceSecurityIncidentsTable.id eq incidentId }
            .limit(1)
            .map { it.toSecurityIncidentView() }
            .firstOrNull()
    }

    private suspend fun markUserNotificationStatus(rowId: Long, status: String, error: String?) = dbQuery {
        ComplianceSecurityIncidentUsersTable.update({ ComplianceSecurityIncidentUsersTable.id eq rowId }) {
            it[ComplianceSecurityIncidentUsersTable.notificationStatus] = status
            if (status == "sent") {
                it[ComplianceSecurityIncidentUsersTable.notifiedAt] = Instant.now()
                it[ComplianceSecurityIncidentUsersTable.lastError] = null
            } else {
                it[ComplianceSecurityIncidentUsersTable.lastError] = error
            }
            it[ComplianceSecurityIncidentUsersTable.updatedAt] = Instant.now()
        }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toSecurityIncidentView(): SecurityIncidentView {
        val incidentId = this[ComplianceSecurityIncidentsTable.id]
        val userRows = ComplianceSecurityIncidentUsersTable
            .selectAll()
            .where { ComplianceSecurityIncidentUsersTable.incidentId eq incidentId }
            .toList()

        return SecurityIncidentView(
            id = incidentId,
            incidentKey = this[ComplianceSecurityIncidentsTable.incidentKey],
            incidentType = this[ComplianceSecurityIncidentsTable.incidentType],
            severity = this[ComplianceSecurityIncidentsTable.severity],
            status = this[ComplianceSecurityIncidentsTable.status],
            summary = this[ComplianceSecurityIncidentsTable.summary],
            detectionSource = this[ComplianceSecurityIncidentsTable.detectionSource],
            affectedSystems = this[ComplianceSecurityIncidentsTable.affectedSystems],
            detectedAt = this[ComplianceSecurityIncidentsTable.detectedAt],
            notificationDeadlineAt = this[ComplianceSecurityIncidentsTable.notificationDeadlineAt],
            regulatorNotificationRequired = this[ComplianceSecurityIncidentsTable.regulatorNotificationRequired],
            regulatorNotifiedAt = this[ComplianceSecurityIncidentsTable.regulatorNotifiedAt],
            riskToRights = this[ComplianceSecurityIncidentsTable.riskToRights],
            likelyConsequences = this[ComplianceSecurityIncidentsTable.likelyConsequences],
            mitigationSteps = this[ComplianceSecurityIncidentsTable.mitigationSteps],
            createdAt = this[ComplianceSecurityIncidentsTable.createdAt],
            updatedAt = this[ComplianceSecurityIncidentsTable.updatedAt],
            affectedUsersTotal = userRows.size,
            affectedUsersPending = userRows.count { it[ComplianceSecurityIncidentUsersTable.notificationStatus] == "pending" },
            affectedUsersSent = userRows.count { it[ComplianceSecurityIncidentUsersTable.notificationStatus] == "sent" },
            affectedUsersFailed = userRows.count { it[ComplianceSecurityIncidentUsersTable.notificationStatus] == "failed" }
        )
    }
}

data class SecurityIncidentView(
    val id: Long,
    val incidentKey: String,
    val incidentType: String,
    val severity: String,
    val status: String,
    val summary: String,
    val detectionSource: String?,
    val affectedSystems: String?,
    val detectedAt: Instant,
    val notificationDeadlineAt: Instant,
    val regulatorNotificationRequired: Boolean,
    val regulatorNotifiedAt: Instant?,
    val riskToRights: Boolean,
    val likelyConsequences: String?,
    val mitigationSteps: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val affectedUsersTotal: Int,
    val affectedUsersPending: Int,
    val affectedUsersSent: Int,
    val affectedUsersFailed: Int
)

data class NotificationDispatchResult(
    val sent: Int,
    val failed: Int,
    val skipped: Int
)

data class SecurityIncidentSummaryView(
    val openIncidents: Int,
    val criticalOpenIncidents: Int,
    val regulatorNotificationOverdue: Int,
    val regulatorNotificationDueWithin24h: Int,
    val affectedUsersPendingNotification: Int,
    val affectedUsersFailedNotification: Int,
    val generatedAt: Instant
)

data class SecurityIncidentUserTarget(
    val id: Long,
    val userId: String,
    val notificationStatus: String
)