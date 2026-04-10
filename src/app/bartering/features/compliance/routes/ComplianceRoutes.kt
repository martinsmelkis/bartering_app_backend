package app.bartering.features.compliance.routes

import app.bartering.config.RetentionConfig
import app.bartering.features.authentication.dao.AuthenticationDaoImpl
import app.bartering.features.authentication.utils.verifyRequestSignature
import app.bartering.features.compliance.model.*
import app.bartering.features.compliance.service.*
import app.bartering.features.profile.dao.UserProfileDaoImpl
import app.bartering.utils.HashUtils
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.koin.java.KoinJavaComponent.inject
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

private suspend fun requireComplianceAdmin(
    routeName: String,
    call: io.ktor.server.application.ApplicationCall,
    authDao: AuthenticationDaoImpl,
    userProfileDao: UserProfileDaoImpl,
    complianceAuditService: ComplianceAuditService,
    allowlistRequired: Boolean = true
): String? {
    val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
    if (authenticatedUserId == null) {
        return null
    }

    val remoteAddress = call.request.local.remoteAddress

    val trustedProxySourceCidrs = getenvCsvOrDefault(
        "COMPLIANCE_ADMIN_TRUSTED_PROXY_SOURCE_CIDRS",
        listOf("127.0.0.1", "::1", "172.16.0.0/12", "192.168.0.0/16", "10.0.0.0/8")
    )

    val trustedAdminClientCidrs = getenvCsvOrDefault(
        "COMPLIANCE_ADMIN_TRUSTED_CLIENT_CIDRS",
        listOf("127.0.0.1", "::1", "172.16.0.0/12", "192.168.0.0/16", "10.0.0.0/8")
    )

    val isTrustedProxySource = trustedProxySourceCidrs.any { cidrOrIp -> isIpInCidrOrExact(remoteAddress, cidrOrIp) }

    val forwardedFor = if (isTrustedProxySource) {
        call.request.headers["X-Forwarded-For"]?.substringBefore(",")?.trim()
    } else {
        null
    }

    val realIp = if (isTrustedProxySource) {
        call.request.headers["X-Real-IP"]?.trim()
    } else {
        null
    }

    val clientIp = when {
        !forwardedFor.isNullOrBlank() -> forwardedFor
        !realIp.isNullOrBlank() -> realIp
        else -> remoteAddress
    }

    val localNetworkAllowed = isPrivateOrLoopbackIp(clientIp) ||
        trustedAdminClientCidrs.any { cidrOrIp -> isIpInCidrOrExact(clientIp, cidrOrIp) }

    if (allowlistRequired && !localNetworkAllowed) {
        complianceAuditService.logEvent(
            actorType = "admin",
            actorId = authenticatedUserId,
            eventType = "COMPLIANCE_ADMIN_ACCESS_DENIED",
            entityType = "compliance_admin",
            entityId = routeName,
            purpose = "gdpr_accountability",
            outcome = "denied",
            requestId = call.request.headers["X-Request-ID"],
            ipHash = (call.request.headers["X-Forwarded-For"]?.substringBefore(",")?.trim()
                ?: call.request.headers["X-Real-IP"])?.let { HashUtils.sha256(it) },
            details = mapOf("reason" to "network_restricted")
        )
        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Compliance admin endpoints are restricted to local/admin network"))
        return null
    }

    val isAllowedAdmin = userProfileDao.isComplianceAdmin(authenticatedUserId)
    if (!isAllowedAdmin) {
        complianceAuditService.logEvent(
            actorType = "admin",
            actorId = authenticatedUserId,
            eventType = "COMPLIANCE_ADMIN_ACCESS_DENIED",
            entityType = "compliance_admin",
            entityId = routeName,
            purpose = "gdpr_accountability",
            outcome = "denied",
            requestId = call.request.headers["X-Request-ID"],
            ipHash = (call.request.headers["X-Forwarded-For"]?.substringBefore(",")?.trim()
                ?: call.request.headers["X-Real-IP"])?.let { HashUtils.sha256(it) },
            details = mapOf("reason" to "account_type_not_admin")
        )
        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "User is not authorized for compliance admin endpoints"))
        return null
    }

    return authenticatedUserId
}

private fun getenvCsvOrDefault(name: String, default: List<String>): List<String> {
    return System.getenv(name)
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.takeIf { it.isNotEmpty() }
        ?: default
}

private fun isPrivateOrLoopbackIp(ip: String): Boolean {
    val normalized = ip.lowercase(Locale.ROOT)
    return normalized == "127.0.0.1" ||
        normalized == "::1" ||
        normalized.startsWith("10.") ||
        normalized.startsWith("192.168.") ||
        normalized.matches(Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"))
}

private fun ipv4ToLong(ip: String): Long? {
    val parts = ip.split(".")
    if (parts.size != 4) return null

    var result = 0L
    for (part in parts) {
        val value = part.toIntOrNull() ?: return null
        if (value !in 0..255) return null
        result = (result shl 8) or value.toLong()
    }
    return result
}

private fun isIpInCidrOrExact(ip: String, cidrOrIp: String): Boolean {
    if (cidrOrIp == ip) return true

    val cidrParts = cidrOrIp.split("/")
    if (cidrParts.size != 2) return false

    val networkIp = cidrParts[0]
    val prefix = cidrParts[1].toIntOrNull() ?: return false
    if (prefix !in 0..32) return false

    val ipLong = ipv4ToLong(ip) ?: return false
    val networkLong = ipv4ToLong(networkIp) ?: return false

    val mask = if (prefix == 0) 0L else (-1L shl (32 - prefix)) and 0xFFFFFFFFL
    return (ipLong and mask) == (networkLong and mask)
}

private fun LegalHoldView.toResponse() = LegalHoldResponse(
    id = id,
    userId = userId,
    reason = reason,
    scope = scope,
    imposedBy = imposedBy,
    imposedAt = imposedAt.toString(),
    expiresAt = expiresAt?.toString()
)

fun Route.complianceAdminRoutes() {
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)
    val userProfileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)
    val legalHoldService: LegalHoldService by inject(LegalHoldService::class.java)
    val complianceAuditService: ComplianceAuditService by inject(ComplianceAuditService::class.java)
    val dsarCoverageService: DsarCoverageService by inject(DsarCoverageService::class.java)
    val dsrService: DataSubjectRequestService by inject(DataSubjectRequestService::class.java)
    val erasureTaskService: ErasureTaskService by inject(ErasureTaskService::class.java)
    val complianceGovernanceService: ComplianceGovernanceService by inject(ComplianceGovernanceService::class.java)
    val securityIncidentService: SecurityIncidentService by inject(SecurityIncidentService::class.java)

    post("/api/v1/admin/compliance/legal-holds/apply") {
        val adminId = requireComplianceAdmin(
            "legal_holds_apply",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@post
        val request = call.receive<ApplyLegalHoldRequest>()

        if (request.userId.isBlank() || request.reason.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId and reason are required"))
            return@post
        }

        val expiresAt = request.expiresAt?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        }
        if (request.expiresAt != null && expiresAt == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "expiresAt must be ISO-8601 timestamp"))
            return@post
        }

        val normalizedScope = request.scope.lowercase().trim()
        if (normalizedScope !in setOf("all", "export", "deletion", "retention")) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "scope must be one of: all, export, deletion, retention"))
            return@post
        }

        val holdId = legalHoldService.applyHold(
            userId = request.userId,
            reason = request.reason,
            scope = normalizedScope,
            imposedBy = adminId,
            expiresAt = expiresAt
        )

        complianceAuditService.logEvent(
            actorType = "admin",
            actorId = adminId,
            eventType = "LEGAL_HOLD_APPLIED",
            entityType = "legal_hold",
            entityId = holdId.toString(),
            purpose = "legal_hold_enforcement",
            outcome = "success",
            requestId = call.request.headers["X-Request-ID"],
            details = mapOf(
                "userId" to request.userId,
                "scope" to normalizedScope,
                "expiresAt" to (expiresAt?.toString() ?: "none")
            )
        )

        call.respond(HttpStatusCode.Created, mapOf("success" to true, "holdId" to holdId))
    }

    post("/api/v1/admin/compliance/legal-holds/release") {
        val adminId = requireComplianceAdmin(
            "legal_holds_release",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@post
        val request = call.receive<ReleaseLegalHoldRequest>()

        val released = legalHoldService.releaseHold(
            holdId = request.holdId,
            releasedBy = adminId,
            releaseReason = request.reason
        )

        complianceAuditService.logEvent(
            actorType = "admin",
            actorId = adminId,
            eventType = if (released) "LEGAL_HOLD_RELEASED" else "LEGAL_HOLD_RELEASE_FAILED",
            entityType = "legal_hold",
            entityId = request.holdId.toString(),
            purpose = "legal_hold_enforcement",
            outcome = if (released) "success" else "error",
            requestId = call.request.headers["X-Request-ID"],
            details = mapOf("reason" to (request.reason ?: "not_provided"))
        )

        if (!released) {
            call.respond(HttpStatusCode.NotFound, mapOf("success" to false, "error" to "Active legal hold not found"))
            return@post
        }

        call.respond(HttpStatusCode.OK, mapOf("success" to true))
    }

    get("/api/v1/admin/compliance/legal-holds") {
        requireComplianceAdmin(
            "legal_holds_list",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@get
        val userId = call.request.queryParameters["userId"]
        val holds = legalHoldService.listActiveHolds(userId).map { it.toResponse() }
        call.respond(HttpStatusCode.OK, holds)
    }

    get("/api/v1/admin/compliance/retention/status") {
        requireComplianceAdmin(
            "retention_status",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@get

        call.respond(
            HttpStatusCode.OK,
            RetentionStatusResponse(
                orchestratorIntervalHours = RetentionConfig.retentionOrchestratorIntervalHours,
                retentionDays = mapOf(
                    "chatDeliveredMessages" to RetentionConfig.chatDeliveredMessagesDays,
                    "chatAnalytics" to RetentionConfig.chatAnalyticsDays,
                    "readReceipts" to RetentionConfig.readReceiptsDays,
                    "riskDeviceTracking" to RetentionConfig.riskDeviceTrackingDays,
                    "riskIpTracking" to RetentionConfig.riskIpTrackingDays,
                    "riskLocationChanges" to RetentionConfig.riskLocationChangesDays,
                    "riskPatterns" to RetentionConfig.riskPatternsDays,
                    "postingHardDeleteGrace" to RetentionConfig.postingHardDeleteGraceDays,
                    "backupRetentionDays" to RetentionConfig.backupRetentionDays
                )
            )
        )
    }

    post("/api/v1/admin/compliance/security-incidents") {
        val adminId = requireComplianceAdmin(
            "security_incident_create",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@post

        val request = call.receive<SecurityIncidentCreateRequest>()

        if (request.incidentKey.isBlank() || request.incidentType.isBlank() || request.summary.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "incidentKey, incidentType and summary are required"))
            return@post
        }

        val severity = request.severity.lowercase().trim()
        if (severity !in setOf("low", "medium", "high", "critical")) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "severity must be one of: low, medium, high, critical"))
            return@post
        }

        val detectedAt = request.detectedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now()

        val incidentId = securityIncidentService.createIncident(
            incidentKey = request.incidentKey,
            incidentType = request.incidentType.lowercase().trim(),
            severity = severity,
            summary = request.summary,
            detectionSource = request.detectionSource,
            affectedSystems = request.affectedSystems,
            detectedAt = detectedAt,
            riskToRights = request.riskToRights,
            regulatorNotificationRequired = request.regulatorNotificationRequired,
            likelyConsequences = request.likelyConsequences,
            mitigationSteps = request.mitigationSteps,
            createdBy = adminId,
            affectedUserIds = request.affectedUserIds
        )

        complianceAuditService.logEvent(
            actorType = "admin",
            actorId = adminId,
            eventType = "SECURITY_INCIDENT_CREATED",
            entityType = "security_incident",
            entityId = incidentId.toString(),
            purpose = "gdpr_article_33_34",
            outcome = "success",
            requestId = call.request.headers["X-Request-ID"],
            details = mapOf(
                "incidentKey" to request.incidentKey,
                "severity" to severity,
                "affectedUsersCount" to request.affectedUserIds.size.toString()
            )
        )

        call.respond(HttpStatusCode.Created, mapOf("success" to true, "incidentId" to incidentId))
    }

    post("/api/v1/admin/compliance/security-incidents/update") {
        val adminId = requireComplianceAdmin(
            "security_incident_update",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@post

        val incidentId = call.request.queryParameters["incidentId"]?.toLongOrNull()
        if (incidentId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "incidentId query parameter is required"))
            return@post
        }

        val request = call.receive<SecurityIncidentUpdateRequest>()
        val status = request.status.lowercase().trim()

        if (status !in setOf("detected", "triaging", "contained", "notified", "resolved", "closed")) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid status"))
            return@post
        }

        val updated = securityIncidentService.updateIncident(
            incidentId = incidentId,
            status = status,
            containedAt = request.containedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
            resolvedAt = request.resolvedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
            regulatorNotifiedAt = request.regulatorNotifiedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
            mitigationSteps = request.mitigationSteps,
            likelyConsequences = request.likelyConsequences,
            updatedBy = adminId
        )

        if (!updated) {
            call.respond(HttpStatusCode.NotFound, mapOf("success" to false, "error" to "Incident not found"))
            return@post
        }

        complianceAuditService.logEvent(
            actorType = "admin",
            actorId = adminId,
            eventType = "SECURITY_INCIDENT_UPDATED",
            entityType = "security_incident",
            entityId = incidentId.toString(),
            purpose = "gdpr_article_33_34",
            outcome = "success",
            requestId = call.request.headers["X-Request-ID"],
            details = mapOf("status" to status)
        )

        call.respond(HttpStatusCode.OK, mapOf("success" to true))
    }

    get("/api/v1/admin/compliance/security-incidents") {
        requireComplianceAdmin(
            "security_incident_list",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@get

        val status = call.request.queryParameters["status"]?.lowercase()?.trim()?.takeIf { it.isNotBlank() }
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 1000)

        val incidents = securityIncidentService.listIncidents(limit = limit, status = status)
            .map {
                SecurityIncidentResponse(
                    id = it.id,
                    incidentKey = it.incidentKey,
                    incidentType = it.incidentType,
                    severity = it.severity,
                    status = it.status,
                    summary = it.summary,
                    detectionSource = it.detectionSource,
                    affectedSystems = it.affectedSystems,
                    detectedAt = it.detectedAt.toString(),
                    notificationDeadlineAt = it.notificationDeadlineAt.toString(),
                    regulatorNotificationRequired = it.regulatorNotificationRequired,
                    regulatorNotifiedAt = it.regulatorNotifiedAt?.toString(),
                    riskToRights = it.riskToRights,
                    likelyConsequences = it.likelyConsequences,
                    mitigationSteps = it.mitigationSteps,
                    affectedUsersTotal = it.affectedUsersTotal,
                    affectedUsersPending = it.affectedUsersPending,
                    affectedUsersSent = it.affectedUsersSent,
                    affectedUsersFailed = it.affectedUsersFailed,
                    createdAt = it.createdAt.toString(),
                    updatedAt = it.updatedAt.toString()
                )
            }

        call.respond(HttpStatusCode.OK, incidents)
    }

    get("/api/v1/admin/compliance/security-incidents/summary") {
        requireComplianceAdmin(
            "security_incident_summary",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@get

        val summary = securityIncidentService.summarizeIncidentReadiness()

        call.respond(
            HttpStatusCode.OK,
            SecurityIncidentSummaryResponse(
                openIncidents = summary.openIncidents,
                criticalOpenIncidents = summary.criticalOpenIncidents,
                regulatorNotificationOverdue = summary.regulatorNotificationOverdue,
                regulatorNotificationDueWithin24h = summary.regulatorNotificationDueWithin24h,
                affectedUsersPendingNotification = summary.affectedUsersPendingNotification,
                affectedUsersFailedNotification = summary.affectedUsersFailedNotification,
                generatedAt = summary.generatedAt.toString()
            )
        )
    }

    post("/api/v1/admin/compliance/security-incidents/notify-users") {
        val adminId = requireComplianceAdmin(
            "security_incident_notify_users",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@post

        val request = call.receive<SecurityIncidentNotifyUsersRequest>()

        val dispatch = securityIncidentService.notifyAffectedUsers(
            incidentId = request.incidentId,
            actorId = adminId,
            customTitle = request.customTitle,
            customBody = request.customBody,
            selectedUserIds = request.userIds
        )

        complianceAuditService.logEvent(
            actorType = "admin",
            actorId = adminId,
            eventType = "SECURITY_INCIDENT_AFFECTED_USERS_NOTIFIED",
            entityType = "security_incident",
            entityId = request.incidentId.toString(),
            purpose = "gdpr_article_34",
            outcome = "success",
            requestId = call.request.headers["X-Request-ID"],
            details = mapOf(
                "sent" to dispatch.sent.toString(),
                "failed" to dispatch.failed.toString(),
                "skipped" to dispatch.skipped.toString()
            )
        )

        call.respond(
            HttpStatusCode.OK,
            mapOf(
                "success" to true,
                "sent" to dispatch.sent,
                "failed" to dispatch.failed,
                "skipped" to dispatch.skipped
            )
        )
    }

    get("/api/v1/admin/compliance/retention/recent") {
        requireComplianceAdmin(
            "retention_recent",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@get
        val sinceHours = call.request.queryParameters["sinceHours"]?.toLongOrNull() ?: 72L
        val events = complianceAuditService.listAuditEvents(
            eventType = "RETENTION_PURGE_CYCLE_COMPLETED",
            from = Instant.now().minus(sinceHours, ChronoUnit.HOURS),
            limit = 100
        )
        call.respond(HttpStatusCode.OK, events)
    }

    get("/api/v1/admin/compliance/audit/search") {
        requireComplianceAdmin(
            "audit_search",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@get

        val actorId = call.request.queryParameters["actorId"]
        val eventType = call.request.queryParameters["eventType"]
        val from = call.request.queryParameters["from"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val to = call.request.queryParameters["to"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 1000)

        val events = complianceAuditService.listAuditEvents(
            actorId = actorId,
            eventType = eventType,
            from = from,
            to = to,
            limit = limit
        )

        call.respond(HttpStatusCode.OK, events)
    }

    get("/api/v1/admin/compliance/dsar/evidence/{userId}") {
        requireComplianceAdmin(
            "dsar_evidence",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@get
        val userId = call.parameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing userId"))

        val userEvents = complianceAuditService.listAuditEvents(actorId = userId, limit = 500)
        val exportEvents = userEvents.count { it.eventType.startsWith("DATA_EXPORT") }
        val deletionEvents = userEvents.count { it.eventType.startsWith("ACCOUNT_DELETION") }
        val consentEvents = userEvents.count { it.eventType.startsWith("CONSENT_") }
        val legalHoldEvents = complianceAuditService.listAuditEvents(
            eventType = "LEGAL_HOLD_APPLIED",
            limit = 1000
        ).count { it.details?.get("userId")?.toString()?.contains(userId) == true }

        call.respond(
            HttpStatusCode.OK, DsarEvidenceResponse(
                userId = userId,
                exportEvents = exportEvents,
                deletionEvents = deletionEvents,
                consentEvents = consentEvents,
                legalHoldEvents = legalHoldEvents,
                latestEvents = userEvents.take(20).map { "${it.createdAt} ${it.eventType} ${it.outcome}" }
            )
        )
    }

    get("/api/v1/admin/compliance/evidence/summary") {
        requireComplianceAdmin(
            "evidence_summary",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@get

        val sinceDays = (call.request.queryParameters["sinceDays"]?.toLongOrNull() ?: 30L).coerceIn(1L, 365L)
        val now = Instant.now()
        val from = now.minus(sinceDays, ChronoUnit.DAYS)

        val allEvents = complianceAuditService.listAuditEvents(from = from, limit = 5000)
        val requestEvents = allEvents.filter {
            it.eventType == "DATA_EXPORT_REQUESTED" || it.eventType == "ACCOUNT_DELETION_REQUESTED"
        }
        val completionEvents = allEvents.filter {
            it.eventType == "DATA_EXPORT_COMPLETED" || it.eventType == "ACCOUNT_DELETION_COMPLETED"
        }

        val dsarEvaluation = DsarSlaEvaluator.evaluate(
            requests = requestEvents,
            completions = completionEvents,
            slaDays = RetentionConfig.dsarSlaDays,
            now = now
        )

        val overdueOpenDsar = dsrService.listOverdueRequests(limit = 2000).size
        val retentionCoverage = complianceGovernanceService.evaluateRetentionCoverage()
        val ropaReadiness = complianceGovernanceService.summarizeRopaReadiness()
        val erasureOverdue = erasureTaskService.countOverduePendingTasks(now)
        val erasureBackupDueSoon = erasureTaskService.countBackupTasksDueSoon(days = 7)
        val erasurePending = erasureTaskService.listTasks(status = "pending", limit = 2000).size
        val securitySummary = securityIncidentService.summarizeIncidentReadiness()

        call.respond(
            HttpStatusCode.OK,
            ComplianceEvidenceSummaryResponse(
                generatedAt = now.toString(),
                dsarSlaDays = RetentionConfig.dsarSlaDays,
                dsarTotalRequests = dsarEvaluation.totalRequests,
                dsarCompletedWithinSla = dsarEvaluation.completedWithinSla,
                dsarBreached = dsarEvaluation.breached,
                dsarOpen = dsarEvaluation.open,
                dsarOverdueOpen = overdueOpenDsar,
                dsarBreachedActorIds = dsarEvaluation.breachedActorIds,
                legalHoldAppliedEvents = allEvents.count { it.eventType == "LEGAL_HOLD_APPLIED" },
                legalHoldReleasedEvents = allEvents.count { it.eventType == "LEGAL_HOLD_RELEASED" },
                dataExportRequestedEvents = allEvents.count { it.eventType == "DATA_EXPORT_REQUESTED" },
                dataExportCompletedEvents = allEvents.count { it.eventType == "DATA_EXPORT_COMPLETED" },
                accountDeletionRequestedEvents = allEvents.count { it.eventType == "ACCOUNT_DELETION_REQUESTED" },
                accountDeletionCompletedEvents = allEvents.count { it.eventType == "ACCOUNT_DELETION_COMPLETED" },
                retentionTaskCompletedEvents = allEvents.count { it.eventType == "RETENTION_PURGE_TASK_COMPLETED" },
                retentionCycleCompletedEvents = allEvents.count { it.eventType == "RETENTION_PURGE_CYCLE_COMPLETED" },
                retentionCoverageRequiredTables = retentionCoverage.requiredTableCount,
                retentionCoverageCoveredTables = retentionCoverage.coveredTableCount,
                retentionCoverageMissingTables = retentionCoverage.missingTableCount,
                retentionCoverageIncompleteTables = retentionCoverage.incompleteTableCount,
                ropaActiveActivities = ropaReadiness.activeActivities,
                ropaReviewDueActivities = ropaReadiness.reviewDueActivities,
                erasurePendingTasks = erasurePending,
                erasureOverdueTasks = erasureOverdue,
                erasureBackupDueSoonTasks = erasureBackupDueSoon,
                securityIncidentsOpen = securitySummary.openIncidents,
                securityIncidentsCriticalOpen = securitySummary.criticalOpenIncidents,
                securityRegulatorNotificationOverdue = securitySummary.regulatorNotificationOverdue,
                securityRegulatorNotificationDueWithin24h = securitySummary.regulatorNotificationDueWithin24h,
                securityAffectedUsersPendingNotification = securitySummary.affectedUsersPendingNotification,
                securityAffectedUsersFailedNotification = securitySummary.affectedUsersFailedNotification
            )
        )
    }

    get("/api/v1/admin/compliance/dsar/coverage/{userId}") {
        requireComplianceAdmin(
            "dsar_coverage",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@get

        val userId = call.parameters["userId"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Missing userId")
        )

        val now = Instant.now()
        val coverage = dsarCoverageService.buildLiveCoverage(userId)

        call.respond(
            HttpStatusCode.OK,
            DsarCoverageResponse(
                generatedAt = now.toString(),
                userId = userId,
                coverage = coverage
            )
        )
    }

    get("/api/v1/admin/compliance/erasure-tasks") {
        requireComplianceAdmin(
            "erasure_tasks_list",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@get

        val userId = call.request.queryParameters["userId"]
        val status = call.request.queryParameters["status"]
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 1000)

        val tasks = erasureTaskService.listTasks(userId = userId, status = status, limit = limit)
            .map {
                ErasureTaskResponse(
                    id = it.id,
                    userId = it.userId,
                    taskType = it.taskType,
                    status = it.status,
                    storageScope = it.storageScope,
                    targetRef = it.targetRef,
                    requestedBy = it.requestedBy,
                    handledBy = it.handledBy,
                    dueAt = it.dueAt?.toString(),
                    completedAt = it.completedAt?.toString(),
                    notes = it.notes,
                    createdAt = it.createdAt.toString(),
                    updatedAt = it.updatedAt.toString()
                )
            }

        call.respond(HttpStatusCode.OK, tasks)
    }

    post("/api/v1/admin/compliance/erasure-tasks/complete") {
        val adminId = requireComplianceAdmin(
            "erasure_tasks_complete",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@post

        val request = call.receive<CompleteErasureTaskRequest>()
        val updated = erasureTaskService.markCompleted(
            taskId = request.taskId,
            handledBy = adminId,
            notes = request.notes
        )

        complianceAuditService.logEvent(
            actorType = "admin",
            actorId = adminId,
            eventType = if (updated) "ERASURE_TASK_COMPLETED" else "ERASURE_TASK_COMPLETE_FAILED",
            entityType = "compliance_erasure_task",
            entityId = request.taskId.toString(),
            purpose = "gdpr_right_to_erasure",
            outcome = if (updated) "success" else "error",
            requestId = call.request.headers["X-Request-ID"],
            details = mapOf("notes" to (request.notes ?: "none"))
        )

        if (!updated) {
            call.respond(HttpStatusCode.NotFound, mapOf("success" to false, "error" to "Task not found"))
            return@post
        }

        call.respond(HttpStatusCode.OK, mapOf("success" to true))
    }

    post("/api/v1/admin/compliance/erasure-tasks/fail") {
        val adminId = requireComplianceAdmin(
            "erasure_tasks_fail",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@post

        val request = call.receive<FailErasureTaskRequest>()
        val updated = erasureTaskService.markFailed(
            taskId = request.taskId,
            handledBy = adminId,
            notes = request.notes
        )

        complianceAuditService.logEvent(
            actorType = "admin",
            actorId = adminId,
            eventType = if (updated) "ERASURE_TASK_FAILED" else "ERASURE_TASK_FAIL_UPDATE_FAILED",
            entityType = "compliance_erasure_task",
            entityId = request.taskId.toString(),
            purpose = "gdpr_right_to_erasure",
            outcome = if (updated) "error" else "denied",
            requestId = call.request.headers["X-Request-ID"],
            details = mapOf("notes" to (request.notes ?: "none"))
        )

        if (!updated) {
            call.respond(HttpStatusCode.NotFound, mapOf("success" to false, "error" to "Task not found"))
            return@post
        }

        call.respond(HttpStatusCode.OK, mapOf("success" to true))
    }

    get("/api/v1/admin/compliance/erasure-tasks/summary") {
        requireComplianceAdmin(
            "erasure_tasks_summary",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@get

        val overdue = erasureTaskService.countOverduePendingTasks()
        val backupDueSoon = erasureTaskService.countBackupTasksDueSoon(days = 7)

        call.respond(
            HttpStatusCode.OK,
            mapOf(
                "overduePendingTasks" to overdue,
                "backupTasksDueWithin7Days" to backupDueSoon,
                "backupRetentionDays" to RetentionConfig.backupRetentionDays
            )
        )
    }

    post("/api/v1/admin/compliance/retention-policy/upsert") {
        val adminId = requireComplianceAdmin(
            "retention_policy_upsert",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@post

        val request = call.receive<RetentionPolicyUpsertRequest>()
        val id = complianceGovernanceService.upsertRetentionPolicy(request, actorId = adminId)

        complianceAuditService.logEvent(
            actorType = "admin",
            actorId = adminId,
            eventType = "RETENTION_POLICY_REGISTER_UPSERTED",
            entityType = "retention_policy_register",
            entityId = id.toString(),
            purpose = "gdpr_accountability",
            outcome = "success",
            requestId = call.request.headers["X-Request-ID"],
            details = mapOf("tableName" to request.tableName)
        )

        call.respond(HttpStatusCode.OK, mapOf("success" to true, "id" to id))
    }

    get("/api/v1/admin/compliance/retention-policy") {
        requireComplianceAdmin(
            "retention_policy_list",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@get

        val activeOnly = call.request.queryParameters["activeOnly"]?.toBoolean() ?: false
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 500).coerceIn(1, 2000)

        val items = complianceGovernanceService.listRetentionPolicies(activeOnly = activeOnly, limit = limit)
            .map {
                RetentionPolicyItemResponse(
                    id = it.id,
                    dataDomain = it.dataDomain,
                    tableName = it.tableName,
                    processingPurpose = it.processingPurpose,
                    legalBasis = it.legalBasis,
                    retentionPeriodDays = it.retentionPeriodDays,
                    deletionTrigger = it.deletionTrigger,
                    deletionMethod = it.deletionMethod,
                    exceptionRules = it.exceptionRules,
                    ownerRole = it.ownerRole,
                    enforcementJob = it.enforcementJob,
                    isActive = it.isActive,
                    createdBy = it.createdBy,
                    updatedBy = it.updatedBy,
                    createdAt = it.createdAt.toString(),
                    updatedAt = it.updatedAt.toString()
                )
            }

        call.respond(HttpStatusCode.OK, items)
    }

    get("/api/v1/admin/compliance/retention-policy/coverage") {
        val adminId = requireComplianceAdmin(
            "retention_policy_coverage",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@get

        val coverage = complianceGovernanceService.evaluateRetentionCoverage()

        complianceAuditService.logEvent(
            actorType = "admin",
            actorId = adminId,
            eventType = "RETENTION_POLICY_COVERAGE_VIEWED",
            entityType = "retention_policy_register",
            entityId = "coverage",
            purpose = "gdpr_accountability",
            outcome = "success",
            requestId = call.request.headers["X-Request-ID"],
            details = mapOf(
                "requiredTableCount" to coverage.requiredTableCount.toString(),
                "coveredTableCount" to coverage.coveredTableCount.toString(),
                "missingTableCount" to coverage.missingTableCount.toString(),
                "incompleteTableCount" to coverage.incompleteTableCount.toString()
            )
        )

        call.respond(
            HttpStatusCode.OK,
            RetentionPolicyCoverageResponse(
                requiredTableCount = coverage.requiredTableCount,
                coveredTableCount = coverage.coveredTableCount,
                missingTableCount = coverage.missingTableCount,
                incompleteTableCount = coverage.incompleteTableCount,
                items = coverage.items.map {
                    RetentionPolicyCoverageItemResponse(
                        tableName = it.tableName,
                        dataDomain = it.dataDomain,
                        present = it.present,
                        active = it.active,
                        hasOwner = it.hasOwner,
                        hasEnforcementJob = it.hasEnforcementJob,
                        missingFields = it.missingFields
                    )
                }
            )
        )
    }

    post("/api/v1/admin/compliance/ropa/upsert") {
        val adminId = requireComplianceAdmin(
            "ropa_upsert",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@post

        val request = call.receive<RopaUpsertRequest>()
        val id = complianceGovernanceService.upsertRopaActivity(request, actorId = adminId)

        complianceAuditService.logEvent(
            actorType = "admin",
            actorId = adminId,
            eventType = "ROPA_REGISTER_UPSERTED",
            entityType = "ropa_register",
            entityId = id.toString(),
            purpose = "gdpr_accountability",
            outcome = "success",
            requestId = call.request.headers["X-Request-ID"],
            details = mapOf("activityKey" to request.activityKey)
        )

        call.respond(HttpStatusCode.OK, mapOf("success" to true, "id" to id))
    }

    get("/api/v1/admin/compliance/ropa") {
        requireComplianceAdmin(
            "ropa_list",
            call,
            authDao,
            userProfileDao,
            complianceAuditService
        ) ?: return@get

        val activeOnly = call.request.queryParameters["activeOnly"]?.toBoolean() ?: false
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 500).coerceIn(1, 2000)

        val items = complianceGovernanceService.listRopaActivities(activeOnly = activeOnly, limit = limit)
            .map {
                RopaItemResponse(
                    id = it.id,
                    activityKey = it.activityKey,
                    activityName = it.activityName,
                    controllerName = it.controllerName,
                    controllerContact = it.controllerContact,
                    dpoContact = it.dpoContact,
                    processingPurposes = it.processingPurposes,
                    dataSubjectCategories = it.dataSubjectCategories,
                    personalDataCategories = it.personalDataCategories,
                    recipientCategories = it.recipientCategories,
                    thirdCountryTransfers = it.thirdCountryTransfers,
                    safeguardsDescription = it.safeguardsDescription,
                    legalBasis = it.legalBasis,
                    retentionSummary = it.retentionSummary,
                    tomsSummary = it.tomsSummary,
                    sourceSystems = it.sourceSystems,
                    processors = it.processors,
                    jointControllers = it.jointControllers,
                    isActive = it.isActive,
                    reviewDueAt = it.reviewDueAt?.toString(),
                    lastReviewedAt = it.lastReviewedAt?.toString(),
                    createdBy = it.createdBy,
                    updatedBy = it.updatedBy,
                    createdAt = it.createdAt.toString(),
                    updatedAt = it.updatedAt.toString()
                )
            }

        call.respond(HttpStatusCode.OK, items)
    }
}

fun Application.complianceRoutes() {
    routing {
        complianceAdminRoutes()
    }
}
