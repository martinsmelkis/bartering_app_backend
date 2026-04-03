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

        call.respond(
            HttpStatusCode.OK,
            ComplianceEvidenceSummaryResponse(
                generatedAt = now.toString(),
                dsarSlaDays = RetentionConfig.dsarSlaDays,
                dsarTotalRequests = dsarEvaluation.totalRequests,
                dsarCompletedWithinSla = dsarEvaluation.completedWithinSla,
                dsarBreached = dsarEvaluation.breached,
                dsarOpen = dsarEvaluation.open,
                dsarBreachedActorIds = dsarEvaluation.breachedActorIds,
                legalHoldAppliedEvents = allEvents.count { it.eventType == "LEGAL_HOLD_APPLIED" },
                legalHoldReleasedEvents = allEvents.count { it.eventType == "LEGAL_HOLD_RELEASED" },
                dataExportRequestedEvents = allEvents.count { it.eventType == "DATA_EXPORT_REQUESTED" },
                dataExportCompletedEvents = allEvents.count { it.eventType == "DATA_EXPORT_COMPLETED" },
                accountDeletionRequestedEvents = allEvents.count { it.eventType == "ACCOUNT_DELETION_REQUESTED" },
                accountDeletionCompletedEvents = allEvents.count { it.eventType == "ACCOUNT_DELETION_COMPLETED" },
                retentionTaskCompletedEvents = allEvents.count { it.eventType == "RETENTION_PURGE_TASK_COMPLETED" },
                retentionCycleCompletedEvents = allEvents.count { it.eventType == "RETENTION_PURGE_CYCLE_COMPLETED" }
            )
        )
    }
}

fun Application.complianceRoutes() {
    routing {
        complianceAdminRoutes()
    }
}
