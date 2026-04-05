package app.bartering.dashboard_user_moderation.features.network

import app.bartering.dashboard_user_moderation.models.auth.DashboardConfig
import app.bartering.dashboard_user_moderation.models.auth.ModerationSnapshot
import app.bartering.dashboard_user_moderation.models.moderation.UserModerationRow
import app.bartering.dashboard_user_moderation.models.moderation.UserReportStats
import app.bartering.dashboard_user_moderation.models.moderation.UserReviewsResponse
import kotlinx.serialization.Serializable
import app.bartering.dashboard_user_moderation.utils.CryptoUtils
import app.bartering.dashboard_user_moderation.utils.CryptoUtils.signChallenge
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import java.security.interfaces.ECPrivateKey

@Serializable
private data class ReportedUsersResponse(
    val userIds: List<String> = emptyList()
)

class BackendModerationApiClient(
    private val config: DashboardConfig,
    private val client: HttpClient
) {
    private val signingKey: ECPrivateKey? = runCatching {
        if (config.adminPrivateKeyHex.isBlank()) null else CryptoUtils.parseEcPrivateKeyFromHex(config.adminPrivateKeyHex)
    }.getOrNull()

    suspend fun fetchSnapshot(): ModerationSnapshot {
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
            return ModerationSnapshot(
                connected = false,
                backendStatus = healthStatus,
                rows = emptyList(),
                connectionError = "Missing dashboard moderation config: ${missingConfig.joinToString(", ")}" 
            )
        }

        if (signingKey == null) {
            return ModerationSnapshot(
                connected = false,
                backendStatus = healthStatus,
                rows = emptyList(),
                connectionError = "Invalid DASHBOARD_ADMIN_PRIVATE_KEY_HEX (expected secp256r1 private key hex)."
            )
        }

        val activeSigningKey = signingKey
        val rowsResult = runCatching {
            val reportedUsers = signedGet<ReportedUsersResponse>(
                path = "/api/v1/reports/moderation/users",
                signingKey = activeSigningKey
            )
            reportedUsers.userIds.map { userId -> fetchRowForUser(activeSigningKey, userId) }
        }

        return ModerationSnapshot(
            connected = rowsResult.isSuccess,
            backendStatus = healthStatus,
            rows = rowsResult.getOrNull().orEmpty(),
            connectionError = rowsResult.exceptionOrNull()?.message
        )
    }

    private suspend fun fetchRowForUser(signingKey: ECPrivateKey, userId: String): UserModerationRow {
        val reportStats = signedGet<UserReportStats>(
            path = "/api/v1/reports/stats/$userId",
            signingKey = signingKey
        )

        val userReviews = signedGet<UserReviewsResponse>(
            path = "/api/v1/reviews/user/$userId",
            signingKey = signingKey
        )

        val pendingReviewModerationCount = userReviews.reviews.count {
            it.moderationStatus.equals("pending", ignoreCase = true)
        }
        val disputedTransactionCount = userReviews.reviews.count {
            it.transactionStatus.equals("disputed", ignoreCase = true)
        }
        val scamFlagCount = userReviews.reviews.count {
            it.transactionStatus.equals("scam", ignoreCase = true)
        }

        return UserModerationRow(
            userId = userId,
            totalReportsReceived = reportStats.totalReportsReceived,
            pendingReports = reportStats.pendingReports,
            actionsTaken = reportStats.actionsTaken,
            lastReportedAt = reportStats.lastReportedAt,
            totalReviews = userReviews.totalCount,
            pendingReviewModerationCount = pendingReviewModerationCount,
            disputedTransactionCount = disputedTransactionCount,
            scamFlagCount = scamFlagCount
        )
    }

    private suspend inline fun <reified T> signedGet(path: String, signingKey: ECPrivateKey): T {
        val timestamp = System.currentTimeMillis().toString()
        val requestBody = ""
        val challenge = "$timestamp.$requestBody"
        val signatureB64 = signChallenge(signingKey, challenge)

        val response = client.get("${config.backendBaseUrl}$path") {
            headers.append("X-User-ID", config.adminUserId)
            headers.append("X-Timestamp", timestamp)
            headers.append("X-Signature", signatureB64)
        }

        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("$path returned HTTP ${response.status.value}")
        }

        return response.body()
    }
}
