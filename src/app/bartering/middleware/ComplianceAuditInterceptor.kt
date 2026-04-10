package app.bartering.middleware

import app.bartering.features.compliance.service.ComplianceAuditService
import app.bartering.utils.HashUtils
import io.ktor.http.HttpMethod
import io.ktor.server.application.*
import io.ktor.server.request.*
import kotlinx.coroutines.runBlocking
import org.koin.java.KoinJavaComponent.inject
import org.slf4j.LoggerFactory

private data class SensitiveAuditRule(
    val method: HttpMethod,
    val pathPattern: Regex,
    val eventType: String,
    val entityType: String,
    val purpose: String
)

private val sensitiveAuditRules = listOf(
    SensitiveAuditRule(HttpMethod.Delete, Regex("^/api/v1/authentication/user/[^/]+$"), "SENSITIVE_ACCOUNT_DELETE_ACCESS", "user_account", "gdpr_right_to_erasure"),
    SensitiveAuditRule(HttpMethod.Post, Regex("^/api/v1/profile/export-data$"), "SENSITIVE_DATA_EXPORT_ACCESS", "user_export", "gdpr_data_portability"),
    SensitiveAuditRule(HttpMethod.Post, Regex("^/api/v1/profile-consent-update$"), "SENSITIVE_CONSENT_UPDATE_ACCESS", "user_privacy_consents", "gdpr_accountability"),
    SensitiveAuditRule(HttpMethod.Post, Regex("^/api/v1/profile-update$"), "SENSITIVE_PROFILE_UPDATE_ACCESS", "user_profile", "gdpr_accountability"),
    SensitiveAuditRule(HttpMethod.Get, Regex("^/api/v1/profile-info$"), "SENSITIVE_PROFILE_READ_ACCESS", "user_profile", "gdpr_accountability"),
    SensitiveAuditRule(HttpMethod.Get, Regex("^/api/v1/profile-info-extended$"), "SENSITIVE_PROFILE_READ_ACCESS", "user_profile", "gdpr_accountability"),
    SensitiveAuditRule(HttpMethod.Get, Regex("^/api/v1/profiles/search$"), "SENSITIVE_PROFILE_SEARCH_ACCESS", "user_profile", "gdpr_accountability"),
    SensitiveAuditRule(HttpMethod.Get, Regex("^/api/v1/profiles/nearby$"), "SENSITIVE_PROFILE_NEARBY_ACCESS", "user_profile", "gdpr_accountability"),
    SensitiveAuditRule(HttpMethod.Get, Regex("^/api/v1/admin/compliance(/.*)?$"), "SENSITIVE_COMPLIANCE_ADMIN_ACCESS", "compliance_admin", "gdpr_accountability"),
    SensitiveAuditRule(HttpMethod.Post, Regex("^/api/v1/admin/compliance(/.*)?$"), "SENSITIVE_COMPLIANCE_ADMIN_ACCESS", "compliance_admin", "gdpr_accountability")
)

fun Application.installComplianceAuditInterceptor() {
    val log = LoggerFactory.getLogger("ComplianceAuditInterceptor")
    val complianceAuditService: ComplianceAuditService by inject(ComplianceAuditService::class.java)

    log.info("Installing Compliance Audit Interceptor")

    intercept(ApplicationCallPipeline.Monitoring) {
        val method = call.request.httpMethod
        val path = call.request.path()

        val matchingRule = sensitiveAuditRules.firstOrNull { rule ->
            rule.method == method && rule.pathPattern.matches(path)
        }

        if (matchingRule != null) {
            try {
                val actorId = call.request.headers["X-User-ID"]
                val actorType = if (path.startsWith("/api/v1/admin/compliance")) "admin" else if (!actorId.isNullOrBlank()) "user" else "system"

                val ipRaw = call.request.headers["X-Forwarded-For"]?.substringBefore(",")?.trim()
                    ?: call.request.headers["X-Real-IP"]
                    ?: call.request.local.remoteAddress

                val requestId = call.request.headers["X-Request-ID"]
                val deviceId = call.request.headers["X-Device-ID"]

                runBlocking {
                    complianceAuditService.logEvent(
                        actorType = actorType,
                        actorId = actorId,
                        eventType = matchingRule.eventType,
                        entityType = matchingRule.entityType,
                        entityId = path,
                        purpose = matchingRule.purpose,
                        outcome = "success",
                        requestId = requestId,
                        ipHash = ipRaw.takeIf { it.isNotBlank() }?.let { HashUtils.sha256(it) },
                        deviceIdHash = deviceId?.takeIf { it.isNotBlank() }?.let { HashUtils.sha256(it) },
                        details = mapOf(
                            "method" to method.value,
                            "path" to path,
                            "source" to "compliance_audit_interceptor"
                        )
                    )
                }
            } catch (e: Exception) {
                log.debug("Compliance audit interceptor failed for {} {}", method.value, path, e)
            }
        }

        proceed()
    }

    log.info("Compliance Audit Interceptor installed")
}
