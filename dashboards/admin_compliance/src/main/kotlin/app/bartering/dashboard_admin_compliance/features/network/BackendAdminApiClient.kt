package app.bartering.dashboard_admin_compliance.features.network

import app.bartering.dashboard_admin_compliance.models.auth.DashboardConfig
import app.bartering.dashboard_admin_compliance.models.auth.DashboardSnapshot
import app.bartering.dashboard_admin_compliance.models.compliance.ComplianceSummaryResponse
import app.bartering.dashboard_admin_compliance.utils.CryptoUtils
import app.bartering.dashboard_admin_compliance.utils.CryptoUtils.signChallenge
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import java.security.interfaces.ECPrivateKey

class BackendAdminApiClient(
    private val config: DashboardConfig,
    private val client: HttpClient
) {
    private val signingKey: ECPrivateKey? = runCatching {
        if (config.adminPrivateKeyHex.isBlank()) null else CryptoUtils.parseEcPrivateKeyFromHex(config.adminPrivateKeyHex)
    }.getOrNull()

    suspend fun fetchSnapshot(): DashboardSnapshot {
        val healthStatus = runCatching {
            val response = client.get("${config.backendBaseUrl}/public-api/v1/healthCheck")
            when (response.status) {
                HttpStatusCode.OK -> "healthy"
                else -> "unhealthy (${response.status.value})"
            }
        }.getOrElse { "unreachable" }

        val missingConfig = buildList {
            if (config.adminUserId.isBlank()) add("DASHBOARD_ADMIN_USER_ID")
            if (config.adminPrivateKeyHex.isBlank()) add("DASHBOARD_ADMIN_PRIVATE_KEY_HEX")
        }

        if (missingConfig.isNotEmpty()) {
            return DashboardSnapshot(
                connected = false,
                backendStatus = healthStatus,
                complianceSummary = null,
                connectionError = "Missing dashboard signing config: ${missingConfig.joinToString(", ")}"
            )
        }

        if (signingKey == null) {
            return DashboardSnapshot(
                connected = false,
                backendStatus = healthStatus,
                complianceSummary = null,
                connectionError = "Invalid DASHBOARD_ADMIN_PRIVATE_KEY_HEX (expected secp256r1 private key hex)."
            )
        }

        val activeSigningKey = signingKey
        val compliance = runCatching {
            val timestamp = System.currentTimeMillis().toString()
            val requestBody = ""
            val challenge = "$timestamp.$requestBody"
            val signatureB64 = signChallenge(activeSigningKey, challenge)

            val response = client.get("${config.backendBaseUrl}/api/v1/admin/compliance/evidence/summary?sinceDays=30") {
                headers.append("X-User-ID", config.adminUserId)
                headers.append("X-Timestamp", timestamp)
                headers.append("X-Signature", signatureB64)
            }

            if (response.status != HttpStatusCode.OK) {
                throw IllegalStateException("Compliance API returned HTTP ${response.status.value}")
            }

            response.body<ComplianceSummaryResponse>()
        }

        return DashboardSnapshot(
            connected = compliance.isSuccess && compliance.getOrNull() != null,
            backendStatus = healthStatus,
            complianceSummary = compliance.getOrNull(),
            connectionError = compliance.exceptionOrNull()?.message
        )
    }

}
